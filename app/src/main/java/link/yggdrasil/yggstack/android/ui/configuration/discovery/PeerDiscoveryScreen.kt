package link.yggdrasil.yggstack.android.ui.configuration.discovery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.PublicPeerInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDiscoveryScreen(
    viewModel: PeerDiscoveryViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val externalIp by viewModel.externalIp.collectAsState()
    val displayPeers by viewModel.getDisplayPeers().collectAsState()
    val selectedPeers by viewModel.selectedPeers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isCancellable by viewModel.isCancellable.collectAsState()
    val loadingMessage by viewModel.loadingMessage.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // Handle back button
    BackHandler {
        onDismiss()
    }
    
    // Show Toast for download errors
    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            if (error.contains("Peers list download failed")) {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }
    
    // Refresh external IP on screen open
    LaunchedEffect(Unit) {
        viewModel.refreshExternalIp()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.peer_discovery))
                        externalIp?.let { ip ->
                            Text(
                                text = stringResource(R.string.external_ip) + ": $ip",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.fetchPeers() },
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.get_peers))
                }
                
                if (isCancellable) {
                    Button(
                        onClick = { viewModel.cancelSort() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    Button(
                        onClick = { viewModel.sortByRTT() },
                        enabled = !isLoading && displayPeers.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.rtt_check))
                    }
                }
            }



            // Loading indicator
            if (isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (progress > 0f) {
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = loadingMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Error message
            errorMessage?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Peers list
            if (displayPeers.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No peers available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Click 'Get Peers' to download",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    val listState = rememberLazyListState()
                    val showScrollToTop by remember {
                        derivedStateOf {
                            listState.firstVisibleItemIndex > 3
                        }
                    }
                    
                    // Separate selected and main lists
                    val selectedPeersList = displayPeers.filter { it.uri in selectedPeers }
                    val hasSelectedPeers = selectedPeersList.isNotEmpty()
                    
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Selected peers duplicates at top
                        if (hasSelectedPeers) {
                            items(selectedPeersList, key = { "selected_${it.uri}" }) { peer ->
                                PeerCard(
                                    peer = peer,
                                    isSelected = true,
                                    onToggleSelect = {
                                        scope.launch {
                                            viewModel.togglePeerSelection(peer.uri)
                                        }
                                    }
                                )
                            }
                            
                            // Divider
                            item(key = "divider") {
                                Divider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                        
                        // Main sorted list (all peers with checkboxes)
                        items(displayPeers, key = { "main_${it.uri}" }) { peer ->
                            PeerCard(
                                peer = peer,
                                isSelected = peer.uri in selectedPeers,
                                onToggleSelect = {
                                    scope.launch {
                                        viewModel.togglePeerSelection(peer.uri)
                                    }
                                }
                            )
                        }
                    }
                    
                    // Scroll to top FAB
                    if (showScrollToTop) {
                        FloatingActionButton(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Scroll to top"
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerCard(
    peer: PublicPeerInfo,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { /* handled by checkbox */ },
                    onLongClick = {
                        // Copy full URI to clipboard
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Peer URI", peer.uri)
                        clipboard.setPrimaryClip(clip)
                    }
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // URI (protocol://ip:port)
                Text(
                    text = peer.uri.split("?")[0], // Remove query parameters for display
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
                
                Spacer(Modifier.height(4.dp))
                
                // Country and RTT
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = peer.country,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    peer.rtt?.let { rtt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = when {
                                    rtt < 50 -> MaterialTheme.colorScheme.primary
                                    rtt < 150 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = "$rtt ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } ?: run {
                        if (peer.lastChecked != null) {
                            Text(
                                text = "–",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // Checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() }
            )
        }
    }
}
