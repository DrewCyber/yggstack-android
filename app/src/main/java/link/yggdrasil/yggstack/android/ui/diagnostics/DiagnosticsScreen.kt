package link.yggdrasil.yggstack.android.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.BackupConfig
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.YggstackConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = ConfigRepository(context)
    val viewModel: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModel.Factory(repository, context)
    )

    val tabs = listOf(
        stringResource(R.string.tab_config),
        stringResource(R.string.tab_peers),
        stringResource(R.string.tab_logs)
    )
    
    // Load saved tab before creating pager
    var initialTab by remember { mutableStateOf<Int?>(null) }
    
    LaunchedEffect(Unit) {
        initialTab = repository.diagnosticsTabFlow.first().coerceIn(0, 2)
    }
    
    // Only show content after initial tab is loaded
    initialTab?.let { startPage ->
        val pagerState = rememberPagerState(
            initialPage = startPage,
            pageCount = { tabs.size }
        )
        val coroutineScope = rememberCoroutineScope()

        // Save tab index when user changes it
        LaunchedEffect(pagerState.currentPage) {
            repository.saveDiagnosticsTab(pagerState.currentPage)
        }

        Column(modifier = modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(title) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> ConfigViewer(viewModel)
                    1 -> PeerStatus(
                        viewModel = viewModel,
                        isVisible = pagerState.currentPage == 1
                    )
                    2 -> LogsViewer(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigViewer(viewModel: DiagnosticsViewModel) {
    val currentConfig by viewModel.currentConfig.collectAsState()
    val yggstackConfig by viewModel.yggstackConfig.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val scope = rememberCoroutineScope()

    var showImportPreview by remember { mutableStateOf(false) }
    var importedBackup by remember { mutableStateOf<BackupConfig?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var includeYggdrasil by remember { mutableStateOf(false) }

    // Recompute backup TOML whenever config or toggle changes (used by export launcher)
    val backupToml = remember(yggstackConfig, includeYggdrasil) {
        yggstackConfig?.let { BackupConfig.fromYggstackConfig(it, includeYggdrasil).toToml() } ?: ""
    }

    // Export launcher – saves .toml file
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
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

    // Import launcher – accepts both TOML (new) and JSON (legacy)
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
                        val result = BackupConfig.fromString(fileContent)
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

    // Import preview dialog
    if (showImportPreview && importedBackup != null) {
        ImportPreviewDialog(
            backup = importedBackup!!,
            onConfirm = {
                scope.launch {
                    try {
                        viewModel.importBackup(importedBackup!!)
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

    // Export dialog
    if (showExportDialog && yggstackConfig != null) {
        ExportBackupDialog(
            yggstackConfig = yggstackConfig!!,
            includeYggdrasil = includeYggdrasil,
            onToggle = { includeYggdrasil = it },
            onConfirm = {
                showExportDialog = false
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                exportLauncher.launch("yggstack_backup_$timestamp.toml")
            },
            onDismiss = { showExportDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Backup Configuration Card (top)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
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
                    Text(
                        text = stringResource(R.string.backup_restore_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Export button – opens export dialog
                    IconButton(
                        onClick = { showExportDialog = true },
                        enabled = yggstackConfig != null
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export configuration",
                            tint = if (yggstackConfig != null) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                    // Import button
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    }) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = "Import configuration",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Yggdrasil Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "yggdrasil.conf",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isServiceRunning) stringResource(R.string.service_running) else stringResource(R.string.service_stopped_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isServiceRunning) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentConfig.isNotEmpty()) {
                        IconButton(onClick = {
                            val clip = ClipData.newPlainText("Yggstack Config", currentConfig)
                            clipboardManager.setPrimaryClip(clip)
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy config",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (isServiceRunning) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (currentConfig.isNotEmpty()) {
                    Text(
                        text = currentConfig,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_config_available),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExportBackupDialog(
    yggstackConfig: YggstackConfig,
    includeYggdrasil: Boolean,
    onToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val backupToml = remember(yggstackConfig, includeYggdrasil) {
        BackupConfig.fromYggstackConfig(yggstackConfig, includeYggdrasil).toToml()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.backup_export_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Divider(modifier = Modifier.padding(bottom = 12.dp))

                // Toggle: include Yggdrasil parameters
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.backup_include_yggdrasil),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = includeYggdrasil,
                        onCheckedChange = onToggle
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (includeYggdrasil)
                        stringResource(R.string.backup_desc_full)
                    else
                        stringResource(R.string.backup_desc_yggstack_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.backup_preview_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = backupToml,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) {
                        Text(stringResource(R.string.backup_export_button))
                    }
                }
            }
        }
    }
}

@Composable
fun ImportPreviewDialog(
    backup: BackupConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.import_config_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Text(
                    text = stringResource(R.string.import_config_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Yggdrasil settings (only present in full backups)
                backup.yggdrasil?.let { ygd ->
                    Text(
                        text = stringResource(R.string.backup_yggdrasil_settings),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    val truncatedKey = if (ygd.privateKey.length > 20)
                        "${ygd.privateKey.take(8)}...${ygd.privateKey.takeLast(8)}"
                    else "●●●"
                    Text(
                        text = stringResource(R.string.backup_private_key_label, truncatedKey),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = stringResource(R.string.backup_peers_count, ygd.peers.size),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    ygd.peers.forEach { peer ->
                        Text(
                            text = "  - $peer",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = stringResource(R.string.backup_multicast_beacon, ygd.multicastBeacon),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = stringResource(R.string.backup_multicast_listen, ygd.multicastListen),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = stringResource(R.string.backup_group_password_enabled, ygd.groupPasswordEnabled),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (ygd.groupPassword.isNotBlank()) {
                        val maskedGroupPassword = if (ygd.groupPassword.length > 12) {
                            "${ygd.groupPassword.take(4)}...${ygd.groupPassword.takeLast(4)}"
                        } else {
                            "●●●●"
                        }
                        Text(
                            text = stringResource(R.string.backup_group_password_value, maskedGroupPassword),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = stringResource(R.string.backup_max_backoff, ygd.maxBackoff),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Proxy settings
                Text(
                    text = stringResource(R.string.proxy_settings),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.enabled_label, backup.proxy.enabled),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                if (backup.proxy.socksAddress.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.socks_label, backup.proxy.socksAddress),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (backup.proxy.dnsServer.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.dns_label, backup.proxy.dnsServer),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Expose mappings
                Text(
                    text = stringResource(R.string.expose_mappings),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.enabled_label, backup.expose.enabled),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = stringResource(R.string.mappings_count, backup.expose.mappings.size),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                backup.expose.mappings.forEach { mapping ->
                    Text(
                        text = "  - ${mapping.protocol} ${mapping.localPort} → ${mapping.yggPort}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Forward mappings
                Text(
                    text = stringResource(R.string.forward_mappings),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.enabled_label, backup.forward.enabled),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = stringResource(R.string.mappings_count, backup.forward.mappings.size),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                backup.forward.mappings.forEach { mapping ->
                    Text(
                        text = "  - ${mapping.protocol} ${mapping.remoteIp}:${mapping.remotePort} → ${mapping.localPort}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (backup.yggdrasil != null)
                        stringResource(R.string.import_note_full)
                    else
                        stringResource(R.string.import_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) {
                        Text(stringResource(R.string.import_button))
                    }
                }
            }
        }
    }
}

@Composable
fun PeerStatus(viewModel: DiagnosticsViewModel, isVisible: Boolean) {
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val totalPeerCount by viewModel.totalPeerCount.collectAsState()
    val peerDetails by viewModel.peerDetails.collectAsState()
    val yggdrasilIp by viewModel.yggdrasilIp.collectAsState()
    val yggdrasilPublicKey by viewModel.yggdrasilPublicKey.collectAsState()
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val savedScrollPosition by viewModel.peerStatusScrollPosition.collectAsState()
    val scrollState = rememberScrollState(initial = savedScrollPosition)

    // Only collect peer details when this tab is visible and service is running
    LaunchedEffect(isVisible, isServiceRunning) {
        if (isVisible && isServiceRunning) {
            viewModel.collectPeerDetails()
        }
    }

    // Save scroll position when it changes
    LaunchedEffect(scrollState.value) {
        viewModel.savePeerStatusScrollPosition(scrollState.value)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Yggdrasil IP and Public Key Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = yggdrasilIp ?: context.getString(R.string.not_connected),
                    onValueChange = { },
                    label = { Text(stringResource(R.string.yggdrasil_ip_section)) },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    singleLine = true,
                    isError = yggdrasilIp == null && isServiceRunning,
                    trailingIcon = {
                        if (yggdrasilIp != null) {
                            IconButton(onClick = {
                                val clip = ClipData.newPlainText("Yggdrasil IP", yggdrasilIp)
                                clipboardManager.setPrimaryClip(clip)
                                // System shows toast automatically on Android 13+
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy IP")
                            }
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = yggdrasilPublicKey ?: context.getString(R.string.not_connected),
                    onValueChange = { },
                    label = { Text(stringResource(R.string.public_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    singleLine = true,
                    isError = yggdrasilPublicKey == null && isServiceRunning,
                    trailingIcon = {
                        if (yggdrasilPublicKey != null) {
                            IconButton(onClick = {
                                val clip = ClipData.newPlainText("Yggdrasil Public Key", yggdrasilPublicKey)
                                clipboardManager.setPrimaryClip(clip)
                                // System shows toast automatically on Android 13+
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Public Key")
                            }
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (!isServiceRunning) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.start_service_view_peers),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.connected_peers),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (totalPeerCount > 0) "$peerCount/$totalPeerCount" else "0",
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (peerCount > 0) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }
                        
                        Icon(
                            imageVector = if (peerCount > 0) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = if (peerCount > 0) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }

                    // Spacer(modifier = Modifier.height(8.dp))

                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        
        // Display each peer's details as separate cards
        if (peerCount > 0) {
            peerDetails.forEach { peer ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (peer.up) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (peer.inbound) stringResource(R.string.inbound) else stringResource(R.string.outbound),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (peer.up) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = if (peer.up) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = peer.uri,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.uptime),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatUptime(peer.uptime),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.latency),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (peer.latency > 0) "${peer.latency} ms" else "-",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.cost),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${peer.cost}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.rx_label, formatBytes(peer.rxBytes)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.tx_label, formatBytes(peer.txBytes)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatUptime(seconds: Double): String {
    val sec = seconds.toInt()
    val hours = sec / 3600
    val minutes = (sec % 3600) / 60
    val secs = sec % 60
    return when {
        hours > 0 -> String.format("%dh %dm", hours, minutes)
        minutes > 0 -> String.format("%dm %ds", minutes, secs)
        else -> String.format("%ds", secs)
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsViewer(viewModel: DiagnosticsViewModel) {
    val logs by viewModel.logs.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var userScrolled by remember { mutableStateOf(false) }

    // Initial scroll to bottom when screen opens
    LaunchedEffect(Unit) {
        if (logs.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    // Track if user manually scrolled up
    LaunchedEffect(scrollState.value) {
        if (scrollState.value < scrollState.maxValue - 100) {
            userScrolled = true
        } else if (scrollState.value >= scrollState.maxValue - 50) {
            userScrolled = false
        }
    }

    // Auto-scroll to bottom when new logs arrive, unless user scrolled up
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && !userScrolled) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.service_logs),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.log_entries, logs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (logs.isNotEmpty()) {
                    Row {
                        IconButton(onClick = {
                            // Download logs as file
                            viewModel.downloadLogs(context)
                        }) {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = "Download logs"
                            )
                        }
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear logs"
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_logs_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                } else {
                    Column {
                        logs.forEach { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Green,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        if (logs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.logs_collected_realtime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

