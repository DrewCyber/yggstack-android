package link.yggdrasil.yggstackng.android.service

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import link.yggdrasil.yggstackng.android.data.PublicPeerInfo
import uniffi.yggstack_mobile.checkQuicPeer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Service for checking peer availability via TCP connect
 */
class PeerPingerService {
    
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val MAX_CONCURRENT_CHECKS = 10
        
        // Protocol priority for checking (from most reliable to least)
        private val PROTOCOL_PRIORITY = listOf("tcp", "tls", "ws", "wss", "quic")
    }

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    /**
     * Check a single peer via protocol-specific method and measure RTT
     * Supports TCP/TLS/WS/WSS (via TCP connect) and QUIC (via native check)
     */
    suspend fun checkPeer(peer: PublicPeerInfo): PublicPeerInfo = withContext(Dispatchers.IO) {
        val protocol = extractProtocol(peer.uri)
        
        // QUIC requires special handling via native code
        if (protocol == "quic") {
            return@withContext try {
                val rtt = checkQuicPeer(peer.uri)
                if (rtt > 0) {
                    peer.copy(
                        rtt = rtt,
                        lastChecked = System.currentTimeMillis()
                    )
                } else {
                    peer.copy(
                        rtt = null,
                        lastChecked = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                peer.copy(
                    rtt = null,
                    lastChecked = System.currentTimeMillis()
                )
            }
        }
        
        // TCP/TLS/WS/WSS - use TCP connect
        try {
            val (host, port) = parseUri(peer.uri)
            
            val startTime = System.currentTimeMillis()
            val socket = Socket()
            
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val rtt = System.currentTimeMillis() - startTime
                
                peer.copy(
                    rtt = rtt,
                    lastChecked = System.currentTimeMillis()
                )
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            // Connection failed - return peer without RTT
            peer.copy(
                rtt = null,
                lastChecked = System.currentTimeMillis()
            )
        }
    }

    /**
     * Check multiple peers in parallel with limited concurrency
     * Returns list sorted by RTT (fastest first)
     */
    suspend fun checkPeersWithProgress(
        peers: List<PublicPeerInfo>,
        onProgress: (checked: Int, total: Int) -> Unit
    ): List<PublicPeerInfo> = withContext(Dispatchers.IO) {
        if (peers.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<PublicPeerInfo>()
        val channel = Channel<PublicPeerInfo>(MAX_CONCURRENT_CHECKS)
        var checkedCount = 0

        // Launch workers
        val workers = List(MAX_CONCURRENT_CHECKS) {
            launch {
                for (peer in channel) {
                    val result = checkPeer(peer)
                    synchronized(results) {
                        results.add(result)
                        checkedCount++
                        onProgress(checkedCount, peers.size)
                    }
                }
            }
        }

        // Send peers to channel
        launch {
            peers.forEach { channel.send(it) }
            channel.close()
        }

        // Wait for all workers to complete
        workers.forEach { it.join() }

        // Sort by RTT: peers with RTT first (sorted by value), then peers without RTT
        results.sortedWith(compareBy(
            { it.rtt == null },  // nulls last
            { it.rtt }           // sort by RTT value
        ))
    }

    /**
     * Extract protocol from URI (tcp, tls, ws, wss, quic, etc.)
     */
    private fun extractProtocol(uriString: String): String? {
        return try {
            val cleanUri = uriString.split("?")[0]
            val uri = URI(cleanUri)
            uri.scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Select best representative peer from a group based on protocol priority
     * Priority: TCP > TLS > WS > WSS > QUIC > others
     */
    private fun selectRepresentativePeer(peers: List<PublicPeerInfo>): PublicPeerInfo {
        // Try to find peer by protocol priority
        for (protocol in PROTOCOL_PRIORITY) {
            val peer = peers.find { extractProtocol(it.uri) == protocol }
            if (peer != null) return peer
        }
        
        // Fallback to first peer if no priority match
        return peers.first()
    }

    /**
     * Check a peer by trying multiple protocols in priority order
     * Returns the peer with RTT from first successful protocol check
     */
    private suspend fun checkPeerWithProtocolFallback(ipPeers: List<PublicPeerInfo>): PublicPeerInfo? = withContext(Dispatchers.IO) {
        // Group peers by protocol for this IP
        val peersByProtocol = ipPeers.groupBy { extractProtocol(it.uri) }
        
        // Try protocols in priority order
        for (protocol in PROTOCOL_PRIORITY) {
            val protocolPeers = peersByProtocol[protocol]
            if (protocolPeers.isNullOrEmpty()) continue
            
            // Try first peer of this protocol
            val peer = protocolPeers.first()
            val checked = checkPeer(peer)
            
            if (checked.rtt != null) {
                // Success! Return this peer with RTT
                return@withContext checked
            }
        }
        
        // No protocol succeeded, check first available peer to get null RTT
        val firstPeer = ipPeers.first()
        checkPeer(firstPeer)
    }

    /**
     * Check all peers individually with progress tracking
     * Each peer gets its own RTT measurement
     */
    suspend fun checkPeersByHostWithProgress(
        peers: List<PublicPeerInfo>,
        onProgress: (checked: Int, total: Int) -> Unit,
        onIncrementalUpdate: ((List<PublicPeerInfo>) -> Unit)? = null
    ): List<PublicPeerInfo> = withContext(Dispatchers.IO) {
        if (peers.isEmpty()) return@withContext emptyList()
        
        val total = peers.size
        val results = mutableListOf<PublicPeerInfo>()
        val channel = Channel<PublicPeerInfo>(MAX_CONCURRENT_CHECKS)
        var checkedCount = 0
        
        // Launch workers to check each peer individually
        val workers = List(MAX_CONCURRENT_CHECKS) {
            launch {
                for (peer in channel) {
                    val checkedPeer = checkPeer(peer)
                    
                    val updatedList = synchronized(results) {
                        results.add(checkedPeer)
                        checkedCount++
                        
                        // Sort current results by RTT
                        results.sortedWith(compareBy(
                            { it.rtt == null },
                            { it.rtt }
                        ))
                    }
                    
                    // Report progress
                    onProgress(checkedCount, total)
                    
                    // Notify incremental update with sorted list
                    onIncrementalUpdate?.invoke(updatedList)
                }
            }
        }
        
        // Send all peers to workers
        launch {
            peers.forEach { peer ->
                channel.send(peer)
            }
            channel.close()
        }
        
        // Wait for all workers to complete
        workers.forEach { it.join() }
        
        // Return final sorted results
        results.sortedWith(compareBy(
            { it.rtt == null },
            { it.rtt }
        ))
    }

    /**
     * Parse peer URI to extract host and port
     * Handles: tcp://host:port, tls://host:port, quic://host:port, etc.
     */
    private fun parseUri(uriString: String): Pair<String, Int> {
        try {
            // Handle URIs with query parameters (e.g., ?key=...)
            val cleanUri = uriString.split("?")[0]
            
            val uri = URI(cleanUri)
            var host = uri.host ?: throw IllegalArgumentException("No host in URI")
            val port = uri.port
            
            if (port <= 0) {
                throw IllegalArgumentException("Invalid port in URI")
            }
            
            // Remove IPv6 brackets if present
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length - 1)
            }
            
            return Pair(host, port)
        } catch (e: Exception) {
            // Fallback: try to parse manually
            val regex = Regex("""^[a-z]+://\[?([^\]]+)\]?:(\d+)""")
            val match = regex.find(uriString)
            if (match != null) {
                val host = match.groupValues[1]
                val port = match.groupValues[2].toInt()
                return Pair(host, port)
            }
            throw IllegalArgumentException("Cannot parse URI: $uriString", e)
        }
    }
}
