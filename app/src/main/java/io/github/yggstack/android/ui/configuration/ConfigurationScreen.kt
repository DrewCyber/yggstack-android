package io.github.yggstack.android.ui.configuration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yggstack.android.R
import io.github.yggstack.android.data.*

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
    val clipboardManager = LocalClipboardManager.current

    var peerInput by remember { mutableStateOf("") }
    var showExposeDialog by remember { mutableStateOf(false) }
    var showForwardDialog by remember { mutableStateOf(false) }

    val isServiceRunning = serviceState is ServiceState.Running

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 80.dp) // Space for button at bottom
        ) {
            // Private Key Section
            ConfigSection(title = stringResource(R.string.private_key_section)) {
                OutlinedTextField(
                    value = config.privateKey,
                    onValueChange = { viewModel.updatePrivateKey(it) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isServiceRunning,
                    visualTransformation = if (showPrivateKey) VisualTransformation.None else PasswordVisualTransformation(),
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

            Spacer(modifier = Modifier.height(16.dp))

            // Peers Section
            ConfigSection(title = stringResource(R.string.peers_section)) {
                config.peers.forEach { peer ->
                    PeerItem(
                        peer = peer,
                        enabled = !isServiceRunning,
                        onDelete = { viewModel.removePeer(peer) }
                    )
                }

                if (!isServiceRunning) {
                    OutlinedTextField(
                        value = peerInput,
                        onValueChange = { peerInput = it },
                        label = { Text(stringResource(R.string.peer_uri_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (peerInput.isNotBlank()) {
                                    viewModel.addPeer(peerInput)
                                    peerInput = ""
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_peer))
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

            // Proxy Configuration Section
            ConfigSectionWithToggle(
                title = stringResource(R.string.proxy_config_section),
                enabled = config.proxyEnabled,
                onToggle = { viewModel.toggleProxyEnabled() }
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
                onToggle = { viewModel.toggleExposeEnabled() }
            ) {
                config.exposeMappings.forEach { mapping ->
                    ExposeMappingItem(
                        mapping = mapping,
                        enabled = !isServiceRunning && config.exposeEnabled,
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
                onToggle = { viewModel.toggleForwardEnabled() }
            ) {
                config.forwardMappings.forEach { mapping ->
                    ForwardMappingItem(
                        mapping = mapping,
                        enabled = !isServiceRunning && config.forwardEnabled,
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
        ExposeMappingDialog(
            onDismiss = { showExposeDialog = false },
            onConfirm = { mapping ->
                viewModel.addExposeMapping(mapping)
                showExposeDialog = false
            }
        )
    }

    if (showForwardDialog) {
        ForwardMappingDialog(
            onDismiss = { showForwardDialog = false },
            onConfirm = { mapping ->
                viewModel.addForwardMapping(mapping)
                showForwardDialog = false
            }
        )
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
            modifier = Modifier.padding(16.dp)
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
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
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
                    onCheckedChange = { onToggle() }
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_peer))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposeMappingDialog(
    onDismiss: () -> Unit,
    onConfirm: (ExposeMapping) -> Unit
) {
    var protocol by remember { mutableStateOf(Protocol.TCP) }
    var localPort by remember { mutableStateOf("") }
    var localIp by remember { mutableStateOf("127.0.0.1") }
    var yggPort by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_mapping)) },
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
                    onValueChange = { localPort = it },
                    label = { Text(stringResource(R.string.local_port)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = localIp,
                    onValueChange = { localIp = it },
                    label = { Text(stringResource(R.string.local_ip)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = yggPort,
                    onValueChange = { yggPort = it },
                    label = { Text(stringResource(R.string.ygg_port)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    localPort.toIntOrNull()?.let { lp ->
                        yggPort.toIntOrNull()?.let { yp ->
                            onConfirm(ExposeMapping(protocol, lp, localIp, yp))
                        }
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardMappingDialog(
    onDismiss: () -> Unit,
    onConfirm: (ForwardMapping) -> Unit
) {
    var protocol by remember { mutableStateOf(Protocol.TCP) }
    var localIp by remember { mutableStateOf("127.0.0.1") }
    var localPort by remember { mutableStateOf("") }
    var remoteIp by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_mapping)) },
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
                    onValueChange = { localIp = it },
                    label = { Text(stringResource(R.string.local_ip)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = localPort,
                    onValueChange = { localPort = it },
                    label = { Text(stringResource(R.string.local_port)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = remoteIp,
                    onValueChange = { remoteIp = it },
                    label = { Text(stringResource(R.string.remote_ip)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = remotePort,
                    onValueChange = { remotePort = it },
                    label = { Text(stringResource(R.string.remote_port)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    localPort.toIntOrNull()?.let { lp ->
                        remotePort.toIntOrNull()?.let { rp ->
                            onConfirm(ForwardMapping(protocol, localIp, lp, remoteIp, rp))
                        }
                    }
                }
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

