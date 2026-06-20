package link.yggdrasil.yggstack.android.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

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
    val groupPasswordEnabled: Boolean = false,
    val groupPassword: String = "",
    val cachedPeers: List<CachedPeer> = emptyList(),  // Dynamically discovered peers cache
    val maxBackoffEnabled: Boolean = true,
    val maxBackoff: Int = 5,  // Maximum backoff time in seconds for peer reconnection (5-30s)
    val disabledPeers: List<String> = emptyList()  // Peers that have been manually disabled
)

/**
 * Cached peer information for fast reconnection
 */
@Serializable
data class CachedPeer(
    val uri: String,              // Peer URI (e.g., "tcp://[fe80::1]:1234")
    val discoverySource: String,  // "multicast" or "dynamic"
    val lastSeen: Long,           // Timestamp when last connected
    val successCount: Int = 0,    // Number of successful connections
    val failureCount: Int = 0     // Number of failed connection attempts
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
    val yggPort: Int,
    val shortName: String = "",
    val enabled: Boolean = true
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
    val remotePort: Int,
    val shortName: String = "",
    val enabled: Boolean = true
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
    val cost: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val uptime: Double,
    val latency: Long
)

