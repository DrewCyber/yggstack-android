package link.yggdrasil.yggstack.android.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import link.yggdrasil.yggstack.android.BuildConfig
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.utils.AutostartHelper
import link.yggdrasil.yggstack.android.utils.PermissionHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = remember { ConfigRepository(context) }
    val selectedTheme by repository.themeFlow.collectAsState(initial = "system")
    val selectedLanguage by repository.languageFlow.collectAsState(initial = "en")
    val autostartEnabled by repository.autostartFlow.collectAsState(initial = false)
    val autoUpdateEnabled by repository.autoUpdateFlow.collectAsState(initial = true)
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Theme Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.theme_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Column {
                    ThemeOption(
                        label = stringResource(R.string.theme_light),
                        selected = selectedTheme == "light",
                        onClick = {
                            coroutineScope.launch {
                                repository.saveTheme("light")
                            }
                        }
                    )
                    ThemeOption(
                        label = stringResource(R.string.theme_dark),
                        selected = selectedTheme == "dark",
                        onClick = {
                            coroutineScope.launch {
                                repository.saveTheme("dark")
                            }
                        }
                    )
                    ThemeOption(
                        label = stringResource(R.string.theme_system),
                        selected = selectedTheme == "system",
                        onClick = {
                            coroutineScope.launch {
                                repository.saveTheme("system")
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // System Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.system_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Autostart
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.autostart_enabled),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.autostart_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autostartEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                repository.saveAutostart(enabled)
                            }
                            
                            // Try to open manufacturer-specific autostart settings when enabled
                            if (enabled && AutostartHelper.requiresManufacturerAutostartPermission()) {
                                AutostartHelper.openAutostartSettings(context)
                            }
                        }
                    )
                }
                
                // Manufacturer-specific autostart button
                if (autostartEnabled && AutostartHelper.requiresManufacturerAutostartPermission()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val opened = AutostartHelper.openAutostartSettings(context)
                            if (!opened) {
                                // Fallback to general settings
                                try {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                } catch (e: Exception) {
                                    // Ignore
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open ${AutostartHelper.getManufacturerName()} Autostart Settings")
                    }
                }
                
                // Check battery optimization button
                if (autostartEnabled && !PermissionHelper.isBatteryOptimizationDisabled(context)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(PermissionHelper.getBatteryOptimizationIntent(context))
                            } catch (e: Exception) {
                                // Fallback to general settings
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disable Battery Optimization")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Check for updates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_update_enabled),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.auto_update_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoUpdateEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                repository.saveAutoUpdate(enabled)
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Language Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var expanded by remember { mutableStateOf(false) }
                val languages = listOf(
                    "system" to stringResource(R.string.language_system),
                    "en" to stringResource(R.string.language_english),
                    "ru" to stringResource(R.string.language_russian)
                )
                val selectedLanguageLabel = languages.find { it.first == selectedLanguage }?.second
                    ?: stringResource(R.string.language_system)

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedLanguageLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.language_section)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        languages.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    expanded = false
                                    coroutineScope.launch {
                                        repository.saveLanguage(code)
                                        // Recreate activity to apply new locale immediately
                                        activity?.recreate()
                                    }
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // About Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.about_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                SettingItem(
                    label = stringResource(R.string.version),
                    value = BuildConfig.VERSION_NAME
                )

                SettingItem(
                    label = "Commit",
                    value = BuildConfig.COMMIT_HASH
                )

                SettingItem(
                    label = stringResource(R.string.library_version),
                    value = "yggstack 1.0.5"
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Repository link
                SettingLinkItem(
                    label = stringResource(R.string.repository),
                    url = "https://github.com/DrewCyber/yggstack-android",
                    context = context
                )
                
                // Telegram chat link
                SettingLinkItem(
                    label = stringResource(R.string.telegram_chat),
                    url = "http://t.me/yggstackandroid",
                    context = context
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Force Stop Button
        Button(
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general settings
                    try {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e2: Exception) {
                        // Ignore
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Force Stop")
        }
    }
}

@Composable
fun ThemeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun SettingItem(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SettingLinkItem(
    label: String,
    url: String,
    context: android.content.Context
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.Default.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

