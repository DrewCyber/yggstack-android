package link.yggdrasil.yggstack.android.ui.configuration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import link.yggdrasil.yggstack.android.BuildConfig
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.ListenEntry
import link.yggdrasil.yggstack.android.data.ListenScheme
import link.yggdrasil.yggstack.android.data.ServiceState

/**
 * Full-screen editor for the Yggdrasil `Listen` addresses (inbound peering),
 * opened from the Listen row in the Yggdrasil.conf card. Presented as an
 * overlay the same way PeerDiscoveryScreen is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(
    viewModel: ConfigurationViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    // Same edit-gating as the Configuration screen: Stopping is still "running"
    val isServiceRunning = serviceState is ServiceState.Running ||
        serviceState is ServiceState.PowerSaving ||
        serviceState is ServiceState.Stopping

    var showEntryDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<ListenEntry?>(null) }

    BackHandler { onDismiss() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.listen_title)) },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.listen_screen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (config.listenEntries.isEmpty()) {
                Text(
                    text = stringResource(R.string.listen_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            config.listenEntries.forEach { entry ->
                ListenEntryRow(
                    entry = entry,
                    enabled = !isServiceRunning,
                    onEdit = {
                        editingEntry = entry
                        showEntryDialog = true
                    },
                    onDelete = { viewModel.removeListenEntry(entry) }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (!isServiceRunning) {
                Button(
                    onClick = {
                        editingEntry = null
                        showEntryDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.listen_add_address))
                }
            }
        }
    }

    if (showEntryDialog) {
        ListenEntryDialog(
            initialEntry = editingEntry,
            existingEntries = config.listenEntries,
            // New entries start from the port of the most recent entry, if any
            defaultPort = config.listenEntries.lastOrNull()?.port,
            onDismiss = { showEntryDialog = false },
            onConfirm = { entry ->
                val old = editingEntry
                if (old == null) viewModel.addListenEntry(entry)
                else viewModel.updateListenEntry(old, entry)
                showEntryDialog = false
            },
            onDelete = editingEntry?.let { entry ->
                {
                    viewModel.removeListenEntry(entry)
                    showEntryDialog = false
                }
            }
        )
    }
}

@Composable
private fun ListenEntryRow(
    entry: ListenEntry,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.toUri(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = entry.scheme.uri.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Listen address", entry.toUri()))
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy_address),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Edits one Listen entry: scheme picker + bind IP (with the local-IP
 * suggestion dropdown) + port — the same field set and validation as the
 * proxy address dialogs. The ng engine honors only tcp/tls, so the scheme
 * picker is narrowed on that flavor.
 */
@Composable
private fun ListenEntryDialog(
    initialEntry: ListenEntry?,
    existingEntries: List<ListenEntry>,
    defaultPort: Int?,
    onDismiss: () -> Unit,
    onConfirm: (ListenEntry) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var scheme by remember { mutableStateOf(initialEntry?.scheme ?: ListenScheme.TCP) }
    var ip by remember { mutableStateOf(initialEntry?.ip ?: "") }
    var port by remember { mutableStateOf(initialEntry?.port?.toString() ?: defaultPort?.toString() ?: "") }
    var ipError by remember { mutableStateOf(false) }

    fun validateIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    val portNum = port.toIntOrNull()
    val portValid = portNum != null && portNum in 1..65535
    val ipValid = validateIPv4(ip) || ip == "::1"
    val duplicate = existingEntries.any {
        it != initialEntry && it.scheme == scheme && it.ip == ip && it.port.toString() == port
    }

    val availableSchemes = if (BuildConfig.ENGINE_ID == "go") {
        ListenScheme.entries
    } else {
        ListenScheme.entries.filter { it.supportedByNg }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (initialEntry != null) stringResource(R.string.listen_title)
                    else stringResource(R.string.listen_add_address)
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            Column {
                // Scheme picker (chips): all four on go, tcp/tls on ng
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableSchemes.forEach { s ->
                        FilterChip(
                            selected = scheme == s,
                            onClick = { scheme = s },
                            label = { Text(s.uri) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LocalIpTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        ipError = it.isNotEmpty() && !validateIPv4(it) && it != "::1"
                    },
                    label = { Text(stringResource(R.string.ip_label)) },
                    placeholder = { Text("0.0.0.0") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = ipError,
                    supportingText = if (ipError) {
                        { Text(stringResource(R.string.invalid_ip)) }
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
                    isError = (port.isNotEmpty() && !portValid) || duplicate,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = when {
                        duplicate -> ({ Text(stringResource(R.string.listen_port_in_use)) })
                        port.isNotEmpty() && !portValid -> ({ Text(stringResource(R.string.invalid_port)) })
                        isPrivilegedPort(portNum ?: 0) -> ({
                            Text(stringResource(R.string.port_below_1025_warning),
                                color = PrivilegedPortWarning)
                        })
                        else -> null
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(
                        R.string.listen_uri_label
                    ) + ": " + ListenEntry(
                        scheme,
                        if (ip.contains(':')) "[$ip]" else ip,
                        portNum ?: 0
                    ).toUri(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(ListenEntry(scheme, ip, portNum ?: 0)) },
                enabled = ip.isNotEmpty() && ipValid && portValid && !duplicate
            ) {
                Text(if (initialEntry != null) stringResource(R.string.update) else stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
