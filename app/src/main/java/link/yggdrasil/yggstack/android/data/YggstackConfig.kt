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
    val httpProxy: String = "",
    // Per-listener enable ticks on the Configuration screen; the address stays
    // saved when unticked so re-ticking restores it (same model as pacEnabled).
    // Default: only the SOCKS5 proxy on (the master proxy toggle itself is off).
    val socksEnabled: Boolean = true,
    val httpEnabled: Boolean = false,
    val dnsServer: String = "",
    // Optional failover DNS server: tried when dnsServer is unreachable or
    // silent (both engines). Blank (the default) keeps the single-server
    // behavior.
    val dnsServer2: String = "",
    val proxyEnabled: Boolean = false,
    val pacEnabled: Boolean = false,
    val pacIp: String = "127.0.0.1",
    val pacPort: Int = 8081,
    val pacAllTraffic: Boolean = false,
    val exposeMappings: List<ExposeMapping> = emptyList(),
    val exposeEnabled: Boolean = false,
    val forwardMappings: List<ForwardMapping> = emptyList(),
    val forwardEnabled: Boolean = false,
    val multicastBeacon: Boolean = false,
    val multicastListen: Boolean = false,
    // Inbound peering (Yggdrasil `Listen`): master tick + saved entries; when
    // unticked the entries stay saved (same model as socksEnabled).
    val listenEnabled: Boolean = false,
    val listenEntries: List<ListenEntry> = emptyList(),
    val logLevel: String = "info",
    val groupPasswordEnabled: Boolean = false,
    val groupPassword: String = "",
    val cachedPeers: List<CachedPeer> = emptyList(),  // Dynamically discovered peers cache
    val maxBackoffEnabled: Boolean = true,
    val maxBackoff: Int = 5,  // Maximum backoff time in seconds for peer reconnection (5-30s)
    val disabledPeers: List<String> = emptyList(),  // Peers that have been manually disabled
    val powerSaveEnabled: Boolean = false,
    val powerSaveIdleTimeoutSeconds: Int = 60,  // 10-120s, idle time before powering down the node
    val powerSaveSleepOnPortsIdle: Boolean = true,
    val powerSaveWakeOnPortsActive: Boolean = true,
    val powerSaveSleepDuringScreenOff: Boolean = false,
    val powerSaveWakeOnScreenOn: Boolean = false
)

/** True if at least one expose mapping would actually accept connections from the Yggdrasil network. */
fun YggstackConfig.hasActiveExposedPorts(): Boolean =
    exposeEnabled && exposeMappings.any { it.enabled }

/** True if the node would accept inbound peer connections on configured Listen sockets. */
fun YggstackConfig.hasActiveListen(): Boolean =
    listenEnabled && listenEntries.isNotEmpty()

/** True if something requires the node to stay reachable from the network,
 *  making Power Save unavailable (exposed ports, multicast announce, Listen). */
fun YggstackConfig.hasAlwaysOnInbound(): Boolean =
    hasActiveExposedPorts() || multicastBeacon || hasActiveListen()

/** True if there is at least one local SOCKS/HTTP proxy or forward mapping Power Save could wake on. */
fun YggstackConfig.hasWakeableTargets(): Boolean =
    (proxyEnabled && ((socksEnabled && socksProxy.isNotBlank()) ||
        (httpEnabled && httpProxy.isNotBlank()))) ||
        (forwardEnabled && forwardMappings.any { it.enabled })

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
    // Free-form multiline note for the mapped service (keys, passwords, description).
    // Plain text only — never sent to the engine; carried in share links and backups.
    val note: String = "",
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
    // Free-form multiline note for the mapped service (keys, passwords, description).
    // Plain text only — never sent to the engine; carried in share links and backups.
    val note: String = "",
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

/**
 * Transport a Listen entry accepts incoming peer connections on. The Go core
 * honors all four; the ng core only TCP and TLS (see [supportedByNg]).
 */
@Parcelize
@Serializable
enum class ListenScheme : Parcelable {
    TCP, TLS, QUIC, WS;

    val uri: String get() = name.lowercase()
    val supportedByNg: Boolean get() = this == TCP || this == TLS
}

/**
 * One Yggdrasil `Listen` address: a socket this node accepts peer
 * connections on, e.g. tcp://0.0.0.0:1234.
 */
@Parcelize
@Serializable
data class ListenEntry(
    val scheme: ListenScheme,
    val ip: String,        // bind address; IPv6 stored unbracketed
    val port: Int
) : Parcelable {
    /** URI form written to the native config; IPv6 hosts are bracketed. */
    fun toUri(): String =
        "${scheme.uri}://" + (if (ip.contains(':')) "[$ip]" else ip) + ":$port"

    companion object {
        private val uriRegex = Regex("""^(tcp|tls|quic|ws)://(\[[^\]]+\]|[^:\s]+):(\d+)$""")

        /** Parse "scheme://[host]:port"; null when malformed or the scheme is unknown. */
        fun fromUri(uri: String): ListenEntry? {
            val m = uriRegex.find(uri.trim()) ?: return null
            val scheme = ListenScheme.entries.firstOrNull { it.uri == m.groupValues[1] } ?: return null
            val port = m.groupValues[3].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return ListenEntry(scheme, m.groupValues[2].removeSurrounding("[", "]"), port)
        }
    }
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

/**
 * Runtime stats for a single listener (SOCKS proxy, forwarded or exposed port),
 * parsed from the Go side's GetListenersJSON. Counters reset when the service stops.
 */
data class PortStatsDetail(
    val key: String,        // listener identity, e.g. "ltcp:127.0.0.1:8080->[300:...]:80"
    val kind: String,       // "socks", "http", "local-tcp", "local-udp", "remote-tcp", "remote-udp"
    val listenAddr: String,
    val targetAddr: String,
    val activeConnections: Long,
    val totalConnections: Long,
    val rxBytes: Long,
    val txBytes: Long
) {
    val isTcp: Boolean get() = kind == "socks" || kind == "http" || kind == "local-tcp" || kind == "remote-tcp"

    /** Section this listener belongs to on the Ports stats page: "proxy", "expose" or "forward". */
    val section: String
        get() = when (kind) {
            "remote-tcp", "remote-udp" -> "expose"
            "local-tcp", "local-udp" -> "forward"
            else -> "proxy"
        }
}

