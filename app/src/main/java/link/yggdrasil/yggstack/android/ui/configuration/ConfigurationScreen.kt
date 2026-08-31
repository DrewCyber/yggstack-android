package link.yggdrasil.yggstack.android.ui.configuration

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.*
import link.yggdrasil.yggstack.android.ui.configuration.discovery.PeerDiscoveryScreen
import link.yggdrasil.yggstack.android.ui.configuration.discovery.PeerDiscoveryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    viewModel: ConfigurationViewModel,
    modifier: Modifier = Modifier
) {
    val config by viewModel.config.collectAsState()
    val serviceState by viewModel.serviceState.collectAsState()
    val showPrivateKey by viewModel.showPrivateKey.collectAsState()
    val savedScrollPosition by viewModel.scrollPosition.collectAsState()
    val pendingDeepLink by viewModel.pendingDeepLink.collectAsState()
    val showTransitTrafficWarning by viewModel.showTransitTrafficWarning.collectAsState()

    var editingPeer by remember { mutableStateOf<String?>(null) }
    var showPeerDialog by remember { mutableStateOf(false) }
    var showExposeDialog by remember { mutableStateOf(false) }
    var editingExposeMapping by remember { mutableStateOf<ExposeMapping?>(null) }
    var deepLinkExposePrefill by remember { mutableStateOf<ExposeMapping?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var editingForwardMapping by remember { mutableStateOf<ForwardMapping?>(null) }
    var deepLinkForwardPrefill by remember { mutableStateOf<ForwardMapping?>(null) }
    var showPeerDiscovery by remember { mutableStateOf(false) }
    var showGroupPassword by remember { mutableStateOf(false) }
    var dnsServerInput by remember { mutableStateOf(config.dnsServer) }
    var isDnsServerFocused by remember { mutableStateOf(false) }

    // Open the relevant dialog when a deep link arrives
    LaunchedEffect(pendingDeepLink) {
        when (val link = pendingDeepLink) {
            is PendingDeepLink.ExposeLink -> {
                editingExposeMapping = null
                deepLinkExposePrefill = link.mapping
                showExposeDialog = true
                viewModel.consumePendingDeepLink()
            }
            is PendingDeepLink.ForwardLink -> {
                editingForwardMapping = null
                deepLinkForwardPrefill = link.mapping
                showForwardDialog = true
                viewModel.consumePendingDeepLink()
            }
            null -> Unit
        }
    }

    val scrollState = rememberScrollState(initial = savedScrollPosition)
    
    // Save scroll position when it changes
    LaunchedEffect(scrollState.value) {
        viewModel.saveScrollPosition(scrollState.value)
    }

    LaunchedEffect(config.dnsServer, isDnsServerFocused) {
        if (!isDnsServerFocused && dnsServerInput != config.dnsServer) {
            dnsServerInput = config.dnsServer
        }
    }

    // Treat Stopping as still-running for edit-gating: the service briefly reports
    // Stopping before Power Save's idle flag flips true, and without this, config
    // sections that are hidden/disabled while running (Add peer, Add mapping, etc.)
    // flash back into their editable state for that transient window.
    val isServiceRunning = serviceState is ServiceState.Running ||
        serviceState is ServiceState.PowerSaving ||
        serviceState is ServiceState.Stopping

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp)
                .padding(bottom = 50.dp) // Space for button at bottom
        ) {
            // App title as part of scrollable content
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // Private Key Section
            Card(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = config.privateKey,
                    onValueChange = { viewModel.updatePrivateKey(it) },
                    label = { Text(stringResource(R.string.private_key_section)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    enabled = !isServiceRunning,
                    visualTransformation = if (showPrivateKey) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = !showPrivateKey,
                    maxLines = if (showPrivateKey) Int.MAX_VALUE else 1,
                    trailingIcon = {
                        IconButton(onClick = { viewModel.toggleShowPrivateKey() }) {
                            Icon(
                                if (showPrivateKey) Icons.Default.Lock else Icons.Default.Edit,
                                contentDescription = if (showPrivateKey)
                                    stringResource(R.string.hide_private_key)
                                else
                                    stringResource(R.string.show_private_key)
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Peers Section with clickable header
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    // Header with title and manage button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.peers_section),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        TextButton(
                            onClick = { showPeerDiscovery = true },
                            enabled = !isServiceRunning
                        ) {
                            Icon(
                                Icons.Default.ManageSearch,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.discover_peers))
                        }
                    }
                    
                    config.peers.forEach { peer ->
                        PeerItem(
                            peer = peer,
                            isEnabled = peer !in config.disabledPeers,
                            onToggleEnabled = { viewModel.togglePeerEnabled(peer) },
                            enabled = !isServiceRunning,
                            onEdit = {
                                editingPeer = peer
                                showPeerDialog = true
                            }
                        )
                    }

                    if (!isServiceRunning) {
                        Button(
                            onClick = {
                                editingPeer = null
                                showPeerDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.add_peer))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // MaxBackoff setting
                    var showMaxBackoffDialog by remember { mutableStateOf(false) }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MaxBackoff",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = { showMaxBackoffDialog = true },
                                enabled = !isServiceRunning && config.maxBackoffEnabled
                            ) {
                                Text(
                                    text = "${config.maxBackoff}s",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Switch(
                                checked = config.maxBackoffEnabled,
                                onCheckedChange = { viewModel.setMaxBackoffEnabled(it) },
                                enabled = !isServiceRunning,
                                modifier = Modifier.scale(0.6f)
                            )
                        }
                    }
                    
                    if (showMaxBackoffDialog) {
                        MaxBackoffDialog(
                            currentValue = config.maxBackoff,
                            onConfirm = { newValue ->
                                viewModel.updateMaxBackoff(newValue)
                                showMaxBackoffDialog = false
                            },
                            onDismiss = { showMaxBackoffDialog = false }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = config.groupPassword,
                            onValueChange = { viewModel.updateGroupPassword(it) },
                            label = { Text(stringResource(R.string.group_password_label)) },
                            modifier = Modifier.weight(1f),
                            enabled = !isServiceRunning,
                            singleLine = true,
                            visualTransformation = if (showGroupPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showGroupPassword = !showGroupPassword },
                                    enabled = !isServiceRunning
                                ) {
                                    Icon(
                                        if (showGroupPassword) Icons.Default.Lock else Icons.Default.Edit,
                                        contentDescription = if (showGroupPassword)
                                            stringResource(R.string.hide_private_key)
                                        else
                                            stringResource(R.string.show_private_key)
                                    )
                                }
                            }
                        )
                        Switch(
                            checked = config.groupPasswordEnabled,
                            onCheckedChange = { viewModel.setGroupPasswordEnabled(it) },
                            enabled = !isServiceRunning &&
                                    (config.groupPasswordEnabled || config.groupPassword.isNotBlank()),
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .scale(0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            // Multicast Discovery Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    // Multicast Discovery title
                    Text(
                        text = stringResource(R.string.multicast_discovery),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    // Multicast switches - closer together
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Discover Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.multicast_discover),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(
                                checked = config.multicastListen,
                                onCheckedChange = { viewModel.setMulticastListen(it) },
                                enabled = !isServiceRunning,
                                modifier = Modifier.scale(0.6f)
                            )
                        }
                        
                        // Advertise Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.multicast_advertise),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(
                                checked = config.multicastBeacon,
                                onCheckedChange = { viewModel.setMulticastBeacon(it) },
                                enabled = !isServiceRunning,
                                modifier = Modifier.scale(0.6f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Proxy Configuration Section
            ConfigSectionWithToggle(
                title = stringResource(R.string.proxy_config_section),
                enabled = config.proxyEnabled,
                onToggle = { viewModel.toggleProxyEnabled() },
                isServiceRunning = isServiceRunning
            ) {
                OutlinedTextField(
                    value = config.socksProxy,
                    onValueChange = { viewModel.updateSocksProxy(it) },
                    label = { Text(stringResource(R.string.socks_proxy)) },
                    placeholder = { Text(stringResource(R.string.socks_proxy_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isServiceRunning && config.proxyEnabled
                )

                Spacer(modifier = Modifier.height(8.dp))

                val context = LocalContext.current
                OutlinedTextField(
                    value = dnsServerInput,
                    onValueChange = {
                        dnsServerInput = it
                        viewModel.updateDnsServer(it)
                    },
                    label = { Text(stringResource(R.string.dns_server)) },
                    placeholder = { Text(stringResource(R.string.dns_server_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            val wasFocused = isDnsServerFocused
                            isDnsServerFocused = focusState.isFocused
                            if (wasFocused && !focusState.isFocused) {
                                val normalizedDnsServer = ConfigRepository.normalizeDnsServer(dnsServerInput)
                                if (normalizedDnsServer != dnsServerInput) {
                                    dnsServerInput = normalizedDnsServer
                                }
                                if (normalizedDnsServer != config.dnsServer) {
                                    viewModel.updateDnsServer(normalizedDnsServer)
                                }
                            }
                        },
                    enabled = !isServiceRunning && config.proxyEnabled,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dns.r3v.dev/"))
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_alfis),
                                contentDescription = "Open DNS service",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

            }

            Spacer(modifier = Modifier.height(12.dp))

            // Expose Local Port Section
            ConfigSectionWithToggle(
                title = stringResource(R.string.expose_local_port_section),
                enabled = config.exposeEnabled,
                onToggle = { viewModel.toggleExposeEnabled() },
                isServiceRunning = isServiceRunning
            ) {
                ReorderableColumn(
                    items = config.exposeMappings,
                    onReorder = { viewModel.reorderExposeMappings(it) },
                    enabled = !isServiceRunning && config.exposeEnabled
                ) { mapping, isDragging ->
                    ExposeMappingItem(
                        mapping = mapping,
                        enabled = !isServiceRunning && config.exposeEnabled,
                        checkboxEnabled = config.exposeEnabled,
                        isDragging = isDragging,
                        onEdit = {
                            editingExposeMapping = mapping
                            showExposeDialog = true
                        },
                        onToggle = { viewModel.toggleExposeMapping(mapping) }
                    )
                }

                if (!isServiceRunning && config.exposeEnabled) {
                    Button(
                        onClick = { showExposeDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_mapping))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

            }

            Spacer(modifier = Modifier.height(12.dp))

            // Forward Remote Port Section
            ConfigSectionWithToggle(
                title = stringResource(R.string.forward_remote_port_section),
                enabled = config.forwardEnabled,
                onToggle = { viewModel.toggleForwardEnabled() },
                isServiceRunning = isServiceRunning
            ) {
                ReorderableColumn(
                    items = config.forwardMappings,
                    onReorder = { viewModel.reorderForwardMappings(it) },
                    enabled = !isServiceRunning && config.forwardEnabled
                ) { mapping, isDragging ->
                    ForwardMappingItem(
                        mapping = mapping,
                        enabled = !isServiceRunning && config.forwardEnabled,
                        checkboxEnabled = config.forwardEnabled,
                        isDragging = isDragging,
                        onEdit = {
                            editingForwardMapping = mapping
                            showForwardDialog = true
                        },
                        onToggle = { viewModel.toggleForwardMapping(mapping) }
                    )
                }

                if (!isServiceRunning && config.forwardEnabled) {
                    Button(
                        onClick = { showForwardDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_mapping))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

            }

            Spacer(modifier = Modifier.height(12.dp))

            // Power Save Section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    val exposedActive = config.hasActiveExposedPorts()
                    var showHowItWorks by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.power_save_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(
                                onClick = { showHowItWorks = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HelpOutline,
                                    contentDescription = stringResource(R.string.power_save_how_it_works),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Switch(
                            checked = config.powerSaveEnabled,
                            onCheckedChange = { viewModel.setPowerSaveEnabled(it) },
                            enabled = !isServiceRunning && !exposedActive,
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    if (exposedActive) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.power_save_exposed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showHowItWorks) {
                        PowerSaveHowItWorksDialog(onDismiss = { showHowItWorks = false })
                    }

                    if (config.powerSaveEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))

                        var showIdleTimeoutDialog by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.power_save_idle_timeout_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(
                                onClick = { showIdleTimeoutDialog = true },
                                enabled = !isServiceRunning
                            ) {
                                Text(
                                    text = formatMinutesSeconds(config.powerSaveIdleTimeoutSeconds),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        if (showIdleTimeoutDialog) {
                            PowerSaveIdleTimeoutDialog(
                                currentValue = config.powerSaveIdleTimeoutSeconds,
                                onConfirm = { newValue ->
                                    viewModel.setPowerSaveIdleTimeoutSeconds(newValue)
                                    showIdleTimeoutDialog = false
                                },
                                onDismiss = { showIdleTimeoutDialog = false }
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Log Level Section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    val logsEnabled by viewModel.logsEnabled.collectAsState()
                    val logLevels = listOf("error", "warn", "info", "debug")
                    val logLevelLabels = mapOf(
                        "error" to stringResource(R.string.log_level_error),
                        "warn" to stringResource(R.string.log_level_warn),
                        "info" to stringResource(R.string.log_level_info),
                        "debug" to stringResource(R.string.log_level_debug)
                    )
                    
                    // Title with toggle on same row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.log_level),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Switch(
                            checked = logsEnabled,
                            onCheckedChange = { viewModel.setLogsEnabled(it) },
                            enabled = !isServiceRunning,
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    
                    if (logsEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                        ) {
                        logLevels.forEachIndexed { index, level ->
                            Button(
                                onClick = { viewModel.setLogLevel(level) },
                                enabled = !isServiceRunning && logsEnabled,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (config.logLevel == level) {
                                        // Use red color for debug level when selected
                                        if (level == "debug") {
                                            Color(0xFFDC3545) // Red color for debug
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (config.logLevel == level) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    disabledContainerColor = if (config.logLevel == level) {
                                        // Keep red color for debug when disabled
                                        if (level == "debug") {
                                            Color(0xFFDC3545).copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        }
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    },
                                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                                ),
                                shape = when (index) {
                                    0 -> MaterialTheme.shapes.small.copy(
                                        topEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
                                        bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)
                                    )
                                    logLevels.size - 1 -> MaterialTheme.shapes.small.copy(
                                        topStart = androidx.compose.foundation.shape.CornerSize(0.dp),
                                        bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp)
                                    )
                                    else -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = logLevelLabels[level] ?: level,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                Spacer(modifier = Modifier.height(8.dp))

                }
            }
        }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // Start/Stop Button - Sticky at bottom
        Button(
            onClick = {
                if (isServiceRunning) {
                    viewModel.stopService()
                } else {
                    viewModel.startService()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            enabled = serviceState !is ServiceState.Starting && serviceState !is ServiceState.Stopping,
            // Disabled colors are pinned to the same enabled-state colors: Material3's
            // default disabled look is a near-transparent onSurface overlay, which made
            // the button appear to vanish during the brief Stopping window (e.g. while
            // Power Save is tearing the node down before it reports idle).
            colors = if (isServiceRunning) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    disabledContainerColor = MaterialTheme.colorScheme.error,
                    disabledContentColor = MaterialTheme.colorScheme.onError
                )
            } else {
                ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        ) {
            Text(
                if (isServiceRunning)
                    stringResource(R.string.stop_service)
                else
                    stringResource(R.string.start_service)
            )
        }
    }

    // Dialogs
    if (showTransitTrafficWarning) {
        TransitTrafficWarningDialog(
            onConfirm = { suppress -> viewModel.confirmStartWithWarning(suppress) },
            onDismiss = { viewModel.dismissTransitWarning() }
        )
    }

    if (showPeerDiscovery) {
        val context = LocalContext.current
        val repository = ConfigRepository(context)
        val discoveryViewModel: PeerDiscoveryViewModel = viewModel(
            factory = PeerDiscoveryViewModel.Factory(repository)
        )
        
        PeerDiscoveryScreen(
            viewModel = discoveryViewModel,
            onDismiss = { showPeerDiscovery = false }
        )
    }

    if (showExposeDialog) {
        key(showExposeDialog, editingExposeMapping, deepLinkExposePrefill) {
            ExposeMappingDialog(
                initialMapping = editingExposeMapping,
                prefillMapping = deepLinkExposePrefill,
                existingExposeMappings = config.exposeMappings,
                existingForwardMappings = config.forwardMappings,
                onDismiss = {
                    showExposeDialog = false
                    editingExposeMapping = null
                    deepLinkExposePrefill = null
                },
                onConfirm = { mapping ->
                    if (editingExposeMapping != null) {
                        viewModel.updateExposeMapping(editingExposeMapping!!, mapping)
                        editingExposeMapping = null
                    } else {
                        viewModel.addExposeMapping(mapping)
                    }
                    deepLinkExposePrefill = null
                    showExposeDialog = false
                },
                onDelete = editingExposeMapping?.let { mapping ->
                    {
                        viewModel.removeExposeMapping(mapping)
                        showExposeDialog = false
                        editingExposeMapping = null
                    }
                }
            )
        }
    }

    if (showForwardDialog) {
        key(showForwardDialog, editingForwardMapping, deepLinkForwardPrefill) {
            ForwardMappingDialog(
                initialMapping = editingForwardMapping,
                prefillMapping = deepLinkForwardPrefill,
                existingExposeMappings = config.exposeMappings,
                existingForwardMappings = config.forwardMappings,
                onDismiss = {
                    showForwardDialog = false
                    editingForwardMapping = null
                    deepLinkForwardPrefill = null
                },
                onConfirm = { mapping ->
                    if (editingForwardMapping != null) {
                        viewModel.updateForwardMapping(editingForwardMapping!!, mapping)
                        editingForwardMapping = null
                    } else {
                        viewModel.addForwardMapping(mapping)
                    }
                    deepLinkForwardPrefill = null
                    showForwardDialog = false
                },
                onDelete = editingForwardMapping?.let { mapping ->
                    {
                        viewModel.removeForwardMapping(mapping)
                        showForwardDialog = false
                        editingForwardMapping = null
                    }
                }
            )
        }
    }

    if (showPeerDialog) {
        key(showPeerDialog, editingPeer) {
            PeerDialog(
                initialPeer = editingPeer,
                onDismiss = {
                    showPeerDialog = false
                    editingPeer = null
                },
                onConfirm = { newPeer ->
                    if (editingPeer != null) {
                        viewModel.updatePeer(editingPeer!!, newPeer)
                    } else {
                        viewModel.addPeer(newPeer)
                    }
                    showPeerDialog = false
                    editingPeer = null
                },
                onDelete = editingPeer?.let { peer ->
                    {
                        viewModel.removePeer(peer)
                        showPeerDialog = false
                        editingPeer = null
                    }
                }
            )
        }
    }
}

@Composable
fun ConfigSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
fun ConfigSectionWithToggle(
    title: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    isServiceRunning: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggle() },
                    enabled = !isServiceRunning,
                    modifier = Modifier.scale(0.8f)
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
fun PeerItem(
    peer: String,
    isEnabled: Boolean,
    onToggleEnabled: () -> Unit,
    enabled: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onEdit) else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = peer,
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Checkbox(
            checked = isEnabled,
            onCheckedChange = { onToggleEnabled() }
        )
    }
}

@Composable
fun ExposeMappingItem(
    mapping: ExposeMapping,
    enabled: Boolean,
    checkboxEnabled: Boolean,
    isDragging: Boolean = false,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isDragging) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onEdit) else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (mapping.shortName.isNotBlank()) mapping.shortName
                   else "${mapping.protocol.name.lowercase()} ${mapping.localPort} ${mapping.localIp} → ${mapping.yggPort}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Checkbox(
            checked = mapping.enabled,
            onCheckedChange = onToggle,
            enabled = checkboxEnabled
        )
    }
}

@Composable
fun ForwardMappingItem(
    mapping: ForwardMapping,
    enabled: Boolean,
    checkboxEnabled: Boolean,
    isDragging: Boolean = false,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isDragging) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onEdit) else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (mapping.shortName.isNotBlank()) mapping.shortName
                   else "${mapping.protocol.name.lowercase()} ${mapping.localIp}:${mapping.localPort} → [${mapping.remoteIp}]:${mapping.remotePort}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Checkbox(
            checked = mapping.enabled,
            onCheckedChange = onToggle,
            enabled = checkboxEnabled
        )
    }
}

@Composable
fun TransitTrafficWarningDialog(
    onConfirm: (suppress: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var suppressChecked by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.transit_warning_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.transit_warning_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = suppressChecked,
                        onCheckedChange = { suppressChecked = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.transit_warning_suppress),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { suppressChecked = !suppressChecked }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(suppressChecked) }) {
                Text(stringResource(R.string.transit_warning_start_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposeMappingDialog(
    initialMapping: ExposeMapping? = null,
    prefillMapping: ExposeMapping? = null,
    existingExposeMappings: List<ExposeMapping> = emptyList(),
    existingForwardMappings: List<ForwardMapping> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (ExposeMapping) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val fill = prefillMapping ?: initialMapping
    var protocol by remember { mutableStateOf(fill?.protocol ?: Protocol.TCP) }
    var localPort by remember { mutableStateOf(fill?.localPort?.toString() ?: "") }
    var localIp by remember { mutableStateOf(fill?.localIp ?: "127.0.0.1") }
    var yggPort by remember { mutableStateOf(fill?.yggPort?.toString() ?: "") }
    var shortName by remember { mutableStateOf(fill?.shortName ?: "") }
    
    var localPortError by remember { mutableStateOf(false) }
    var localIpError by remember { mutableStateOf(false) }
    var yggPortError by remember { mutableStateOf(false) }

    fun validatePort(port: String): Boolean {
        val portNum = port.toIntOrNull() ?: return false
        return portNum in 1..65535
    }

    fun validateIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    // Check if localIp + localPort + protocol is already used by another mapping
    val localConflict = run {
        val portNum = localPort.toIntOrNull() ?: return@run false
        existingExposeMappings.any { m ->
            m != initialMapping && m.protocol == protocol && m.localIp == localIp && m.localPort == portNum
        } || existingForwardMappings.any { m ->
            m.protocol == protocol && m.localIp == localIp && m.localPort == portNum
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialMapping != null) "Edit Expose Mapping" else "Add Expose Mapping") },
        text = {
            Column {
                // Protocol selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = protocol == Protocol.TCP,
                            onClick = { protocol = Protocol.TCP },
                            label = { Text(stringResource(R.string.protocol_tcp)) }
                        )
                        FilterChip(
                            selected = protocol == Protocol.UDP,
                            onClick = { protocol = Protocol.UDP },
                            label = { Text(stringResource(R.string.protocol_udp)) }
                        )
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete_peer),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = localPort,
                    onValueChange = { 
                        localPort = it
                        localPortError = it.isNotEmpty() && !validatePort(it)
                    },
                    label = { Text(stringResource(R.string.local_port)) },
                    placeholder = { Text("1-65535") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = localPortError || localConflict,
                    supportingText = when {
                        localPortError -> { { Text("Port must be between 1-65535") } }
                        localConflict -> { { Text("Already in use by another mapping") } }
                        else -> null
                    }
                )

                OutlinedTextField(
                    value = localIp,
                    onValueChange = { 
                        localIp = it
                        localIpError = it.isNotEmpty() && !validateIPv4(it)
                    },
                    label = { Text(stringResource(R.string.local_ip)) },
                    placeholder = { Text("127.0.0.1") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = localIpError || localConflict,
                    supportingText = if (localIpError) {
                        { Text("Invalid IPv4 address") }
                    } else null
                )

                OutlinedTextField(
                    value = yggPort,
                    onValueChange = { 
                        yggPort = it
                        yggPortError = it.isNotEmpty() && !validatePort(it)
                    },
                    label = { Text(stringResource(R.string.ygg_port)) },
                    placeholder = { Text("1-65535") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = yggPortError,
                    supportingText = if (yggPortError) {
                        { Text("Port must be between 1-65535") }
                    } else null
                )

                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it },
                    label = { Text(stringResource(R.string.short_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            val allValid = localPort.isNotEmpty() && localIp.isNotEmpty() && yggPort.isNotEmpty() &&
                    !localPortError && !localIpError && !yggPortError
            TextButton(
                onClick = {
                    if (validatePort(localPort) && validateIPv4(localIp) && validatePort(yggPort)) {
                        onConfirm(ExposeMapping(protocol, localPort.toInt(), localIp, yggPort.toInt(), shortName.trim(), enabled = !localConflict))
                    }
                },
                enabled = allValid
            ) {
                Text(if (initialMapping != null) "Update" else "Add")
            }
        },
        dismissButton = {
            val context = LocalContext.current
            val allValid = localPort.isNotEmpty() && localIp.isNotEmpty() && yggPort.isNotEmpty() &&
                    !localPortError && !localIpError && !yggPortError
            Row {
                TextButton(
                    onClick = {
                        val url = buildString {
                            append("https://DrewCyber.github.io/mapping/expose")
                            append("?proto=").append(protocol.name)
                            append("&localPort=").append(localPort)
                            append("&localIp=").append(localIp)
                            append("&yggPort=").append(yggPort)
                            if (shortName.isNotBlank()) append("&name=").append(Uri.encode(shortName.trim()))
                        }
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                    enabled = allValid
                ) {
                    Text("Share")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardMappingDialog(
    initialMapping: ForwardMapping? = null,
    prefillMapping: ForwardMapping? = null,
    existingExposeMappings: List<ExposeMapping> = emptyList(),
    existingForwardMappings: List<ForwardMapping> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (ForwardMapping) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val fill = prefillMapping ?: initialMapping
    var protocol by remember { mutableStateOf(fill?.protocol ?: Protocol.TCP) }
    var localIp by remember { mutableStateOf(fill?.localIp ?: "127.0.0.1") }
    var localPort by remember { mutableStateOf(fill?.localPort?.toString() ?: "") }
    var remoteIp by remember { mutableStateOf(fill?.remoteIp ?: "") }
    var remotePort by remember { mutableStateOf(fill?.remotePort?.toString() ?: "") }
    var shortName by remember { mutableStateOf(fill?.shortName ?: "") }
    
    var localIpError by remember { mutableStateOf(false) }
    var localPortError by remember { mutableStateOf(false) }
    var remoteIpError by remember { mutableStateOf(false) }
    var remotePortError by remember { mutableStateOf(false) }

    fun validatePort(port: String): Boolean {
        val portNum = port.toIntOrNull() ?: return false
        return portNum in 1..65535
    }

    fun validateIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    fun validateIPv6(ip: String): Boolean {
        // Simple IPv6 validation - check for colon-separated hex values
        if (!ip.contains(":")) return false
        val parts = ip.split(":")
        if (parts.size > 8) return false
        return parts.all { part ->
            part.isEmpty() || part.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }
    }

    // Check if localIp + localPort + protocol is already used by another mapping
    val localConflict = run {
        val portNum = localPort.toIntOrNull() ?: return@run false
        existingForwardMappings.any { m ->
            m != initialMapping && m.protocol == protocol && m.localIp == localIp && m.localPort == portNum
        } || existingExposeMappings.any { m ->
            m.protocol == protocol && m.localIp == localIp && m.localPort == portNum
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialMapping != null) "Edit Forward Mapping" else "Add Forward Mapping") },
        text = {
            Column {
                // Protocol selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = protocol == Protocol.TCP,
                            onClick = { protocol = Protocol.TCP },
                            label = { Text(stringResource(R.string.protocol_tcp)) }
                        )
                        FilterChip(
                            selected = protocol == Protocol.UDP,
                            onClick = { protocol = Protocol.UDP },
                            label = { Text(stringResource(R.string.protocol_udp)) }
                        )
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete_peer),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = localIp,
                    onValueChange = { 
                        localIp = it
                        localIpError = it.isNotEmpty() && !validateIPv4(it) && it != "::1"
                    },
                    label = { Text(stringResource(R.string.local_ip)) },
                    placeholder = { Text("127.0.0.1 or ::1") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = localIpError || localConflict,
                    supportingText = if (localIpError) {
                        { Text("Invalid IP address") }
                    } else null
                )

                OutlinedTextField(
                    value = localPort,
                    onValueChange = { 
                        localPort = it
                        localPortError = it.isNotEmpty() && !validatePort(it)
                    },
                    label = { Text(stringResource(R.string.local_port)) },
                    placeholder = { Text("1-65535") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = localPortError || localConflict,
                    supportingText = when {
                        localPortError -> { { Text("Port must be between 1-65535") } }
                        localConflict -> { { Text("Already in use by another mapping") } }
                        else -> null
                    }
                )

                OutlinedTextField(
                    value = remoteIp,
                    onValueChange = { 
                        remoteIp = it
                        remoteIpError = it.isNotEmpty() && !validateIPv6(it)
                    },
                    label = { Text(stringResource(R.string.remote_ip)) },
                    placeholder = { Text("200:1234::1") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = remoteIpError,
                    supportingText = if (remoteIpError) {
                        { Text("Invalid IPv6 address") }
                    } else null
                )

                OutlinedTextField(
                    value = remotePort,
                    onValueChange = { 
                        remotePort = it
                        remotePortError = it.isNotEmpty() && !validatePort(it)
                    },
                    label = { Text(stringResource(R.string.remote_port)) },
                    placeholder = { Text("1-65535") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = remotePortError,
                    supportingText = if (remotePortError) {
                        { Text("Port must be between 1-65535") }
                    } else null
                )

                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it },
                    label = { Text(stringResource(R.string.short_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            val allValid = localPort.isNotEmpty() && localIp.isNotEmpty() &&
                    remoteIp.isNotEmpty() && remotePort.isNotEmpty() &&
                    !localPortError && !localIpError && !remoteIpError && !remotePortError
            TextButton(
                onClick = {
                    if (validatePort(localPort) && validatePort(remotePort) &&
                        (validateIPv4(localIp) || localIp == "::1") && validateIPv6(remoteIp)) {
                        onConfirm(ForwardMapping(protocol, localIp, localPort.toInt(), remoteIp, remotePort.toInt(), shortName.trim(), enabled = !localConflict))
                    }
                },
                enabled = allValid
            ) {
                Text(if (initialMapping != null) "Update" else "Add")
            }
        },
        dismissButton = {
            val context = LocalContext.current
            val allValid = localPort.isNotEmpty() && localIp.isNotEmpty() &&
                    remoteIp.isNotEmpty() && remotePort.isNotEmpty() &&
                    !localPortError && !localIpError && !remoteIpError && !remotePortError
            Row {
                TextButton(
                    onClick = {
                        val url = buildString {
                            append("https://DrewCyber.github.io/mapping/forward")
                            append("?proto=").append(protocol.name)
                            append("&localIp=").append(localIp)
                            append("&localPort=").append(localPort)
                            append("&remoteIp=").append(remoteIp)
                            append("&remotePort=").append(remotePort)
                            if (shortName.isNotBlank()) append("&name=").append(Uri.encode(shortName.trim()))
                        }
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                    enabled = allValid
                ) {
                    Text("Share")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun PeerDialog(
    initialPeer: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var peerUri by remember { mutableStateOf(initialPeer ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (initialPeer != null) "Edit Peer" else stringResource(R.string.add_peer))
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete_peer),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            OutlinedTextField(
                value = peerUri,
                onValueChange = { peerUri = it },
                label = { Text(stringResource(R.string.peer_uri_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(peerUri.trim()) },
                enabled = peerUri.isNotBlank()
            ) {
                Text(if (initialPeer != null) "Update" else "Add")
            }
        },
        dismissButton = {
            val context = LocalContext.current
            Row {
                TextButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, peerUri.trim())
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                    enabled = peerUri.isNotBlank()
                ) {
                    Text("Share")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun MaxBackoffDialog(
    currentValue: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sliderValue by remember { mutableStateOf(currentValue.toFloat()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.max_reconnection_backoff)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.max_reconnection_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Text(
                    text = "${sliderValue.toInt()}s",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 5f..30f,
                    steps = 24, // 25 total values (5-30)
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "5s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "30s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(sliderValue.toInt()) }
            ) {
                Text(stringResource(R.string.ok))
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
fun PowerSaveHowItWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.power_save_how_it_works_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.power_save_how_it_works_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

@Composable
fun PowerSaveIdleTimeoutDialog(
    currentValue: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sliderValue by remember { mutableStateOf(currentValue.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.power_save_idle_timeout_label)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.power_save_idle_timeout_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = formatMinutesSeconds(sliderValue.toInt()),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 10f..120f,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "10s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "120s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(sliderValue.toInt()) }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatMinutesSeconds(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes > 0 && seconds > 0 -> "${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

