package link.yggdrasil.yggstack.android.ui.configuration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val config by viewModel.config.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val showPrivateKey by viewModel.showPrivateKey.collectAsStateWithLifecycle()
    val pendingDeepLink by viewModel.pendingDeepLink.collectAsStateWithLifecycle()
    val showTransitTrafficWarning by viewModel.showTransitTrafficWarning.collectAsStateWithLifecycle()

    var editingPeer by remember { mutableStateOf<String?>(null) }
    var showPeerDialog by remember { mutableStateOf(false) }
    var showExposeDialog by remember { mutableStateOf(false) }
    var editingExposeMapping by remember { mutableStateOf<ExposeMapping?>(null) }
    var deepLinkExposePrefill by remember { mutableStateOf<ExposeMapping?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var editingForwardMapping by remember { mutableStateOf<ForwardMapping?>(null) }
    var deepLinkForwardPrefill by remember { mutableStateOf<ForwardMapping?>(null) }
    var showPeerDiscovery by remember { mutableStateOf(false) }
    var showListenScreen by remember { mutableStateOf(false) }
    var showGroupPassword by remember { mutableStateOf(false) }

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

    // Read the saved position once for restoration instead of collecting it:
    // subscribing here would recompose the whole screen on every save
    val savedScrollPosition = viewModel.scrollPosition.value
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition.first,
        initialFirstVisibleItemScrollOffset = savedScrollPosition.second
    )

    // Save the scroll position once per gesture — when scrolling settles — and
    // when the screen leaves composition (bottom-nav tab switch), instead of
    // on every scrolled pixel
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    viewModel.saveScrollPosition(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset
                    )
                }
            }
    }
    DisposableEffect(listState, viewModel) {
        onDispose {
            viewModel.saveScrollPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 62.dp) // Space for button at bottom
        ) {
            item(key = "title") {
            // App title as part of scrollable content
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            }
            item(key = "peers") {
            // Peers Section with clickable header
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    // Header
                    Text(
                        text = stringResource(R.string.peers_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

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

                    // Peer discovery entry, where manual Add Peer used to be.
                    if (!isServiceRunning) {
                        FilledTonalButton(
                            onClick = { showPeerDiscovery = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.ManageSearch,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.discover_peers))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(3.dp))
            }
            item(key = "multicast") {
            // Multicast Discovery Card (collapsed by default; persisted)
            val multicastExpanded by viewModel.multicastExpanded.collectAsStateWithLifecycle()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    // Multicast Discovery title with expand/collapse toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setMulticastExpanded(!multicastExpanded) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.multicast_discovery),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp)
                        )
                        Icon(
                            imageVector = if (multicastExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    if (multicastExpanded) {
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
            }

            Spacer(modifier = Modifier.height(12.dp))
            }
            item(key = "proxy") {
            // Proxy Configuration Section
            var showProxyHelp by remember { mutableStateOf(false) }
            var showSocksDialog by remember { mutableStateOf(false) }
            var showHttpDialog by remember { mutableStateOf(false) }
            var showDnsDialog by remember { mutableStateOf(false) }
            var showPacDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current

            if (showProxyHelp) {
                ProxyHowItWorksDialog(onDismiss = { showProxyHelp = false })
            }

            if (showSocksDialog) {
                ProxyAddressDialog(
                    title = stringResource(R.string.socks5_proxy_title),
                    initialAddress = config.socksProxy,
                    defaultPort = 1080,
                    onDismiss = { showSocksDialog = false },
                    onConfirm = { address ->
                        viewModel.updateSocksProxy(address)
                        showSocksDialog = false
                    }
                )
            }

            if (showHttpDialog) {
                ProxyAddressDialog(
                    title = stringResource(R.string.http_proxy_title),
                    initialAddress = config.httpProxy,
                    defaultPort = 8080,
                    onDismiss = { showHttpDialog = false },
                    onConfirm = { address ->
                        viewModel.updateHttpProxy(address)
                        showHttpDialog = false
                    }
                )
            }

            if (showDnsDialog) {
                DnsServerDialog(
                    initialPrimary = config.dnsServer,
                    initialSecondary = config.dnsServer2,
                    onDismiss = { showDnsDialog = false },
                    onConfirm = { primary, secondary ->
                        viewModel.updateDnsServers(primary, secondary)
                        showDnsDialog = false
                    }
                )
            }

            if (showPacDialog) {
                PacServerDialog(
                    initialIp = config.pacIp,
                    initialPort = config.pacPort,
                    initialAllTraffic = config.pacAllTraffic,
                    onDismiss = { showPacDialog = false },
                    onConfirm = { ip, port, allTraffic ->
                        viewModel.updatePacIp(ip)
                        viewModel.updatePacPort(port)
                        viewModel.setPacAllTraffic(allTraffic)
                        showPacDialog = false
                    }
                )
            }

            ConfigSectionWithToggle(
                title = stringResource(R.string.proxy_config_section),
                enabled = config.proxyEnabled,
                onToggle = { viewModel.toggleProxyEnabled() },
                isServiceRunning = isServiceRunning,
                helpContentDescription = stringResource(R.string.proxy_how_it_works),
                onHelpClick = { showProxyHelp = true }
            ) {
                val rowEnabled = !isServiceRunning && config.proxyEnabled

                ProxySettingRow(
                    label = stringResource(R.string.socks5_proxy_title),
                    value = config.socksProxy.ifBlank { stringResource(R.string.socks_proxy_hint) },
                    checked = config.socksEnabled,
                    onCheckedChange = { viewModel.toggleSocksEnabled() },
                    enabled = rowEnabled,
                    onClick = { showSocksDialog = true },
                    trailing = {
                        if (config.socksProxy.isNotBlank()) {
                            CopyAddressButton(context, config.socksProxy)
                        }
                    }
                )

                val dnsValue = config.dnsServer.ifBlank { stringResource(R.string.dns_server_hint) } +
                    if (config.dnsServer2.isNotBlank()) ", ${config.dnsServer2}" else ""
                ProxySettingRow(
                    label = stringResource(R.string.dns_server),
                    value = dnsValue,
                    enabled = rowEnabled,
                    onClick = { showDnsDialog = true }
                )

                ProxySettingRow(
                    label = stringResource(R.string.http_proxy_title),
                    value = config.httpProxy.ifBlank { stringResource(R.string.http_proxy_hint) },
                    checked = config.httpEnabled,
                    onCheckedChange = { viewModel.toggleHttpEnabled() },
                    enabled = rowEnabled,
                    onClick = { showHttpDialog = true },
                    trailing = {
                        if (config.httpProxy.isNotBlank()) {
                            CopyAddressButton(context, config.httpProxy)
                        }
                    }
                )

                ProxySettingRow(
                    label = stringResource(R.string.pac_server),
                    value = PacGenerator.pacUrl(config.pacIp, config.pacPort),
                    checked = config.pacEnabled,
                    onCheckedChange = { viewModel.togglePacEnabled() },
                    enabled = rowEnabled,
                    onClick = { showPacDialog = true },
                    trailing = {
                        // The URL is static while configured — copyable even with
                        // the service running, since it never changes on the fly.
                        IconButton(onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("PAC URL", PacGenerator.pacUrl(config.pacIp, config.pacPort))
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.pac_url_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.copy_pac_url),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )

            }

            Spacer(modifier = Modifier.height(12.dp))
            }
            item(key = "forward") {
            // Forward Remote Port Section — above Expose: forwarding is the
            // more common direction, so it leads.
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
                    FilledTonalButton(
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
            }
            item(key = "expose") {
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
                    FilledTonalButton(
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
            }
            item(key = "powerSave") {
            // Power Save Section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    // One hint line per blocker so the user sees exactly what
                    // is disabling Power Save: exposed ports, multicast
                    // announce, Listen — in any combination.
                    val powerSaveBlockers = buildList {
                        if (config.hasActiveExposedPorts()) add(R.string.power_save_exposed_hint)
                        if (config.multicastBeacon) add(R.string.power_save_multicast_hint)
                        if (config.hasActiveListen()) add(R.string.power_save_listen_hint)
                    }
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
                            enabled = !isServiceRunning && powerSaveBlockers.isEmpty(),
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    if (powerSaveBlockers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        powerSaveBlockers.forEach { hintRes ->
                            Text(
                                text = stringResource(hintRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (showHowItWorks) {
                        PowerSaveHowItWorksDialog(onDismiss = { showHowItWorks = false })
                    }

                    if (config.powerSaveEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))

                        var showIdleTimeoutDialog by remember { mutableStateOf(false) }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            PowerSaveToggleRow(
                                label = stringResource(R.string.power_save_sleep_ports_idle),
                                checked = config.powerSaveSleepOnPortsIdle,
                                onCheckedChange = { viewModel.setPowerSaveSleepOnPortsIdle(it) },
                                enabled = !isServiceRunning
                            ) {
                                TextButton(
                                    onClick = { showIdleTimeoutDialog = true },
                                    enabled = !isServiceRunning,
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text(
                                        text = formatMinutesSeconds(config.powerSaveIdleTimeoutSeconds),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            PowerSaveToggleRow(
                                label = stringResource(R.string.power_save_wake_ports_active),
                                checked = config.powerSaveWakeOnPortsActive,
                                onCheckedChange = { viewModel.setPowerSaveWakeOnPortsActive(it) },
                                enabled = !isServiceRunning
                            )
                            PowerSaveToggleRow(
                                label = stringResource(R.string.power_save_sleep_screen_off),
                                checked = config.powerSaveSleepDuringScreenOff,
                                onCheckedChange = { viewModel.setPowerSaveSleepDuringScreenOff(it) },
                                enabled = !isServiceRunning
                            )
                            PowerSaveToggleRow(
                                label = stringResource(R.string.power_save_wake_screen_on),
                                checked = config.powerSaveWakeOnScreenOn,
                                onCheckedChange = { viewModel.setPowerSaveWakeOnScreenOn(it) },
                                enabled = !isServiceRunning
                            )
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
            }
            item(key = "yggdrasilConf") {
            // Yggdrasil.conf card: private key, group password and MaxBackoff,
            // collapsed by default; expansion state is persisted
            val yggdrasilConfExpanded by viewModel.yggdrasilConfExpanded.collectAsStateWithLifecycle()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setYggdrasilConfExpanded(!yggdrasilConfExpanded) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Yggdrasil.conf",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp)
                        )
                        Icon(
                            imageVector = if (yggdrasilConfExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    if (yggdrasilConfExpanded) {
                        OutlinedTextField(
                            value = config.privateKey,
                            onValueChange = { viewModel.updatePrivateKey(it) },
                            label = { Text(stringResource(R.string.private_key_section)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
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
                            Checkbox(
                                checked = config.groupPasswordEnabled,
                                onCheckedChange = { viewModel.setGroupPasswordEnabled(it) },
                                enabled = !isServiceRunning &&
                                        (config.groupPasswordEnabled || config.groupPassword.isNotBlank()),
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }

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
                                Checkbox(
                                    checked = config.maxBackoffEnabled,
                                    onCheckedChange = { viewModel.setMaxBackoffEnabled(it) },
                                    enabled = !isServiceRunning
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

                        // Listen (inbound peering): same row pattern as the
                        // proxy card's address rows; entries are managed on a
                        // dedicated screen opened on tap. With no entries yet,
                        // the tick also opens that screen — a ticked-but-empty
                        // Listen would silently do nothing.
                        ProxySettingRow(
                            label = stringResource(R.string.listen_title),
                            value = config.listenEntries.joinToString(", ") { it.toUri() }
                                .ifBlank { stringResource(R.string.listen_row_hint) },
                            checked = config.listenEnabled,
                            onCheckedChange = {
                                if (config.listenEntries.isEmpty()) showListenScreen = true
                                else viewModel.setListenEnabled(it)
                            },
                            enabled = !isServiceRunning,
                            onClick = { showListenScreen = true }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            }
        // Log Level card removed — logging is controlled from the
        // Diagnostics "Logs" tab (level dropdown incl. Disabled).
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
        val repository = remember { ConfigRepository(context) }
        val discoveryViewModel: PeerDiscoveryViewModel = viewModel(
            factory = PeerDiscoveryViewModel.Factory(repository)
        )
        
        PeerDiscoveryScreen(
            viewModel = discoveryViewModel,
            onDismiss = { showPeerDiscovery = false },
            // Stay on the discovery screen: the dialog opens on top, and its
            // ADD lands in config.peers — which the discovery screen mirrors
            // as a checked (custom) peer.
            onAddPeer = {
                editingPeer = null
                showPeerDialog = true
            }
        )
    }

    if (showListenScreen) {
        ListenScreen(
            viewModel = viewModel,
            onDismiss = { showListenScreen = false }
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
    helpContentDescription: String? = null,
    onHelpClick: (() -> Unit)? = null,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (helpContentDescription != null && onHelpClick != null) {
                        IconButton(
                            onClick = onHelpClick,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HelpOutline,
                                contentDescription = helpContentDescription,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
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

/**
 * One of the Yggdrasil Proxy card's clickable setting rows: label with the
 * current value beneath it, optional trailing content, and an optional
 * enable tick at the far right (absent for rows without an on/off state,
 * e.g. DNS). Tapping the row opens the setting's edit dialog.
 */
@Composable
fun ProxySettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val contentColor = if (checked == false) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
        if (checked != null && onCheckedChange != null) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

/**
 * Copy button for a proxy row's address — same style as the PAC URL copy:
 * works even while the service runs, since the address never changes on the
 * fly.
 */
@Composable
private fun CopyAddressButton(context: Context, address: String) {
    IconButton(onClick = {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Proxy address", address))
        Toast.makeText(
            context,
            context.getString(R.string.copied_to_clipboard),
            Toast.LENGTH_SHORT
        ).show()
    }) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = stringResource(R.string.copy_address),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Edits a proxy listen address as separate IP and Port fields. Accepts local
 * IPv4 addresses (with first-tap suggestions via [LocalIpTextField]) plus
 * `::1`; IPv6 hosts are re-bracketed when combined into `host:port`.
 */
@Composable
fun ProxyAddressDialog(
    title: String,
    initialAddress: String,
    defaultPort: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var ip by remember {
        mutableStateOf(initialAddress.substringBeforeLast(':').removeSurrounding("[", "]"))
    }
    var port by remember {
        mutableStateOf(initialAddress.substringAfterLast(':', defaultPort.toString()))
    }
    var ipError by remember { mutableStateOf(false) }

    fun validateIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    fun validatePort(port: String): Boolean {
        val portNum = port.toIntOrNull() ?: return false
        return portNum in 1..65535
    }

    val portValid = validatePort(port)
    // 1..1024 stays accepted (rooted devices can bind it) — only a soft warning
    val privilegedPort = isPrivilegedPort(port.toIntOrNull() ?: 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                LocalIpTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        ipError = it.isNotEmpty() && !validateIPv4(it) && it != "::1"
                    },
                    label = { Text(stringResource(R.string.ip_label)) },
                    placeholder = { Text("127.0.0.1") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = ipError,
                    supportingText = if (ipError) {
                        { Text("Invalid IP address") }
                    } else null
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.port_label)) },
                    placeholder = { Text("1025-65535") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = port.isNotEmpty() && !portValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = when {
                        port.isNotEmpty() && !portValid ->
                            ({ Text("Port must be between 1-65535") })
                        privilegedPort -> ({
                            Text(stringResource(R.string.port_below_1025_warning),
                                color = PrivilegedPortWarning)
                        })
                        else -> null
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val host = if (ip.contains(':')) "[$ip]" else ip
                    onConfirm("$host:$port")
                },
                enabled = ip.isNotEmpty() && !ipError && portValid
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

/**
 * Edits the DNS server address(es). The primary field has a first-tap
 * suggestion dropdown of well-known Yggdrasil DNS servers; both fields
 * accept `[IPv6]:53`, `host:port` or a bare host (normalized with the
 * default DNS port on confirm). The optional secondary server is only
 * queried when the primary is unreachable or silent.
 */
@Composable
fun DnsServerDialog(
    initialPrimary: String,
    initialSecondary: String,
    onDismiss: () -> Unit,
    onConfirm: (primary: String, secondary: String) -> Unit
) {
    var primary by remember { mutableStateOf(initialPrimary) }
    var secondary by remember { mutableStateOf(initialSecondary) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dns_server)) },
        text = {
            Column {
                LocalIpTextField(
                    value = primary,
                    onValueChange = { primary = it },
                    label = { Text(stringResource(R.string.dns_server)) },
                    placeholder = { Text(stringResource(R.string.dns_server_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    suggestionsProvider = ::dnsServerSuggestions,
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
                LocalIpTextField(
                    value = secondary,
                    onValueChange = { secondary = it },
                    label = { Text(stringResource(R.string.dns_server_2)) },
                    placeholder = { Text(stringResource(R.string.dns_server_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    suggestionsProvider = ::dnsServerSuggestions
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ConfigRepository.normalizeDnsServer(primary),
                        if (secondary.isBlank()) "" else ConfigRepository.normalizeDnsServer(secondary)
                    )
                }
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

/**
 * Edits the PAC server settings: the address advertised in the PAC URL (the
 * server binds it), the listen port, and the all-traffic mode tick.
 */
@Composable
fun PacServerDialog(
    initialIp: String,
    initialPort: Int,
    initialAllTraffic: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ip: String, port: Int, allTraffic: Boolean) -> Unit
) {
    var ip by remember { mutableStateOf(initialIp) }
    var port by remember { mutableStateOf(initialPort.toString()) }
    var allTraffic by remember { mutableStateOf(initialAllTraffic) }
    var ipError by remember { mutableStateOf(false) }

    fun validateIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    val portValid = port.toIntOrNull()?.let { it in 1..65535 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.pac_server))
                Text(
                    text = stringResource(R.string.pac_server_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                LocalIpTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        ipError = it.isNotEmpty() && !validateIPv4(it)
                    },
                    label = { Text(stringResource(R.string.ip_label)) },
                    placeholder = { Text("127.0.0.1") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = ipError,
                    supportingText = if (ipError) {
                        { Text("Invalid IP address") }
                    } else null
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.pac_port)) },
                    placeholder = { Text(stringResource(R.string.pac_port_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = port.isNotEmpty() && !portValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = when {
                        port.isNotEmpty() && !portValid ->
                            ({ Text("Port must be between 1-65535") })
                        isPrivilegedPort(port.toIntOrNull() ?: 0) -> ({
                            Text(stringResource(R.string.port_below_1025_warning),
                                color = PrivilegedPortWarning)
                        })
                        else -> null
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Traffic mode toggle: the description states the active mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { allTraffic = !allTraffic },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            if (allTraffic) R.string.pac_traffic_mode_all
                            else R.string.pac_traffic_mode_ygg
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = allTraffic,
                        onCheckedChange = { allTraffic = it },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(ip, port.toInt(), allTraffic) },
                enabled = ip.isNotEmpty() && !ipError && portValid
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
    var note by remember { mutableStateOf(fill?.note ?: "") }

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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Protocol selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ProtocolOption(
                            label = stringResource(R.string.protocol_tcp),
                            selected = protocol == Protocol.TCP,
                            onClick = { protocol = Protocol.TCP }
                        )
                        ProtocolOption(
                            label = stringResource(R.string.protocol_udp),
                            selected = protocol == Protocol.UDP,
                            onClick = { protocol = Protocol.UDP }
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
                    placeholder = { Text("1025-65535") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = localPortError || localConflict,
                    supportingText = when {
                        localPortError -> { { Text("Port must be between 1-65535") } }
                        localConflict -> { { Text("Already in use by another mapping") } }
                        isPrivilegedPort(localPort.toIntOrNull() ?: 0) -> ({
                            Text(stringResource(R.string.port_below_1025_warning),
                                color = PrivilegedPortWarning)
                        })
                        else -> null
                    }
                )

                LocalIpTextField(
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

                NoteUrlPreview(note = note)

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.mapping_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            val allValid = localPort.isNotEmpty() && localIp.isNotEmpty() && yggPort.isNotEmpty() &&
                    !localPortError && !localIpError && !yggPortError
            TextButton(
                onClick = {
                    if (validatePort(localPort) && validateIPv4(localIp) && validatePort(yggPort)) {
                        onConfirm(ExposeMapping(protocol, localPort.toInt(), localIp, yggPort.toInt(), shortName.trim(), note.trim(), enabled = !localConflict))
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
                            if (note.isNotBlank()) append("&note=").append(Uri.encode(note.trim()))
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
    var note by remember { mutableStateOf(fill?.note ?: "") }

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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Protocol selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ProtocolOption(
                            label = stringResource(R.string.protocol_tcp),
                            selected = protocol == Protocol.TCP,
                            onClick = { protocol = Protocol.TCP }
                        )
                        ProtocolOption(
                            label = stringResource(R.string.protocol_udp),
                            selected = protocol == Protocol.UDP,
                            onClick = { protocol = Protocol.UDP }
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

                LocalIpTextField(
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
                    placeholder = { Text("1025-65535") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = localPortError || localConflict,
                    supportingText = when {
                        localPortError -> { { Text("Port must be between 1-65535") } }
                        localConflict -> { { Text("Already in use by another mapping") } }
                        isPrivilegedPort(localPort.toIntOrNull() ?: 0) -> ({
                            Text(stringResource(R.string.port_below_1025_warning),
                                color = PrivilegedPortWarning)
                        })
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

                NoteUrlPreview(note = note)

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.mapping_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
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
                        onConfirm(ForwardMapping(protocol, localIp, localPort.toInt(), remoteIp, remotePort.toInt(), shortName.trim(), note.trim(), enabled = !localConflict))
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
                            if (note.isNotBlank()) append("&note=").append(Uri.encode(note.trim()))
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

/**
 * One of Power Save's small event options (checkbox row, same tick style as
 * the items in the Peers / Expose / Forward cards): bodyMedium label, optional
 * inline content between the label and the tick (the idle-timeout value on
 * the "Sleep on ports idle" row).
 */
@Composable
private fun PowerSaveToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
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
fun ProxyHowItWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proxy_how_it_works_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = linkifiedBody(stringResource(R.string.proxy_how_it_works_body)),
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

private val URL_PATTERN = Regex("""https?://\S+""")

// Horizontal bullet selector for the mapping protocol (TCP/UDP), in the same
// RadioButton-with-label style as SettingsScreen's theme options
@Composable
private fun ProtocolOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

// URLs typed into a mapping Note, in order of first appearance: leading
// http(s) match with trailing punctuation (e.g. a sentence-final dot) trimmed.
internal fun extractNoteUrls(note: String): List<String> =
    URL_PATTERN.findAll(note)
        .map { it.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'') }
        .filter { it.length > "https://".length }
        .distinct()
        .toList()

// Live preview of the http(s) URLs inside a mapping Note, shown as tappable
// links between the Short Name and Note fields while editing. Mirrors
// linkifiedBody's styling; disappears entirely when the Note has no URL.
@Composable
internal fun NoteUrlPreview(note: String) {
    val context = LocalContext.current
    val urls = remember(note) { extractNoteUrls(note) }
    if (urls.isEmpty()) return
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline
        )
    )
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        urls.forEach { url ->
            Text(
                text = buildAnnotatedString {
                    withLink(
                        LinkAnnotation.Url(url, linkStyles) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    ) {
                        append(url)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }
    }
}

// Turns plain http(s) URLs inside a localized string into tappable links,
// so translations keep working without resource restructuring. Trailing
// punctuation (e.g. the ")" after a parenthesized URL) stays plain text.
@Composable
private fun linkifiedBody(text: String): AnnotatedString {
    val context = LocalContext.current
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline
        )
    )
    return buildAnnotatedString {
        var lastEnd = 0
        for (match in URL_PATTERN.findAll(text)) {
            val url = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
            val start = match.range.first
            append(text.substring(lastEnd, start))
            withLink(
                LinkAnnotation.Url(url, linkStyles) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            ) {
                append(url)
            }
            lastEnd = start + url.length
        }
        append(text.substring(lastEnd))
    }
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

