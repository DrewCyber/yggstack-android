package link.yggdrasil.yggstack.android.ui.diagnostics

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.ConfigRepository

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
                    1 -> PeerStatus(viewModel)
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
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val clipboardManager = LocalClipboardManager.current

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Current Configuration",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isServiceRunning) "Service Running" else "Service Stopped",
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
                            clipboardManager.setText(AnnotatedString(currentConfig))
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
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

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
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
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No configuration available",
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
fun PeerStatus(viewModel: DiagnosticsViewModel) {
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val totalPeerCount by viewModel.totalPeerCount.collectAsState()
    val peerDetails by viewModel.peerDetails.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                        text = "Peer Connection Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isServiceRunning) "Monitoring active" else "Start service to monitor",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isServiceRunning) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Start the service to view peer status",
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
                    Text(
                        text = "Peer Statistics",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Divider()

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Connected Peers",
                                style = MaterialTheme.typography.bodyMedium,
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

                    Spacer(modifier = Modifier.height(16.dp))

                    if (peerCount == 0) {
                        Text(
                            text = "No peers connected. Add peers in the Configuration tab to connect to the Yggdrasil network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "You are connected to $peerCount peer${if (peerCount != 1) "s" else ""} on the Yggdrasil network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
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
                                text = if (peer.inbound) "Inbound" else "Outbound",
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
                                    text = "Uptime",
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
                                    text = "Latency",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (peer.latency > 0) "${peer.latency} ms" else "-",
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
                                    text = "RX: ${formatBytes(peer.rxBytes)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column {
                                Text(
                                    text = "TX: ${formatBytes(peer.txBytes)}",
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
                        text = "Service Logs",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${logs.size} log entries",
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

        Spacer(modifier = Modifier.height(16.dp))

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
                            text = "No logs yet. Start the service to see logs.",
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
                text = "Logs are collected in real-time from the service",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

