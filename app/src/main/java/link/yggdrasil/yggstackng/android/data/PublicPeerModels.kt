package link.yggdrasil.yggstackng.android.data

import kotlinx.serialization.Serializable

/**
 * Information about a public peer from publicpeers list
 */
@Serializable
data class PublicPeerInfo(
    val uri: String,
    val country: String,
    val rtt: Long? = null,  // RTT in milliseconds (via protocol connect), null if not checked
    val lastChecked: Long? = null  // Timestamp of last RTT check
)

/**
 * Global cache of all public peers (single source for all IPs)
 */
@Serializable
data class PublicPeersCache(
    val peers: List<PublicPeerInfo> = emptyList(),
    val downloadedAt: Long? = null
)

/**
 * Sorted lists organized by external IP address
 */
@Serializable
data class SortedPeersCache(
    val sortedByIp: Map<String, PeerListForIp> = emptyMap()
)

/**
 * List of peers sorted for a specific external IP
 */
@Serializable
data class PeerListForIp(
    val peers: List<PublicPeerInfo>,  // Full peer objects with RTT data for this IP
    val sortedAt: Long,
    val sortType: String  // "connect" or "unsorted"
)

/**
 * Models for deserializing publicnodes.json
 */
@Serializable
data class PublicNodesJson(
    val countries: Map<String, Map<String, PeerNodeInfo>>
)

@Serializable
data class PeerNodeInfo(
    val key: String? = null,
    val response_ms: Long? = null,
    val states: String? = null
)
