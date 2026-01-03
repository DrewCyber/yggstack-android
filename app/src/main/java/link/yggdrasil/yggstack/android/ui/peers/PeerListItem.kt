package link.yggdrasil.yggstack.android.ui.peers

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import link.yggdrasil.yggstack.android.data.peer.PublicPeer

/**
 * List item component for displaying a peer
 */
@Composable
fun PeerListItem(
    peer: PublicPeer,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() }
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Peer information
            Column(modifier = Modifier.weight(1f)) {
                // Country and protocol
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = peer.country,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    SuggestionChip(
                        onClick = { },
                        label = {
                            Text(
                                peer.protocol.name,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        enabled = false
                    )
                    
                    if (peer.isManuallyAdded) {
                        SuggestionChip(
                            onClick = { },
                            label = {
                                Text(
                                    "Manual",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            enabled = false
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // URI
                Text(
                    text = peer.uri,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // RTT information
                if (peer.isChecked()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        peer.pingMs?.let {
                            Text(
                                "Ping: ${it}ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        peer.connectRtt?.let {
                            Text(
                                "RTT: ${it}ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
            
            // Availability indicator
            if (peer.isChecked() && peer.getBestRtt() != null) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Available",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
