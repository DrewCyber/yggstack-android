package link.yggdrasil.yggstack.android.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import link.yggdrasil.yggstack.android.data.peer.PeerSelectionMode

/**
 * Configuration model for Yggstack
 */
@Serializable
data class YggstackConfig(
    val peers: List<String> = emptyList(),
    val privateKey: String = "",
    val socksProxy: String = "",
    val dnsServer: String = "",
    val proxyEnabled: Boolean = false,
    val exposeMappings: List<ExposeMapping> = emptyList(),
    val exposeEnabled: Boolean = false,
    val forwardMappings: List<ForwardMapping> = emptyList(),
    val forwardEnabled: Boolean = false,
    val multicastBeacon: Boolean = false,
    val multicastListen: Boolean = false,
    val logLevel: String = "info",
    
    // Peer discovery settings
    val peerSelectionMode: PeerSelectionMode = PeerSelectionMode.CUSTOM,
    val lastAutoSelectedPeer: String? = null
)

/**
 * Mapping for exposing local ports to Yggdrasil network
 */
@Parcelize
@Serializable
data class ExposeMapping(
    val protocol: Protocol,
    val localPort: Int,
    val localIp: String = "127.0.0.1",
    val yggPort: Int
) : Parcelable

/**
 * Mapping for forwarding remote Yggdrasil ports to local
 */
@Parcelize
@Serializable
data class ForwardMapping(
    val protocol: Protocol,
    val localIp: String,
    val localPort: Int,
    val remoteIp: String,
    val remotePort: Int
) : Parcelable

/**
 * Network protocol type
 */
@Parcelize
@Serializable
enum class Protocol : Parcelable {
    TCP, UDP
}

data class PeerDetail(
    val uri: String,
    val up: Boolean,
    val inbound: Boolean,
    val port: Long,
    val priority: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val uptime: Double,
    val latency: Long
)

