package link.yggdrasil.yggstack.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.VersionChecker
import link.yggdrasil.yggstack.android.data.VersionInfo
import link.yggdrasil.yggstack.android.ui.configuration.ConfigurationScreen
import link.yggdrasil.yggstack.android.ui.configuration.ConfigurationViewModel
import link.yggdrasil.yggstack.android.ui.diagnostics.DiagnosticsScreen
import link.yggdrasil.yggstack.android.ui.settings.SettingsScreen
import link.yggdrasil.yggstack.android.ui.theme.YggstackAndroidTheme
import link.yggdrasil.yggstack.android.utils.PermissionHelper
import kotlinx.coroutines.launch

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
    var permissionsChecked by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Notification permission launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // After notification permission is handled, check all permissions again
        if (!PermissionHelper.hasAllBackgroundPermissions(context)) {
            showPermissionDialog = true
        }
    }

    // Check permissions on startup
    LaunchedEffect(Unit) {
        // First, request notification permission if needed (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            !PermissionHelper.isNotificationPermissionGranted(context)) {
            notificationPermissionLauncher.launch(PermissionHelper.NOTIFICATION_PERMISSION)
        } else if (!PermissionHelper.hasAllBackgroundPermissions(context)) {
            showPermissionDialog = true
        }
        permissionsChecked = true
    }

    // Permission dialog
    if (showPermissionDialog) {
        val needsNotification = !PermissionHelper.isNotificationPermissionGranted(context)
        val needsBattery = !PermissionHelper.isBatteryOptimizationDisabled(context)
        val needsBackground = PermissionHelper.isBackgroundRestricted(context)
        
        BackgroundPermissionDialog(
            needsNotification = needsNotification,
            needsBattery = needsBattery,
            needsBackground = needsBackground,
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = {
                showPermissionDialog = false
                // Prioritize based on what's needed
                try {
                    when {
                        needsNotification -> {
                            context.startActivity(PermissionHelper.getNotificationSettingsIntent(context))
                        }
                        needsBattery -> {
                            context.startActivity(PermissionHelper.getBatteryOptimizationIntent(context))
                        }
                        else -> {
                            context.startActivity(PermissionHelper.getAppInfoIntent(context))
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to app info settings
                    context.startActivity(PermissionHelper.getAppInfoIntent(context))
                }
            }
        )
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
    needsNotification: Boolean,
    needsBattery: Boolean,
    needsBackground: Boolean,
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
                
                if (needsNotification) {
                    Text(
                        text = "• " + stringResource(R.string.permission_notifications_required),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (needsBattery) {
                    Text(
                        text = "• " + stringResource(R.string.permission_battery_required),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (needsBackground) {
                    Text(
                        text = "• " + stringResource(R.string.permission_background_required),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.permission_dialog_instructions),
                    style = MaterialTheme.typography.bodySmall
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

@Composable
fun UpdateAvailableDialog(
    versionInfo: VersionInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text(stringResource(R.string.new_version_available)) },
        text = {
            Column {
                Text(
                    text = "Version ${versionInfo.latestVersion} is available",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Current version: ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (versionInfo.releaseNotes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Release Notes:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(
                            markdown = versionInfo.releaseNotes,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload) {
                Text(stringResource(R.string.download_update))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later))
            }
        }
    )
}

