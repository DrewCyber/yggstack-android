package link.yggdrasil.yggstack.android.service.availability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import link.yggdrasil.yggstack.android.data.peer.PeerProtocol
import link.yggdrasil.yggstack.android.data.peer.PublicPeer

/**
 * Coordinator for peer availability checks
 * Manages parallel checking with concurrency limits
 */
class PeerAvailabilityChecker(
    private val pingChecker: PingChecker,
    private val tcpChecker: TcpConnectChecker,
    private val tlsChecker: TlsConnectChecker,
    private val quicChecker: QuicConnectChecker,
    private val webSocketChecker: WebSocketChecker,
    private val maxConcurrent: Int = 10
) {
    
    private val semaphore = Semaphore(maxConcurrent)
    
    /**
     * Check peers using ping
     * Updates pingMs and lastChecked fields
     */
    suspend fun checkPeersPing(
        peers: List<PublicPeer>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<PublicPeer> = coroutineScope {
        Log.d(TAG, "Starting ping check for ${peers.size} peers")
        
        val results = peers.mapIndexed { index, peer ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val pingTime = pingChecker.checkPing(peer.host)
                    onProgress?.invoke(index + 1, peers.size)
                    
                    peer.copy(
                        pingMs = pingTime,
                        lastChecked = System.currentTimeMillis()
                    )
                }
            }
        }.awaitAll()
        
        val successful = results.count { it.pingMs != null }
        Log.d(TAG, "Ping check complete: $successful/${peers.size} peers reachable")
        
        results
    }
    
    /**
     * Check peers using protocol-specific connect
     * Updates connectRtt and lastChecked fields
     */
    suspend fun checkPeersConnect(
        peers: List<PublicPeer>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<PublicPeer> = coroutineScope {
        Log.d(TAG, "Starting connect check for ${peers.size} peers")
        
        val results = peers.mapIndexed { index, peer ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val rtt = when (peer.protocol) {
                        PeerProtocol.TCP -> tcpChecker.checkConnect(peer.host, peer.port)
                        PeerProtocol.TLS -> tlsChecker.checkConnect(peer.host, peer.port)
                        PeerProtocol.QUIC -> quicChecker.checkConnect(peer.host, peer.port)
                        PeerProtocol.WS, PeerProtocol.WSS -> webSocketChecker.checkConnect(peer.uri)
                    }
                    
                    onProgress?.invoke(index + 1, peers.size)
                    
                    peer.copy(
                        connectRtt = rtt,
                        lastChecked = System.currentTimeMillis()
                    )
                }
            }
        }.awaitAll()
        
        val successful = results.count { it.connectRtt != null }
        Log.d(TAG, "Connect check complete: $successful/${peers.size} peers reachable")
        
        results
    }
    
    /**
     * Check single peer availability (ping only)
     */
    suspend fun checkPeerPing(peer: PublicPeer): PublicPeer {
        val pingTime = pingChecker.checkPing(peer.host)
        return peer.copy(
            pingMs = pingTime,
            lastChecked = System.currentTimeMillis()
        )
    }
    
    /**
     * Check single peer connectivity (protocol-specific)
     */
    suspend fun checkPeerConnect(peer: PublicPeer): PublicPeer {
        val rtt = when (peer.protocol) {
            PeerProtocol.TCP -> tcpChecker.checkConnect(peer.host, peer.port)
            PeerProtocol.TLS -> tlsChecker.checkConnect(peer.host, peer.port)
            PeerProtocol.QUIC -> quicChecker.checkConnect(peer.host, peer.port)
            PeerProtocol.WS, PeerProtocol.WSS -> webSocketChecker.checkConnect(peer.uri)
        }
        
        return peer.copy(
            connectRtt = rtt,
            lastChecked = System.currentTimeMillis()
        )
    }
    
    companion object {
        private const val TAG = "PeerAvailabilityChecker"
    }
}
