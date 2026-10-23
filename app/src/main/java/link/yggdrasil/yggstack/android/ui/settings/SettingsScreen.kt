package link.yggdrasil.yggstack.android.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import link.yggdrasil.yggstack.android.BuildConfig
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.BackupConfig
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.ServiceStartMode
import link.yggdrasil.yggstack.android.data.VersionChecker
import link.yggdrasil.yggstack.android.data.YggstackConfig
import link.yggdrasil.yggstack.android.ui.diagnostics.ExportBackupDialog
import link.yggdrasil.yggstack.android.ui.diagnostics.ImportPreviewDialog
import link.yggdrasil.yggstack.android.utils.AppLinkHelper
import link.yggdrasil.yggstack.android.utils.AutostartHelper
import link.yggdrasil.yggstack.android.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onCheckForUpdate: () -> Unit = {},
    initialUseSystemColors: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = remember { ConfigRepository(context) }
    val selectedTheme by repository.themeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val selectedLanguage by repository.languageFlow.collectAsStateWithLifecycle(initialValue = "en")
    val autostartEnabled by repository.autostartFlow.collectAsStateWithLifecycle(initialValue = false)
    val serviceOnAppStart by repository.serviceOnAppStartFlow.collectAsStateWithLifecycle(initialValue = ServiceStartMode.DO_NOTHING)
    val autoUpdateEnabled by repository.autoUpdateFlow.collectAsStateWithLifecycle(initialValue = true)
    // Initial value is read once at activity start (MainActivity) and passed
    // in, so opening this tab doesn't block on a DataStore read
    val useSystemColors by repository.useSystemColorsFlow.collectAsStateWithLifecycle(initialValue = initialUseSystemColors)
    val coroutineScope = rememberCoroutineScope()
    val systemColorsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // UI Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.ui_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Theme: light / dark / system default
                var themeExpanded by remember { mutableStateOf(false) }
                val themeOptions = listOf(
                    "light" to stringResource(R.string.theme_light),
                    "dark" to stringResource(R.string.theme_dark),
                    "system" to stringResource(R.string.theme_system)
                )
                val selectedThemeLabel = themeOptions.find { it.first == selectedTheme }?.second
                    ?: stringResource(R.string.theme_system)

                ExposedDropdownMenuBox(
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = !themeExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedThemeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.theme_section)) },
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
                        expanded = themeExpanded,
                        onDismissRequest = { themeExpanded = false }
                    ) {
                        themeOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    themeExpanded = false
                                    coroutineScope.launch {
                                        repository.saveTheme(value)
                                    }
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                // Colors: app / system — only where Material You exists
                if (systemColorsSupported) {
                    Spacer(modifier = Modifier.height(8.dp))

                    var colorsExpanded by remember { mutableStateOf(false) }
                    val colorOptions = listOf(
                        false to stringResource(R.string.theme_colors_app),
                        true to stringResource(R.string.theme_colors_system)
                    )
                    val selectedColorsLabel = colorOptions.find { it.first == useSystemColors }?.second
                        ?: stringResource(R.string.theme_colors_app)

                    ExposedDropdownMenuBox(
                        expanded = colorsExpanded,
                        onExpandedChange = { colorsExpanded = !colorsExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedColorsLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.theme_colors_section)) },
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
                            expanded = colorsExpanded,
                            onDismissRequest = { colorsExpanded = false }
                        ) {
                            colorOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        colorsExpanded = false
                                        coroutineScope.launch {
                                            repository.saveUseSystemColors(value)
                                        }
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Backup Configuration Card (moved from the Diagnostics Config tab)
        BackupConfigurationCard(repository = repository)

        Spacer(modifier = Modifier.height(8.dp))

        // General Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.general_section),
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

                Spacer(modifier = Modifier.height(8.dp))

                // Service on app start
                var serviceStartExpanded by remember { mutableStateOf(false) }
                val startModes = listOf(
                    ServiceStartMode.ALWAYS to stringResource(R.string.service_on_app_start_always),
                    ServiceStartMode.KEEP_STATE to stringResource(R.string.service_on_app_start_keep_state),
                    ServiceStartMode.DO_NOTHING to stringResource(R.string.service_on_app_start_do_nothing)
                )
                val selectedStartModeLabel = startModes.find { it.first == serviceOnAppStart }?.second
                    ?: stringResource(R.string.service_on_app_start_do_nothing)

                ExposedDropdownMenuBox(
                    expanded = serviceStartExpanded,
                    onExpandedChange = { serviceStartExpanded = !serviceStartExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedStartModeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.service_on_app_start_label)) },
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
                        expanded = serviceStartExpanded,
                        onDismissRequest = { serviceStartExpanded = false }
                    ) {
                        startModes.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    serviceStartExpanded = false
                                    coroutineScope.launch {
                                        repository.saveServiceOnAppStart(mode)
                                    }
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Language
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
                    value = BuildConfig.APP_VERSION_DISPLAY,
                    url = BuildConfig.APP_VERSION_URL,
                    context = context
                )

                SettingItem(
                    label = stringResource(R.string.yggstack_version),
                    value = BuildConfig.YGGSTACK_VERSION_DISPLAY,
                    url = BuildConfig.YGGSTACK_VERSION_URL,
                    context = context
                )

                SettingItem(
                    label = stringResource(R.string.yggdrasil_version),
                    value = BuildConfig.YGGDRASIL_VERSION_DISPLAY,
                    url = BuildConfig.YGGDRASIL_VERSION_URL,
                    context = context
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

        OutlinedButton(
            onClick = onCheckForUpdate,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.check_for_update_now))
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                coroutineScope.launch {
                    VersionChecker(context).clearPostponedVersion()
                    repository.saveSuppressTransitWarning(false)
                    repository.saveAppLinksDialogSuppressed(false)
                    Toast.makeText(
                        context,
                        context.getString(R.string.suspended_dialogs_reset),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.reset_suspended_dialogs))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Link-handling check: warn when Android isn't set to open this app's
        // links (Settings → Open by default). Re-checked on every resume so the
        // card disappears once the user enables the toggles. Its bottom
        // spacing lives inside the if so the button rhythm stays even when
        // the card is hidden.
        var disabledLinkDomains by remember { mutableStateOf(AppLinkHelper.getDisabledLinkDomains(context)) }
        LifecycleResumeEffect(Unit) {
            disabledLinkDomains = AppLinkHelper.getDisabledLinkDomains(context)
            onPauseOrDispose { }
        }
        if (disabledLinkDomains.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.app_links_not_enabled,
                                disabledLinkDomains.joinToString()
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { AppLinkHelper.openOpenByDefaultSettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.enable_app_links))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // System Menu Button: opens Android's per-app system settings screen.
        // Outlined + gear icon: a plain utility action, not a warning.
        OutlinedButton(
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.system_menu))
        }
    }
}

/**
 * Backup configuration card: export to file, import/restore, share. Moved
 * from the Diagnostics Config tab; the preview dialogs are the shared
 * composables from the diagnostics package.
 */
@Composable
private fun BackupConfigurationCard(repository: ConfigRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val yggstackConfig by repository.configFlow.collectAsStateWithLifecycle(initialValue = null)

    var showImportPreview by remember { mutableStateOf(false) }
    var importedBackup by remember { mutableStateOf<BackupConfig?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    // Which card button opened the dialog: confirm saves a file (false) or
    // hands the backup to the share sheet (true)
    var exportForShare by remember { mutableStateOf(false) }
    var includeYggdrasil by remember { mutableStateOf(false) }
    var includePrivateKey by remember { mutableStateOf(false) }

    // Recompute backup TOML whenever config or ticks change (used by export
    // launcher and share). The private key only applies with Yggdrasil
    // parameters ticked — it is one of them.
    val backupToml = remember(yggstackConfig, includeYggdrasil, includePrivateKey) {
        yggstackConfig?.let {
            BackupConfig.fromYggstackConfig(it, includeYggdrasil, includePrivateKey && includeYggdrasil).toToml()
        } ?: ""
    }

    // Export launcher – saves .toml file. Picker type is application/toml,
    // not text/plain: pickers map known types to an extension and append it
    // when the name doesn't end in one (Samsung saves a text/plain
    // "….toml" as ".toml.txt"); nothing maps application/toml, so the
    // suggested .toml name is kept verbatim.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/toml")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { output ->
                        output.write(backupToml.toByteArray())
                    }
                    Toast.makeText(context, "Configuration exported successfully", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Import launcher – TOML backups; files with no yggstack sections
    // (PDFs, spreadsheets, random text) are rejected by the parser.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val fileContent = context.contentResolver.openInputStream(it)?.use { input ->
                        input.bufferedReader().readText()
                    }

                    if (fileContent != null) {
                        val result = BackupConfig.fromToml(fileContent)
                        result.fold(
                            onSuccess = { backup ->
                                val validation = backup.validate()
                                validation.fold(
                                    onSuccess = {
                                        importedBackup = backup
                                        showImportPreview = true
                                    },
                                    onFailure = { error ->
                                        Toast.makeText(context, "Invalid backup: ${error.message}", Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            onFailure = { error ->
                                Toast.makeText(context, "Failed to parse backup: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Import preview dialog; applies the backup as previewed (the dialog
    // blanks the key when "Ignore private key" is ticked)
    if (showImportPreview && importedBackup != null) {
        ImportPreviewDialog(
            backup = importedBackup!!,
            onConfirm = { backup ->
                scope.launch {
                    try {
                        // Apply to the current config and persist; the
                        // collected configFlow re-emits, refreshing the UI
                        val currentConfig = yggstackConfig ?: repository.configFlow.first()
                        repository.saveConfig(backup.applyTo(currentConfig))
                        Toast.makeText(context, "Configuration imported successfully", Toast.LENGTH_SHORT).show()
                        showImportPreview = false
                        importedBackup = null
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to apply backup: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = {
                showImportPreview = false
                importedBackup = null
            }
        )
    }

    // Export/share dialog — the same "Backup Configuration" screen; the
    // confirm action depends on which card button opened it
    if (showExportDialog && yggstackConfig != null) {
        ExportBackupDialog(
            yggstackConfig = yggstackConfig!!,
            includeYggdrasil = includeYggdrasil,
            includePrivateKey = includePrivateKey,
            onToggleYggdrasil = {
                includeYggdrasil = it
                // Turning the parent tick off resets the nested one, so
                // re-enabling never silently re-includes the private key
                if (!it) includePrivateKey = false
            },
            onTogglePrivateKey = { includePrivateKey = it },
            forShare = exportForShare,
            onConfirm = {
                showExportDialog = false
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                if (exportForShare) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { writeBackupShareFile(context, backupToml, timestamp) }
                            .onSuccess { uri ->
                                withContext(Dispatchers.Main) {
                                    context.startActivity(backupShareIntent(context, uri))
                                }
                            }
                            .onFailure { e ->
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                    }
                } else {
                    exportLauncher.launch("yggstack_backup_$timestamp.toml")
                }
            },
            onDismiss = { showExportDialog = false }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_configuration),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Backup (export) button – opens export dialog.
                // Icons: "save" for backup, "backup restore" for restore.
                IconButton(
                    onClick = {
                        exportForShare = false
                        showExportDialog = true
                    },
                    enabled = yggstackConfig != null
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_save),
                        contentDescription = "Backup configuration",
                        tint = if (yggstackConfig != null) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
                // Restore (import) button
                IconButton(onClick = {
                    importLauncher.launch(arrayOf("*/*"))
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_backup_restore),
                        contentDescription = "Restore configuration",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // Share button – the same dialog in share mode; confirm
                // hands the backup file to other apps via the share sheet
                IconButton(
                    onClick = {
                        exportForShare = true
                        showExportDialog = true
                    },
                    enabled = yggstackConfig != null
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share configuration",
                        tint = if (yggstackConfig != null) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }
    }
}

/**
 * Writes [toml] to a fresh cache file and returns its FileProvider URI.
 * Previous share copies are removed so repeated shares don't pile up in cache.
 * Must run on a worker thread.
 */
private fun writeBackupShareFile(context: android.content.Context, toml: String, timestamp: String): Uri {
    context.cacheDir.listFiles { f -> f.name.startsWith("yggstack_backup_") }?.forEach { it.delete() }
    val shareFile = File(context.cacheDir, "yggstack_backup_$timestamp.toml")
    shareFile.writeText(toml)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
}

/**
 * ACTION_SEND intent handing the backup file to the share sheet. clipData
 * mirrors EXTRA_STREAM so the read grant is honored on all Android versions.
 */
private fun backupShareIntent(context: android.content.Context, uri: Uri): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "application/toml"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        context.getString(R.string.backup_share_chooser_title)
    )

@Composable
fun SettingItem(
    label: String,
    value: String,
    url: String? = null,
    context: android.content.Context? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (url != null && context != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            modifier = if (url != null && context != null) {
                Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                }
            } else {
                Modifier
            }
        )
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

