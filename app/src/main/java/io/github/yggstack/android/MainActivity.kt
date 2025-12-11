package io.github.yggstack.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yggstack.android.data.ConfigRepository
import io.github.yggstack.android.ui.configuration.ConfigurationScreen
import io.github.yggstack.android.ui.configuration.ConfigurationViewModel
import io.github.yggstack.android.ui.diagnostics.DiagnosticsScreen
import io.github.yggstack.android.ui.settings.SettingsScreen
import io.github.yggstack.android.ui.theme.YggstackAndroidTheme
import io.github.yggstack.android.utils.PermissionHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val repository = ConfigRepository(this)
            val theme by repository.themeFlow.collectAsState(initial = "system")
            val systemInDarkTheme = isSystemInDarkTheme()
            
            val darkTheme = when (theme) {
                "light" -> false
                "dark" -> true
                else -> systemInDarkTheme
            }
            
            YggstackAndroidTheme(darkTheme = darkTheme) {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = ConfigRepository(context)
    val configViewModel: ConfigurationViewModel = viewModel(
        factory = ConfigurationViewModel.Factory(repository, context)
    )

    var selectedScreen by remember { mutableStateOf(0) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // Check permissions on startup
    LaunchedEffect(Unit) {
        if (!PermissionHelper.hasAllBackgroundPermissions(context)) {
            showPermissionDialog = true
        }
    }

    // Permission dialog
    if (showPermissionDialog) {
        BackgroundPermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = {
                showPermissionDialog = false
                // Try to open battery optimization settings first
                try {
                    context.startActivity(PermissionHelper.getBatteryOptimizationIntent(context))
                } catch (e: Exception) {
                    // If that fails, open app info settings
                    context.startActivity(PermissionHelper.getAppInfoIntent(context))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_configuration)) },
                    selected = selectedScreen == 0,
                    onClick = { selectedScreen = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_diagnostics)) },
                    selected = selectedScreen == 1,
                    onClick = { selectedScreen = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_info)) },
                    selected = selectedScreen == 2,
                    onClick = { selectedScreen = 2 }
                )
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedScreen) {
                0 -> ConfigurationScreen(viewModel = configViewModel)
                1 -> DiagnosticsScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun BackgroundPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.permission_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.permission_dialog_message))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.permission_dialog_instructions),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.permission_dialog_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.permission_dialog_later))
            }
        }
    )
}

