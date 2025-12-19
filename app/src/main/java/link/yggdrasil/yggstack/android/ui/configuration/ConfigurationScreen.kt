package link.yggdrasil.yggstack.android.ui.configuration

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    viewModel: ConfigurationViewModel,
    modifier: Modifier = Modifier
) {
    val config by viewModel.config.collectAsState()
    val serviceState by viewModel.serviceState.collectAsState()
    val yggdrasilIp by viewModel.yggdrasilIp.collectAsState()
    val showPrivateKey by viewModel.showPrivateKey.collectAsState()
    val savedScrollPosition by viewModel.scrollPosition.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    var peerInput by remember { mutableStateOf("") }
    var editingPeer by remember { mutableStateOf<String?>(null) }
    var showExposeDialog by remember { mutableStateOf(false) }
    var editingExposeMapping by remember { mutableStateOf<ExposeMapping?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var editingForwardMapping by remember { mutableStateOf<ForwardMapping?>(null) }

    val scrollState = rememberScrollState(initial = savedScrollPosition)
    
    // Save scroll position when it changes
    LaunchedEffect(scrollState.value) {
        viewModel.saveScrollPosition(scrollState.value)
    }

    val isServiceRunning = serviceState is ServiceState.Running

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp)
                .padding(bottom = 80.dp) // Space for button at bottom
        ) {
            // App title as part of scrollable content
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // Private Key Section
            ConfigSection(title = stringResource(R.string.private_key_section)) {
                OutlinedTextField(
                    value = config.privateKey,
                    onValueChange = { viewModel.updatePrivateKey(it) },
                    modifier = Modifier.fillMaxWidth(),
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

            // Yggdrasil IP Section
            ConfigSection(title = stringResource(R.string.yggdrasil_ip_section)) {
                OutlinedTextField(
                    value = yggdrasilIp ?: stringResource(R.string.not_connected),
                    onValueChange = { },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    trailingIcon = {
                        if (yggdrasilIp != null) {
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(yggdrasilIp!!))
                            }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.copy_ip))
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Peers Section with clickable header
            val context = LocalContext.current
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                    // Clickable header
                    Surface(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://publicpeers.neilalexander.dev/"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.peers_section),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .weight(1f)
                            )
                            Icon(
                                Icons.Default.Link,
                                contentDescription = "Find public peers",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                            )
                        }
                    }
                    
                    config.peers.forEach { peer ->
                        PeerItem(
                            peer = peer,
                            enabled = !isServiceRunning,
                            onEdit = {
                                editingPeer = peer
                                peerInput = peer
                            },
                            onDelete = { viewModel.removePeer(peer) }
                        )
                    }

                    if (!isServiceRunning) {
                        OutlinedTextField(
                            value = peerInput,
                            onValueChange = { peerInput = it },
                            label = { Text(if (editingPeer != null) "Edit Peer" else stringResource(R.string.peer_uri_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Row {
                                    if (editingPeer != null) {
                                        IconButton(onClick = {
                                            editingPeer = null
                                            peerInput = ""
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                                        }
                                    }
                                    IconButton(onClick = {
                                        if (peerInput.isNotBlank()) {
                                            if (editingPeer != null) {
                                                viewModel.updatePeer(editingPeer!!, peerInput)
                                                editingPeer = null
                                            } else {
                                                viewModel.addPeer(peerInput)
                                            }
                                            peerInput = ""
                                        }
                                    }) {
                                        Icon(
                                            if (editingPeer != null) Icons.Default.Check else Icons.Default.Add,
                                            contentDescription = if (editingPeer != null) "Update" else stringResource(R.string.add_peer)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Multicast Discovery Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.multicast_discovery),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.multicast_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.multicastEnabled,
                            onCheckedChange = { viewModel.setMulticastEnabled(it) },
                            enabled = !isServiceRunning
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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

                OutlinedTextField(
                    value = config.dnsServer,
                    onValueChange = { viewModel.updateDnsServer(it) },
                    label = { Text(stringResource(R.string.dns_server)) },
                    placeholder = { Text(stringResource(R.string.dns_server_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isServiceRunning && config.proxyEnabled
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Expose Local Port Section
            ConfigSectionWithToggle(
                title = stringResource(R.string.expose_local_port_section),
                enabled = config.exposeEnabled,
                onToggle = { viewModel.toggleExposeEnabled() },
                isServiceRunning = isServiceRunning
            ) {
                config.exposeMappings.forEach { mapping ->
                    ExposeMappingItem(
                        mapping = mapping,
                        enabled = !isServiceRunning && config.exposeEnabled,
                        onEdit = {
                            editingExposeMapping = mapping
                            showExposeDialog = true
                        },
                        onDelete = { viewModel.removeExposeMapping(mapping) }
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Forward Remote Port Section
            ConfigSectionWithToggle(
                title = stringResource(R.string.forward_remote_port_section),
                enabled = config.forwardEnabled,
                onToggle = { viewModel.toggleForwardEnabled() },
                isServiceRunning = isServiceRunning
            ) {
                config.forwardMappings.forEach { mapping ->
                    ForwardMappingItem(
                        mapping = mapping,
                        enabled = !isServiceRunning && config.forwardEnabled,
                        onEdit = {
                            editingForwardMapping = mapping
                            showForwardDialog = true
                        },
                        onDelete = { viewModel.removeForwardMapping(mapping) }
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Log Level Section
            ConfigSection(title = stringResource(R.string.log_level)) {
                val logLevels = listOf("error", "warn", "info", "debug")
                val logLevelLabels = mapOf(
                    "error" to stringResource(R.string.log_level_error),
                    "warn" to stringResource(R.string.log_level_warn),
                    "info" to stringResource(R.string.log_level_info),
                    "debug" to stringResource(R.string.log_level_debug)
                )

                Column {
                    Text(
                        text = stringResource(R.string.log_level_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    ) {
                        logLevels.forEachIndexed { index, level ->
                            Button(
                                onClick = { viewModel.setLogLevel(level) },
                                enabled = !isServiceRunning,
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
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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
            colors = if (isServiceRunning) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            } else {
                ButtonDefaults.buttonColors()
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
    if (showExposeDialog) {
        key(showExposeDialog, editingExposeMapping) {
            ExposeMappingDialog(
                initialMapping = editingExposeMapping,
                onDismiss = {
                    showExposeDialog = false
                    editingExposeMapping = null
                },
                onConfirm = { mapping ->
                    if (editingExposeMapping != null) {
                        viewModel.updateExposeMapping(editingExposeMapping!!, mapping)
                        editingExposeMapping = null
                    } else {
                        viewModel.addExposeMapping(mapping)
                    }
                    showExposeDialog = false
                }
            )
        }
    }

    if (showForwardDialog) {
        key(showForwardDialog, editingForwardMapping) {
            ForwardMappingDialog(
                initialMapping = editingForwardMapping,
                onDismiss = {
                    showForwardDialog = false
                    editingForwardMapping = null
                },
                onConfirm = { mapping ->
                    if (editingForwardMapping != null) {
                        viewModel.updateForwardMapping(editingForwardMapping!!, mapping)
                        editingForwardMapping = null
                    } else {
                        viewModel.addForwardMapping(mapping)
                    }
                    showForwardDialog = false
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
            modifier = Modifier.padding(12.dp)
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
            modifier = Modifier.padding(12.dp)
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
                    enabled = !isServiceRunning
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
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = peer,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        if (enabled) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit peer")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_peer))
            }
        }
    }
}

@Composable
fun ExposeMappingItem(
    mapping: ExposeMapping,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${mapping.protocol.name.lowercase()} ${mapping.localPort} ${mapping.localIp} → ${mapping.yggPort}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        if (enabled) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit mapping")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_peer))
            }
        }
    }
}

@Composable
fun ForwardMappingItem(
    mapping: ForwardMapping,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${mapping.protocol.name.lowercase()} ${mapping.localIp}:${mapping.localPort} → [${mapping.remoteIp}]:${mapping.remotePort}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        if (enabled) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit mapping")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_peer))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposeMappingDialog(
    initialMapping: ExposeMapping? = null,
    onDismiss: () -> Unit,
    onConfirm: (ExposeMapping) -> Unit
) {
    var protocol by remember { mutableStateOf(initialMapping?.protocol ?: Protocol.TCP) }
    var localPort by remember { mutableStateOf(initialMapping?.localPort?.toString() ?: "") }
    var localIp by remember { mutableStateOf(initialMapping?.localIp ?: "127.0.0.1") }
    var yggPort by remember { mutableStateOf(initialMapping?.yggPort?.toString() ?: "") }
    
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialMapping != null) "Edit Mapping" else stringResource(R.string.add_mapping)) },
        text = {
            Column {
                // Protocol selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    isError = localPortError,
                    supportingText = if (localPortError) {
                        { Text("Port must be between 1-65535") }
                    } else null
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
                    isError = localIpError,
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (validatePort(localPort) && validateIPv4(localIp) && validatePort(yggPort)) {
                        onConfirm(ExposeMapping(protocol, localPort.toInt(), localIp, yggPort.toInt()))
                    }
                },
                enabled = localPort.isNotEmpty() && localIp.isNotEmpty() && yggPort.isNotEmpty() &&
                        !localPortError && !localIpError && !yggPortError
            ) {
                Text(if (initialMapping != null) "Update" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardMappingDialog(
    initialMapping: ForwardMapping? = null,
    onDismiss: () -> Unit,
    onConfirm: (ForwardMapping) -> Unit
) {
    var protocol by remember { mutableStateOf(initialMapping?.protocol ?: Protocol.TCP) }
    var localIp by remember { mutableStateOf(initialMapping?.localIp ?: "127.0.0.1") }
    var localPort by remember { mutableStateOf(initialMapping?.localPort?.toString() ?: "") }
    var remoteIp by remember { mutableStateOf(initialMapping?.remoteIp ?: "") }
    var remotePort by remember { mutableStateOf(initialMapping?.remotePort?.toString() ?: "") }
    
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialMapping != null) "Edit Mapping" else stringResource(R.string.add_mapping)) },
        text = {
            Column {
                // Protocol selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    isError = localIpError,
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
                    isError = localPortError,
                    supportingText = if (localPortError) {
                        { Text("Port must be between 1-65535") }
                    } else null
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (validatePort(localPort) && validatePort(remotePort) &&
                        (validateIPv4(localIp) || localIp == "::1") && validateIPv6(remoteIp)) {
                        onConfirm(ForwardMapping(protocol, localIp, localPort.toInt(), remoteIp, remotePort.toInt()))
                    }
                },
                enabled = localPort.isNotEmpty() && localIp.isNotEmpty() && 
                        remoteIp.isNotEmpty() && remotePort.isNotEmpty() &&
                        !localPortError && !localIpError && !remoteIpError && !remotePortError
            ) {
                Text(if (initialMapping != null) "Update" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

