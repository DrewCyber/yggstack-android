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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.PublicPeerInfo
import link.yggdrasil.yggstack.android.utils.CountryFlags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDiscoveryScreen(
    viewModel: PeerDiscoveryViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Opens the manual Add Peer dialog; required so the button can never
    // silently degrade into something else.
    onAddPeer: () -> Unit
) {
    val externalIp by viewModel.externalIp.collectAsStateWithLifecycle()
    val displayPeers by viewModel.getDisplayPeers().collectAsStateWithLifecycle()
    val selectedPeers by viewModel.selectedPeers.collectAsStateWithLifecycle()
    val configPeers by viewModel.configPeers.collectAsStateWithLifecycle()
    val countryFilter by viewModel.countryFilter.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isCancellable by viewModel.isCancellable.collectAsStateWithLifecycle()
    val loadingMessage by viewModel.loadingMessage.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    // Countries present in the downloaded list — the filter dropdown's
    // options. Selected peers above the divider (incl. custom ones) are
    // never filtered out.
    val availableCountries = remember(displayPeers) {
        displayPeers.map { it.country }.filter { it.isNotBlank() }.distinct().sorted()
    }
    // Main list below the divider, filtered by the picked countries
    val filteredPeers = remember(displayPeers, countryFilter) {
        if (countryFilter.isEmpty()) displayPeers
        else displayPeers.filter { it.country in countryFilter }
    }
    
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
            // Action buttons, row 1: download + country filter
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

                CountryFilterButton(
                    countries = availableCountries,
                    selected = countryFilter,
                    onToggle = { viewModel.toggleCountryFilter(it) },
                    onReset = { viewModel.setCountryFilter(emptySet()) },
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                )
            }

            // Action buttons, row 2: manual entry + RTT check. Manual entry —
            // the old Add Peer dialog, for a custom peer discovery can't find.
            // Only adds to the selected peers list; no discovery involved.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddPeer,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_custom_peer))
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
                        // Only the visible (filtered) peers get checked
                        enabled = !isLoading && filteredPeers.isNotEmpty(),
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
            } else if (filteredPeers.isEmpty() && !isLoading) {
                // Peers are downloaded, but none pass the country filter
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_peers_match_filter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    val listState = rememberLazyListState()
                    val showScrollToTop by remember {
                        derivedStateOf {
                            listState.firstVisibleItemIndex > 3
                        }
                    }
                    
                    // Selected peers at top, in the same order as the Peers
                    // card on the Configuration screen (config.peers order).
                    // Peers missing from the discovery cache — manually added
                    // through Add Peer — are synthesized as entries so they
                    // show up checked too.
                    val selectedPeersList = configPeers.map { uri ->
                        displayPeers.firstOrNull { it.uri == uri }
                            ?: PublicPeerInfo(uri = uri, country = "")
                    }
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
                        
                        // Main list, filtered by the picked countries
                        // (all peers with checkboxes)
                        items(filteredPeers, key = { "main_${it.uri}" }) { peer ->
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

/**
 * The Country filter button on the Peer Discovery action row. Opens a
 * multi-select dropdown of the countries present in the downloaded peers
 * list; the selection persists across restarts. An empty selection means
 * no filtering (All countries).
 */
@Composable
private fun CountryFilterButton(
    countries: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Button(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Public,
                contentDescription = stringResource(R.string.country_filter),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when (selected.size) {
                    0 -> stringResource(R.string.country_filter)
                    1 -> selected.first().let { country ->
                        CountryFlags.flagEmoji(country)?.let { "$it $country" } ?: country
                    }
                    // pluralStringResource, not stringResource: the plain
                    // string overload wins resolution for (id, Int) and
                    // throws Resources$NotFoundException on a plurals ID.
                    // The count is also passed as a format arg — without it
                    // the raw "(%1$d)" pattern is returned unsubstituted.
                    else -> pluralStringResource(
                        R.plurals.countries_count, selected.size, selected.size
                    )
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Reset entry: clears the filter and closes the menu
            DropdownMenuItem(
                text = { Text(stringResource(R.string.all_countries)) },
                trailingIcon = {
                    if (selected.isEmpty()) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                },
                onClick = {
                    onReset()
                    expanded = false
                }
            )
            Divider()

            // Country entries: toggling keeps the menu open for
            // multi-select
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                countries.forEach { country ->
                    val flag = remember(country) { CountryFlags.flagEmoji(country) }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = flag?.let { "$it $country" } ?: country,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Checkbox(
                                checked = country in selected,
                                onCheckedChange = null
                            )
                        },
                        onClick = { onToggle(country) }
                    )
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
                    // Country flag where known, then the country name
                    if (peer.country.isNotBlank()) {
                        val flag = remember(peer.country) { CountryFlags.flagEmoji(peer.country) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (flag != null) {
                                Text(
                                    text = flag,
                                    fontSize = 16.sp
                                )
                            }
                            Text(
                                text = peer.country,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
