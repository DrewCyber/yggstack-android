package link.yggdrasil.yggstack.android.ui.peers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import link.yggdrasil.yggstack.android.data.peer.PeerProtocol

/**
 * Screen for discovering and managing public peers
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDiscoveryScreen(
    viewModel: PeerDiscoveryViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    android.util.Log.d("PeerDiscoveryScreen", "PeerDiscoveryScreen composing, viewModel=$viewModel")
    val peers by viewModel.peers.collectAsState()
    val externalIp by viewModel.externalIp.collectAsState()
    val sortingInProgress by viewModel.sortingInProgress.collectAsState()
    val sortingProgress by viewModel.sortingProgress.collectAsState()
    val selectedProtocols by viewModel.selectedProtocols.collectAsState()
    val selectedPeerUris by viewModel.selectedPeerUris.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    // Log peers state in UI
    LaunchedEffect(peers.size, isLoading) {
        android.util.Log.d("PeerDiscoveryScreen", "UI recomposed: peers.size=${peers.size}, isLoading=$isLoading, peers object=${peers.hashCode()}")
    }
    
    var showAddPeerDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Public Peers")
                        externalIp?.let {
                            Text(
                                "External IP: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddPeerDialog = true }) {
                        Icon(Icons.Default.Add, "Add peer")
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
            // Error message
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, "Dismiss")
                        }
                    }
                }
            }
            
            // Management buttons
            PeerManagementButtons(
                onClearCache = { viewModel.clearCache() },
                onDownloadList = { viewModel.downloadPeerList() },
                onSortByPing = { viewModel.sortByPing() },
                onSortByConnect = { viewModel.sortByConnect() },
                isLoading = isLoading,
                isSorting = sortingInProgress
            )
            
            // Protocol filters
            ProtocolFilterRow(
                selectedProtocols = selectedProtocols,
                onProtocolToggle = { viewModel.toggleProtocolFilter(it) }
            )
            
            // Sorting progress
            if (sortingInProgress) {
                LinearProgressIndicator(
                    progress = sortingProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Peer list
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (peers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No peers available. Download peer list first.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(peers, key = { it.uri }) { peer ->
                        PeerListItem(
                            peer = peer,
                            isSelected = selectedPeerUris.contains(peer.uri),
                            onToggleSelection = { viewModel.togglePeerSelection(peer.uri) }
                        )
                    }
                }
            }
        }
    }
    
    // Add peer dialog
    if (showAddPeerDialog) {
        AddPeerDialog(
            onDismiss = { showAddPeerDialog = false },
            onAddPeer = { uri ->
                viewModel.addManualPeer(uri)
                showAddPeerDialog = false
            }
        )
    }
}

@Composable
fun PeerManagementButtons(
    onClearCache: () -> Unit,
    onDownloadList: () -> Unit,
    onSortByPing: () -> Unit,
    onSortByConnect: () -> Unit,
    isLoading: Boolean,
    isSorting: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDownloadList,
                    enabled = !isLoading && !isSorting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
                
                OutlinedButton(
                    onClick = onClearCache,
                    enabled = !isLoading && !isSorting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear Cache")
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onSortByPing,
                    enabled = !isLoading && !isSorting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Speed, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sort: Ping")
                }
                
                FilledTonalButton(
                    onClick = onSortByConnect,
                    enabled = !isLoading && !isSorting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.NetworkCheck, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sort: Connect")
                }
            }
        }
    }
}

@Composable
fun ProtocolFilterRow(
    selectedProtocols: List<PeerProtocol>,
    onProtocolToggle: (PeerProtocol) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Filter by Protocol",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PeerProtocol.values().forEach { protocol ->
                    @OptIn(ExperimentalMaterial3Api::class)
                    FilterChip(
                        selected = protocol in selectedProtocols,
                        onClick = { onProtocolToggle(protocol) },
                        label = { Text(protocol.name) }
                    )
                }
            }
        }
    }
}

@Composable
fun AddPeerDialog(
    onDismiss: () -> Unit,
    onAddPeer: (String) -> Unit
) {
    var peerUri by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Manual Peer") },
        text = {
            OutlinedTextField(
                value = peerUri,
                onValueChange = { peerUri = it },
                label = { Text("Peer URI") },
                placeholder = { Text("tcp://example.com:12345") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (peerUri.isNotBlank()) {
                        onAddPeer(peerUri)
                    }
                },
                enabled = peerUri.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
