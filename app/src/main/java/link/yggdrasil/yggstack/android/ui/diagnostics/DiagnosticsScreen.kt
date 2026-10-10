package link.yggdrasil.yggstack.android.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.BackupConfig
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.PeerDetail
import link.yggdrasil.yggstack.android.data.PingSessionState
import link.yggdrasil.yggstack.android.data.PortStatsDetail
import link.yggdrasil.yggstack.android.data.PacGenerator
import link.yggdrasil.yggstack.android.data.YggstackConfig
import link.yggdrasil.yggstack.android.ui.configuration.IpSuggestion
import link.yggdrasil.yggstack.android.ui.configuration.LocalIpTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { ConfigRepository(context) }
    val viewModel: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModel.Factory(repository, context)
    )

    val tabs = listOf(
        stringResource(R.string.tab_config),
        stringResource(R.string.tab_peers),
        stringResource(R.string.tab_ports),
        stringResource(R.string.tab_ping),
        stringResource(R.string.tab_logs)
    )

    // Load saved tab before creating pager
    var initialTab by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        repository.migrateDiagnosticsTabIfNeeded()
        initialTab = repository.diagnosticsTabFlow.first().coerceIn(0, 4)
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
            // Scrollable so a long tab title never wraps ("Confi/g"): each
            // tab sizes to its single-line text and the row scrolls when the
            // titles overflow the width.
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 16.dp
            ) {
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
                    2 -> PortsViewer(
                        viewModel = viewModel,
                        isVisible = pagerState.currentPage == 2
                    )
                    3 -> PingViewer(
                        viewModel = viewModel,
                        isVisible = pagerState.currentPage == 3
                    )
                    4 -> LogsViewer(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigViewer(viewModel: DiagnosticsViewModel) {
    val currentConfig by viewModel.currentConfig.collectAsStateWithLifecycle()
    val yggstackConfig by viewModel.yggstackConfig.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
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
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
        // GroupPassword notice — a group password limits direct reachability
        // to peers sharing it (see GroupPasswordInfoCard).
        if (groupPasswordActive(yggstackConfig)) {
            GroupPasswordInfoCard(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
        }
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

        Spacer(modifier = Modifier.height(8.dp))

        // Backup Configuration Card (pinned to the bottom; scrolls never move it)
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
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Backup (export) button – opens export dialog.
                    // Icons: "output" for backup, "input" for restore.
                    IconButton(
                        onClick = { showExportDialog = true },
                        enabled = yggstackConfig != null
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_output),
                            contentDescription = "Backup configuration",
                            tint = if (yggstackConfig != null) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                    // Restore (import) button
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_input),
                            contentDescription = "Restore configuration",
                            tint = MaterialTheme.colorScheme.primary
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

    // AlertDialog pins the confirm/dismiss buttons outside the scrollable
    // text area, so they stay visible on short screens (fixed-height preview
    // inside a plain Dialog Column used to push them off-screen there).
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.backup_export_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    // Scrolling lives on the text itself: the card is capped
                    // at 320dp, so the outer dialog scroll can never reveal
                    // a longer TOML — only this inner scroll can.
                    Text(
                        text = backupToml,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.backup_export_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
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
                    if (ygd.listenEntries.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.backup_listen_entries,
                                ygd.listenEntries.joinToString(", ") { it.toUri() }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
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
                if (backup.proxy.httpAddress.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.http_label, backup.proxy.httpAddress),
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
                if (backup.proxy.dnsServer2.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.dns_label, backup.proxy.dnsServer2),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (backup.proxy.pacEnabled) {
                    Text(
                        text = stringResource(
                            R.string.pac_label,
                            PacGenerator.pacUrl(backup.proxy.pacIp, backup.proxy.pacPort),
                            if (backup.proxy.pacAllTraffic) "all traffic" else "ygg only"
                        ),
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
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val isPowerSaveIdle by viewModel.isPowerSaveIdle.collectAsStateWithLifecycle()
    val peerCount by viewModel.peerCount.collectAsStateWithLifecycle()
    val totalPeerCount by viewModel.totalPeerCount.collectAsStateWithLifecycle()
    val peerDetails by viewModel.peerDetails.collectAsStateWithLifecycle()
    val yggdrasilIp by viewModel.yggdrasilIp.collectAsStateWithLifecycle()
    val yggdrasilPublicKey by viewModel.yggdrasilPublicKey.collectAsStateWithLifecycle()
    val serviceConnected by viewModel.serviceConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    // Read the saved position once for restoration instead of collecting it:
    // subscribing here would recompose the whole tab on every save
    val savedScrollPosition = viewModel.peerStatusScrollPosition.value
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition.first,
        initialFirstVisibleItemScrollOffset = savedScrollPosition.second
    )

    // Only collect peer details when this tab is visible, the service is
    // running and bound, and the host is at least STARTED. Including the
    // connection state re-fires the effect if the service binds after the tab
    // is already on screen; the STARTED gating stops the service's 1s poller
    // while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isVisible, isServiceRunning, serviceConnected, lifecycleOwner) {
        if (isVisible && isServiceRunning && serviceConnected) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.collectPeerDetails()
            }
        }
    }

    // Save the scroll position once per gesture — when scrolling settles — and
    // when the page leaves composition (the pager disposes off-screen pages),
    // instead of on every scrolled pixel
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    viewModel.savePeerStatusScrollPosition(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset
                    )
                }
            }
    }
    DisposableEffect(listState, viewModel) {
        onDispose {
            viewModel.savePeerStatusScrollPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        // Yggdrasil IP and Public Key Section
        item(key = "identity") {
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
        }

        if (!isServiceRunning) {
            item(key = "notRunning") {
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
                            imageVector = if (isPowerSaveIdle) Icons.Default.BatterySaver else Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                if (isPowerSaveIdle) R.string.peers_power_save_idle
                                else R.string.start_service_view_peers
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        // While the session lives on in Power Save idle, offer
                        // the same manual wake as the Ports screen
                        if (isPowerSaveIdle) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.wakeNow() }) {
                                Text(stringResource(R.string.power_save_wake_now))
                            }
                        }
                    }
                }
                }
            }
        } else {
            item(key = "summary") {
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
        }

        // Display each peer's details as separate cards
        if (peerCount > 0) {
            items(peerDetails, key = { "${it.inbound}|${it.uri}" }) { peer ->
                PeerCard(peer = peer)
            }
        }
    }
}

/**
 * One peer's stats card, extracted so the 1 Hz data updates recompose at most
 * the changed cards (PeerDetail is a stable data class) instead of the tab.
 */
@Composable
private fun PeerCard(peer: PeerDetail) {
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

/**
 * Ports stats page: per-listener connection counts and RX/TX for the SOCKS
 * proxy, exposed and forwarded ports. Sections whose config toggle is
 * disabled are hidden; stats reset when the service stops.
 */
@Composable
fun PortsViewer(viewModel: DiagnosticsViewModel, isVisible: Boolean) {
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val yggstackConfig by viewModel.yggstackConfig.collectAsStateWithLifecycle()
    val compactMode by viewModel.portsCompactMode.collectAsStateWithLifecycle()
    val isPowerSaveIdle by viewModel.isPowerSaveIdle.collectAsStateWithLifecycle()
    val idleCountdownSeconds by viewModel.idleCountdownSeconds.collectAsStateWithLifecycle()
    val powerSaveIdleSince by viewModel.powerSaveIdleSince.collectAsStateWithLifecycle()
    val isSessionActive by viewModel.isSessionActive.collectAsStateWithLifecycle()
    val powerSaveUpMillis by viewModel.powerSaveUpMillis.collectAsStateWithLifecycle()
    val powerSaveIdleMillis by viewModel.powerSaveIdleMillis.collectAsStateWithLifecycle()
    val powerSaveStateSince by viewModel.powerSaveStateSince.collectAsStateWithLifecycle()
    val serviceConnected by viewModel.serviceConnected.collectAsStateWithLifecycle()
    val portSections by viewModel.portSections.collectAsStateWithLifecycle()
    val activeTransitConnections by viewModel.activeTransitConnections.collectAsStateWithLifecycle()

    // Only collect port stats when this tab is visible, the service is bound
    // and the session is active. That covers both node states: running (the
    // service's 1s poller feeds live updates) and Power Save idle (the frozen
    // snapshot replays once so a freshly opened app still shows the cards),
    // and suspends collection while the app is backgrounded either way.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isVisible, isSessionActive, serviceConnected, lifecycleOwner) {
        if (isVisible && isSessionActive && serviceConnected) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.collectPortStats()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item(key = "viewMode") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ports_view_mode_label),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ports_view_compact),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (compactMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = !compactMode,
                            onCheckedChange = { viewModel.setPortsCompactMode(!it) },
                            modifier = Modifier.scale(0.8f)
                        )
                        Text(
                            text = stringResource(R.string.ports_view_extended),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!compactMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (yggstackConfig?.powerSaveEnabled == true && isSessionActive) {
            item(key = "powerSave") {
                PowerSaveStatusCard(
                    isRunning = isServiceRunning,
                    isIdle = isPowerSaveIdle,
                    countdownSeconds = idleCountdownSeconds,
                    idleSinceMs = powerSaveIdleSince,
                    activeConnections = activeTransitConnections,
                    upMillis = powerSaveUpMillis,
                    idleMillis = powerSaveIdleMillis,
                    stateSinceMs = powerSaveStateSince,
                    sleepOnPortsIdle = yggstackConfig?.powerSaveSleepOnPortsIdle ?: true,
                    wakeOnPortsActive = yggstackConfig?.powerSaveWakeOnPortsActive ?: true,
                    sleepDuringScreenOff = yggstackConfig?.powerSaveSleepDuringScreenOff ?: false,
                    onWakeNow = { viewModel.wakeNow() }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (!isSessionActive) {
            item(key = "stopped") {
                Card(modifier = Modifier.fillMaxWidth()) {
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
                                text = stringResource(R.string.ports_service_stopped),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            // Service session active: show port cards whether the node is
            // running or powered down in Power Save idle — during idle the
            // stats freeze at their last values until the node wakes or the
            // service is fully stopped. Sections arrive display-ready from
            // the ViewModel (visibility, ordering, names, rates).
            portSections.forEach { section ->
                item(key = "header_${section.section}", contentType = "sectionHeader") {
                    Text(
                        text = stringResource(section.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(section.rows, key = { it.stat.key }, contentType = { "portRow" }) { row ->
                    PortStatItem(
                        stat = row.stat,
                        displayName = row.displayName,
                        rxRatePerSec = row.rxRatePerSec,
                        txRatePerSec = row.txRatePerSec,
                        compact = compactMode
                    )
                }
            }

            if (portSections.isEmpty()) {
                item(key = "noListeners") {
                    Text(
                        text = stringResource(R.string.ports_no_listeners),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PortStatItem(
    stat: PortStatsDetail,
    displayName: String?,
    rxRatePerSec: Double?,
    txRatePerSec: Double?,
    compact: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (!displayName.isNullOrBlank()) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    if (!compact) {
                        Text(
                            text = if (stat.targetAddr.isBlank()) stat.listenAddr
                            else "${stat.listenAddr} → ${stat.targetAddr}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (stat.isTcp) "TCP" else "UDP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ports_active_label),
                    style = MaterialTheme.typography.bodySmall
                )
                // Highlight live activity; back to the default color when idle.
                Text(
                    text = "${stat.activeConnections}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stat.activeConnections > 0) MaterialTheme.colorScheme.primary else Color.Unspecified
                )
                Text(
                    text = stringResource(R.string.ports_total_label, stat.totalConnections),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = rxRatePerSec?.let {
                            stringResource(R.string.ports_rx_rate_label, formatBytes(stat.rxBytes), formatBytes(it.toLong()))
                        } ?: stringResource(R.string.rx_label, formatBytes(stat.rxBytes)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column {
                    Text(
                        text = txRatePerSec?.let {
                            stringResource(R.string.ports_tx_rate_label, formatBytes(stat.txBytes), formatBytes(it.toLong()))
                        } ?: stringResource(R.string.tx_label, formatBytes(stat.txBytes)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun PowerSaveStatusCard(
    isRunning: Boolean,
    isIdle: Boolean,
    countdownSeconds: Long?,
    idleSinceMs: Long?,
    activeConnections: Long,
    upMillis: Long,
    idleMillis: Long,
    stateSinceMs: Long,
    sleepOnPortsIdle: Boolean,
    wakeOnPortsActive: Boolean,
    sleepDuringScreenOff: Boolean,
    onWakeNow: () -> Unit
) {
    // Live-ticking clock so the idle status and the up/idle session counters
    // keep advancing while the card is on screen
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    // Session time split: accrued totals plus the segment currently in progress
    val liveUpMillis = upMillis +
        if (!isIdle && stateSinceMs > 0) (nowMs - stateSinceMs).coerceAtLeast(0) else 0L
    val liveIdleMillis = idleMillis +
        if (isIdle && stateSinceMs > 0) (nowMs - stateSinceMs).coerceAtLeast(0) else 0L
    val totalMillis = liveUpMillis + liveIdleMillis
    val upPercent = if (totalMillis > 0) ((liveUpMillis * 100) / totalMillis).toInt() else 0
    val idlePercent = if (totalMillis > 0) 100 - upPercent else 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.power_save_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (isIdle) {
                    TextButton(onClick = onWakeNow) {
                        Text(stringResource(R.string.power_save_wake_now))
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val statusText = when {
                isIdle -> {
                    val idleSeconds = idleSinceMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) } ?: 0L
                    // With "Wake on ports active" off (or suspended by screen-off
                    // sleep - and the screen is off then, so nobody sees the card)
                    // the idle ports are closed rather than listening for knocks.
                    if (wakeOnPortsActive) {
                        stringResource(R.string.power_save_idle_status, formatUptime(idleSeconds.toDouble()))
                    } else {
                        stringResource(R.string.power_save_idle_status_closed, formatUptime(idleSeconds.toDouble()))
                    }
                }
                activeConnections > 0 -> stringResource(R.string.power_save_active_ports_status, activeConnections)
                countdownSeconds != null -> stringResource(
                    R.string.power_save_countdown_status,
                    formatCountdown(countdownSeconds)
                )
                isRunning -> when {
                    // Countdown only exists with "Sleep on ports idle"; the armed
                    // text must match whichever sleep trigger is actually active.
                    sleepOnPortsIdle -> stringResource(R.string.power_save_armed_status)
                    sleepDuringScreenOff -> stringResource(R.string.power_save_armed_screen_status)
                    else -> stringResource(R.string.power_save_enabled_status)
                }
                else -> stringResource(R.string.power_save_countdown_status, formatCountdown(0))
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.power_save_up_for, formatDurationHMS(liveUpMillis / 1000), upPercent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.power_save_idle_for, formatDurationHMS(liveIdleMillis / 1000), idlePercent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatCountdown(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
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

/**
 * Duration with seconds always shown (e.g. "1h 3m 40s", "5m 30s", "45s") —
 * used for the Power Save card's Up/Idle session counters.
 */
fun formatDurationHMS(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format("%dh %dm %ds", hours, minutes, seconds)
        minutes > 0 -> String.format("%dm %ds", minutes, seconds)
        else -> String.format("%ds", seconds)
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
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val yggstackConfig by viewModel.yggstackConfig.collectAsStateWithLifecycle()
    val logsEnabled by viewModel.logsEnabled.collectAsStateWithLifecycle()
    val appliedLogging by viewModel.appliedLogging.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var userScrolled by remember { mutableStateOf(false) }
    var levelMenuExpanded by remember { mutableStateOf(false) }

    // The current selection: "disabled" when logging is off, else the level
    // from the config.
    val selectedLevel = if (!logsEnabled) "disabled" else yggstackConfig?.logLevel ?: "error"
    val levelLabels = mapOf(
        "disabled" to stringResource(R.string.log_level_disabled),
        "error" to stringResource(R.string.log_level_error),
        "warn" to stringResource(R.string.log_level_warn),
        "info" to stringResource(R.string.log_level_info),
        "debug" to stringResource(R.string.log_level_debug)
    )
    // Logging applies at node start: a selection differing from what the
    // node started with, while it runs, needs the restart button.
    val selectedPlainLevel = yggstackConfig?.logLevel ?: "error"
    val needsRestart = isServiceRunning && appliedLogging != null &&
        (appliedLogging!!.first != selectedPlainLevel || appliedLogging!!.second != logsEnabled)

    // Initial scroll to bottom when screen opens
    LaunchedEffect(Unit) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    // Track if the user manually scrolled away from the bottom, evaluated
    // only when a scroll gesture settles instead of on every scrolled pixel
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    userScrolled = lastVisible < info.totalItemsCount - 2
                }
            }
    }

    // Auto-scroll to bottom when new logs arrive, unless user scrolled up
    LaunchedEffect(logs) {
        if (logs.isNotEmpty() && !userScrolled) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // The terminal log fills the tab; the Service logs card moved below
        // it so the controls sit at the bottom of the screen.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            )
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
                // Lazy so a long log buffer composes only the visible lines
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    items(logs.size) { index ->
                        Text(
                            text = logs[index],
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Green,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

            // While scrolled back through history: jump to the end and let
            // the log follow its tail again. Mirror of Peer Discovery's
            // scroll-to-top FAB, pointing down in secondary colors.
            if (userScrolled && logs.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        userScrolled = false
                        scope.launch {
                            listState.animateScrollToItem(logs.lastIndex)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.logs_scroll_end)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Service logs card, pinned to the bottom: entry count, download and
        // clear, the log-level dropdown (always usable) and — when the
        // selection changed while the service runs — the restart button.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.log_level_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { levelMenuExpanded = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = levelLabels[selectedLevel] ?: selectedLevel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = levelMenuExpanded,
                            onDismissRequest = { levelMenuExpanded = false }
                        ) {
                            listOf("disabled", "error", "warn", "info", "debug").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(levelLabels[option] ?: option) },
                                    onClick = {
                                        viewModel.setLogSelection(option)
                                        levelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (needsRestart) {
                    Button(
                        onClick = { viewModel.restartService() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text(stringResource(R.string.restart_service))
                    }
                }
            }
        }
    }
}

/**
 * True for a literal IPv6 address inside 200::/7 (the Yggdrasil range).
 * Only hex-and-colon strings are attempted, so a hostname can never reach
 * DNS from here; malformed literals fail as local UnknownHostExceptions.
 */
fun isValidYggAddress(value: String): Boolean {
    val host = value.trim().removePrefix("[").removeSuffix("]")
    if (!host.contains(':')) return false
    if (!host.all { it.isDigit() || it in "abcdefABCDEF:" }) return false
    return try {
        val bytes = java.net.InetAddress.getByName(host).address
        bytes.size == 16 && (bytes[0].toInt() and 0xFF) in 0x02..0x03
    } catch (_: Exception) {
        false
    }
}

/** Packet-count choices for the Ping tab; 0 means "until stopped". */
private val PingCountOptions = listOf(1, 3, 4, 5, 10, 0)

/** One address offered by the ping target picker. */
private data class PingTargetSuggestion(val address: String, val label: String?)

/** A titled, iconized section of the ping target picker's list. */
private data class PingTargetSection(
    val titleRes: Int,
    val icon: ImageVector,
    val entries: List<PingTargetSuggestion>
)

/**
 * All pingable Yggdrasil addresses worth offering, deduplicated across
 * sources: connected peers' addresses, the proxy DNS servers (only when
 * inside 200::/7), and the forward port maps' remote addresses.
 */
private fun buildPingTargetSections(
    peers: List<PeerDetail>,
    config: YggstackConfig?
): List<PingTargetSection> {
    val seen = HashSet<String>()

    val peerEntries = mutableListOf<PingTargetSuggestion>()
    for (peer in peers) {
        if (!peer.up) continue
        val address = peer.address ?: continue
        if (seen.add(address)) {
            peerEntries += PingTargetSuggestion(
                address,
                peer.uri.substringBefore('?').substringAfter("://")
            )
        }
    }

    val dnsEntries = mutableListOf<PingTargetSuggestion>()
    if (config != null) {
        for (server in listOf(config.dnsServer, config.dnsServer2)) {
            if (server.isBlank()) continue
            val host = dnsHostOf(server)
            if (!isValidYggAddress(host)) continue
            if (seen.add(host)) dnsEntries += PingTargetSuggestion(host, "DNS")
        }
    }

    val mapEntries = mutableListOf<PingTargetSuggestion>()
    config?.forwardMappings?.forEach { mapping ->
        val host = mapping.remoteIp.trim().removePrefix("[").removeSuffix("]")
        if (!isValidYggAddress(host)) return@forEach
        if (seen.add(host)) {
            mapEntries += PingTargetSuggestion(
                host,
                mapping.shortName.ifBlank { "fwd :${mapping.remotePort}" }
            )
        }
    }

    return buildList {
        if (peerEntries.isNotEmpty()) add(PingTargetSection(R.string.tab_peers, Icons.Default.Hub, peerEntries))
        if (dnsEntries.isNotEmpty()) add(PingTargetSection(R.string.ping_picker_dns, Icons.Default.Dns, dnsEntries))
        if (mapEntries.isNotEmpty()) add(PingTargetSection(R.string.ping_picker_maps, Icons.Default.Lan, mapEntries))
    }
}

/** Host part of a DNS server setting — "[host]:port" (the normalized form),
 *  bare IPv6, or host:port. */
private fun dnsHostOf(server: String): String {
    val s = server.trim()
    if (s.startsWith("[")) return s.removePrefix("[").substringBefore("]")
    val lastColon = s.lastIndexOf(':')
    val tail = if (lastColon > 0) s.substring(lastColon + 1) else ""
    if (tail.isNotEmpty() && tail.all { it.isDigit() }) return s.substring(0, lastColon)
    return s
}

/**
 * Chooser for the ping target: every address from [buildPingTargetSections]
 * under its section header. Tapping an entry inserts it and returns. A
 * regular themed dialog — standard width, rounded surface, ~75% of the
 * screen height.
 */
@Composable
private fun PingTargetPickerScreen(
    sections: List<PingTargetSection>,
    currentTarget: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 12.dp)
            ) {
                // Header: title + close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ping_target_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
                if (sections.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.ping_picker_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        sections.forEach { section ->
                            item(key = "header_${section.titleRes}") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = section.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${stringResource(section.titleRes)} (${section.entries.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            items(section.entries.size, key = { "s_${section.titleRes}_$it" }) { index ->
                                val entry = section.entries[index]
                                val isCurrent = entry.address == currentTarget
                                Card(
                                    onClick = { onPick(entry.address) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.address,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            entry.label?.let { label ->
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (isCurrent) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingViewer(viewModel: DiagnosticsViewModel, isVisible: Boolean) {
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val isPowerSaveIdle by viewModel.isPowerSaveIdle.collectAsStateWithLifecycle()
    val yggstackConfig by viewModel.yggstackConfig.collectAsStateWithLifecycle()
    val isPowerSaveIdleCapable = yggstackConfig?.powerSaveEnabled == true
    val pingSession by viewModel.pingSession.collectAsStateWithLifecycle()
    val savedTarget by viewModel.pingTarget.collectAsStateWithLifecycle()
    val peerDetails by viewModel.peerDetails.collectAsStateWithLifecycle()
    val serviceConnected by viewModel.serviceConnected.collectAsStateWithLifecycle()

    var target by remember { mutableStateOf("") }
    val savedCount by viewModel.pingCount.collectAsStateWithLifecycle()
    // Local override of the restored count, so the picker is instant while
    // the DataStore write lands.
    var pickedCount by remember { mutableStateOf<Int?>(null) }
    val count = pickedCount ?: savedCount
    var countExpanded by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Feed the target suggestions: collect peer details only while this tab
    // is visible and the service is running and bound (same pattern as the
    // Peers tab — the SharedFlow subscription also drives the poller).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isVisible, isServiceRunning, serviceConnected, lifecycleOwner) {
        if (isVisible && isServiceRunning && serviceConnected) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.collectPeerDetails()
            }
        }
    }

    // Prefill the field once the persisted target arrives (DataStore is async)
    LaunchedEffect(savedTarget) {
        if (target.isBlank() && savedTarget.isNotBlank()) target = savedTarget
    }

    val session = pingSession
    val probes = session?.probes.orEmpty()
    val sessionRunning = session?.running == true
    val targetValid = isValidYggAddress(target)
    // Ping needs a running node; a Power Save idle node is woken on start.
    val nodeAvailable = isServiceRunning || (isPowerSaveIdleCapable && isPowerSaveIdle)
    val context = LocalContext.current

    // Everything the target picker can offer: peers' addresses, proxy DNS
    // servers in 200::/7, forward maps' remote addresses.
    val targetSections = remember(peerDetails, yggstackConfig) {
        buildPingTargetSections(peerDetails, yggstackConfig)
    }

    // No outer horizontal padding: the address field is full-bleed, the log
    // and the toolbar carry their own side insets. IME handling is global:
    // the navigation bar lifts above the keyboard, which grows the Scaffold's
    // content padding here. The screen stays visible with the service
    // stopped — the input and the start button are disabled instead.
    Column(modifier = Modifier.fillMaxSize()) {
        if (groupPasswordActive(yggstackConfig)) {
            GroupPasswordInfoCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            )
        }
        // Terminal-style results, mirroring the Logs viewer, filling all the
        // space above the controls card. Tapping it
            // explicitly clears focus from the address field — closing the
            // keyboard — so the next tap on the field re-opens the
            // suggestions. Covers the empty state too.
            val logInteraction = remember { MutableInteractionSource() }
            val focusManager = LocalFocusManager.current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp, end = 16.dp,
                        top = if (groupPasswordActive(yggstackConfig)) 8.dp else 16.dp
                    )
                    .weight(1f)
                    .clickable(
                        interactionSource = logInteraction,
                        indication = null
                    ) { focusManager.clearFocus() },
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                if (probes.isEmpty() && session?.error == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ping yggdrasil network hosts",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Gray
                        )
                    }
                } else {
                    // Auto-scroll to the latest probe unless the user scrolled up
                    LaunchedEffect(probes.size) {
                        if (probes.isNotEmpty()) listState.animateScrollToItem(probes.lastIndex)
                    }
                    // Selectable: any part of the log can be marked and copied
                    // with the native text-selection menu.
                    SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        items(probes.size) { index ->
                            val probe = probes[index]
                            val line = if (probe.rttMs != null) {
                                "reply from ${session?.target}: seq=${probe.seq} time=%.2f ms".format(probe.rttMs)
                            } else {
                                "request timeout for seq ${probe.seq}"
                            }
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (probe.rttMs != null) Color.Green else Color(0xFFCC8888),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                        session?.error?.let { error ->
                            item(key = "error") {
                                Text(
                                    text = stringResource(R.string.ping_error_line, error),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Red,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                        session?.doneReason?.let { reason ->
                            item(key = "done") {
                                Text(
                                    text = stringResource(
                                        when (reason) {
                                            "stopped" -> R.string.ping_done_stopped
                                            "error" -> R.string.ping_done_error
                                            else -> R.string.ping_done_completed
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                        // Session statistics close the log, ping(1)-style:
                        // sent/received/loss, then min/avg/max RTT.
                        if (probes.isNotEmpty()) {
                            item(key = "stats") {
                                val received = probes.count { it.rttMs != null }
                                val lossPct = (probes.size - received) * 100.0 / probes.size
                                val rtts = probes.mapNotNull { it.rttMs }
                                Column {
                                    Text(
                                        text = stringResource(
                                            R.string.ping_summary,
                                            probes.size, received, "%.0f%%".format(lossPct)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                    if (rtts.isNotEmpty()) {
                                        Text(
                                            text = stringResource(
                                                R.string.ping_rtt_summary,
                                                "%.1f".format(rtts.min()),
                                                "%.1f".format(rtts.average()),
                                                "%.1f".format(rtts.max())
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                    } // SelectionContainer
                }
            }

        // Controls, permanently visible under the log: clear, copy, the
        // packet-count picker ("Count N") and the Ping/Stop word button —
        // right aligned in that order. Copy puts the whole log — probes,
        // error, done line, statistics — on the clipboard.
        @Composable
        fun countValue(n: Int): String =
            if (n == 0) stringResource(R.string.ping_count_value, stringResource(R.string.ping_infinite))
            else stringResource(R.string.ping_count_value, n.toString())

        val canToggle = sessionRunning || (targetValid && nodeAvailable)
        // One card pinned to the bottom of the screen holds the controls row
        // and the address field together; the log takes the rest.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.ping_clear),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (sessionRunning) 0.35f else 1f
                ),
                modifier = Modifier
                    .clickable(enabled = !sessionRunning) { viewModel.clearPing() }
                    .padding(8.dp)
                    .size(20.dp)
            )
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.ping_copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (session == null) 0.35f else 1f
                ),
                modifier = Modifier
                    .clickable(enabled = session != null) {
                        session?.let { s ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("ping log", buildPingLogText(context, s))
                            )
                        }
                    }
                    .padding(8.dp)
                    .size(20.dp)
            )
            val pickerColor = if (sessionRunning) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(enabled = !sessionRunning) { countExpanded = true }
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = countValue(count),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = pickerColor
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.ping_count_label),
                        tint = pickerColor
                    )
                }
                DropdownMenu(
                    expanded = countExpanded,
                    onDismissRequest = { countExpanded = false }
                ) {
                    PingCountOptions.forEach { option ->
                        DropdownMenuItem(
                            // Bare value here — the "Count" word belongs to
                            // the picker label, not every menu row.
                            text = {
                                Text(
                                    if (option == 0) stringResource(R.string.ping_infinite)
                                    else option.toString()
                                )
                            },
                            onClick = {
                                pickedCount = option
                                viewModel.setPingCount(option)
                                countExpanded = false
                            }
                        )
                    }
                }
            }
            Button(
                onClick = {
                    if (sessionRunning) viewModel.stopPing()
                    else viewModel.startPing(target.trim(), count)
                },
                enabled = canToggle,
                colors = if (sessionRunning) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) else ButtonDefaults.buttonColors(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .height(36.dp)
            ) {
                Text(
                    text = stringResource(
                        if (sessionRunning) R.string.ping_button_stop
                        else R.string.ping_button_start
                    ),
                    fontWeight = FontWeight.Bold
                )
            }
        }

            Spacer(modifier = Modifier.height(4.dp))

            // Address field, sharing the card with the controls above.
            // Enabled whenever no session runs — typing a target before the
            // service starts is fine (a disabled field also kills the
            // trailing browse button's taps).
            LocalIpTextField(
            value = target,
            onValueChange = { if (!sessionRunning) target = it },
            label = { Text(stringResource(R.string.ping_target_label)) },
            placeholder = { Text(stringResource(R.string.ping_target_hint)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !sessionRunning,
            singleLine = true,
            isError = target.isNotBlank() && !targetValid,
            supportingText = {
                val hintRes = when {
                    target.isNotBlank() && !targetValid -> R.string.ping_invalid_address
                    isPowerSaveIdle && !sessionRunning -> R.string.ping_waking
                    !nodeAvailable -> R.string.ping_start_service
                    else -> null
                }
                if (hintRes != null) Text(stringResource(hintRes))
            },
            suggestionsProvider = { emptyList() }, // targets come from the picker
            trailingIcon = {
                IconButton(
                    onClick = { showTargetPicker = true },
                    enabled = nodeAvailable
                ) {
                    Icon(
                        imageVector = Icons.Default.ManageSearch,
                        contentDescription = stringResource(R.string.ping_picker_open),
                        tint = when {
                            !nodeAvailable -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            sessionRunning -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )
            }
        }
    }

    if (showTargetPicker) {
        PingTargetPickerScreen(
            sections = targetSections,
            currentTarget = target.trim(),
            onPick = { address ->
                showTargetPicker = false
                if (!sessionRunning) target = address
            },
            onDismiss = { showTargetPicker = false }
        )
    }
}

/** True when a group password is configured and enabled in the config. */
private fun groupPasswordActive(config: YggstackConfig?): Boolean =
    config?.groupPasswordEnabled == true && !config.groupPassword.isNullOrBlank()

/**
 * The full ping log as plain text — probe lines, error and done lines,
 * statistics — as shown in the terminal card, for the copy button.
 */
private fun buildPingLogText(context: Context, session: PingSessionState): String {
    val lines = session.probes.map { probe ->
        if (probe.rttMs != null) {
            "reply from ${session.target}: seq=${probe.seq} time=%.2f ms".format(probe.rttMs)
        } else {
            "request timeout for seq ${probe.seq}"
        }
    }.toMutableList()
    session.error?.let { lines += context.getString(R.string.ping_error_line, it) }
    session.doneReason?.let { reason ->
        lines += context.getString(
            when (reason) {
                "stopped" -> R.string.ping_done_stopped
                "error" -> R.string.ping_done_error
                else -> R.string.ping_done_completed
            }
        )
    }
    if (session.probes.isNotEmpty()) {
        val received = session.probes.count { it.rttMs != null }
        val lossPct = (session.probes.size - received) * 100.0 / session.probes.size
        val rtts = session.probes.mapNotNull { it.rttMs }
        lines += context.getString(
            R.string.ping_summary, session.probes.size, received, "%.0f%%".format(lossPct)
        )
        if (rtts.isNotEmpty()) {
            lines += context.getString(
                R.string.ping_rtt_summary,
                "%.1f".format(rtts.min()),
                "%.1f".format(rtts.average()),
                "%.1f".format(rtts.max())
            )
        }
    }
    return lines.joinToString("\n")
}

/**
 * Notice shown when a group password is configured: it restricts direct
 * reachability to peers sharing the same password, so pings and other
 * requests to everyone else won't be answered.
 */
@Composable
private fun GroupPasswordInfoCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.group_password_info_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.group_password_info_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

