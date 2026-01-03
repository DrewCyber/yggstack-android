package link.yggdrasil.yggstack.android.service.sorting

import android.util.Log
import link.yggdrasil.yggstack.android.data.peer.PeerRepository
import link.yggdrasil.yggstack.android.data.peer.PublicPeer
import link.yggdrasil.yggstack.android.data.peer.SortingType
import link.yggdrasil.yggstack.android.service.availability.PeerAvailabilityChecker

/**
 * Service for sorting peers by availability metrics
 */
class PeerSortingService(
    private val availabilityChecker: PeerAvailabilityChecker,
    private val peerRepository: PeerRepository
) {
    
    /**
     * Sort peers by ping response time
     * Updates database with checked peers and saves sorted list
     */
    suspend fun sortByPing(
        externalIp: String,
        peers: List<PublicPeer>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<PublicPeer> {
        Log.d(TAG, "Starting ping sort for ${peers.size} peers")
        
        // Check all peers
        val checkedPeers = availabilityChecker.checkPeersPing(peers, onProgress)
        
        // Update database
        peerRepository.updatePeers(checkedPeers)
        
        // Sort by ping time, then by protocol priority
        val sortedPeers = checkedPeers
            .filter { it.pingMs != null }
            .sortedWith(compareBy({ it.pingMs }, { it.protocol.getPriority() }))
        
        Log.d(TAG, "Ping sort complete: ${sortedPeers.size} reachable peers")
        
        // Save sorted list to database
        peerRepository.saveSortedList(externalIp, sortedPeers, SortingType.PING)
        
        return sortedPeers
    }
    
    /**
     * Sort peers by protocol connect time
     * Updates database with checked peers and saves sorted list
     */
    suspend fun sortByConnect(
        externalIp: String,
        peers: List<PublicPeer>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<PublicPeer> {
        Log.d(TAG, "Starting connect sort for ${peers.size} peers")
        
        // Check all peers
        val checkedPeers = availabilityChecker.checkPeersConnect(peers, onProgress)
        
        // Update database
        peerRepository.updatePeers(checkedPeers)
        
        // Sort by connect RTT, then by protocol priority
        val sortedPeers = checkedPeers
            .filter { it.connectRtt != null }
            .sortedWith(compareBy({ it.connectRtt }, { it.protocol.getPriority() }))
        
        Log.d(TAG, "Connect sort complete: ${sortedPeers.size} reachable peers")
        
        // Save sorted list to database
        peerRepository.saveSortedList(externalIp, sortedPeers, SortingType.CONNECT)
        
        return sortedPeers
    }
    
    /**
     * Quick sort by best available RTT (connect or ping)
     * Does not perform new checks, sorts existing data
     * If no peers have RTT data, returns all peers sorted by protocol priority
     */
    fun sortByBestRtt(peers: List<PublicPeer>): List<PublicPeer> {
        val peersWithRtt = peers.filter { it.getBestRtt() != null }
        
        return if (peersWithRtt.isEmpty()) {
            // No peers have RTT data, return all peers sorted by protocol priority
            peers.sortedBy { it.protocol.getPriority() }
        } else {
            // Sort peers with RTT data
            peersWithRtt.sortedWith(compareBy({ it.getBestRtt() }, { it.protocol.getPriority() }))
        }
    }
    
    /**
     * Sort peers by protocol priority only
     */
    fun sortByProtocolPriority(peers: List<PublicPeer>): List<PublicPeer> {
        return peers.sortedBy { it.protocol.getPriority() }
    }
    
    /**
     * Sort peers alphabetically by country
     */
    fun sortByCountry(peers: List<PublicPeer>): List<PublicPeer> {
        return peers.sortedBy { it.country }
    }
    
    /**
     * Check if external IP has fresh sorted list
     */
    suspend fun hasFreshSortedList(
        externalIp: String,
        maxAgeMs: Long = 24 * 60 * 60 * 1000
    ): Boolean {
        return peerRepository.hasFreshSortedList(externalIp, maxAgeMs)
    }
    
    companion object {
        private const val TAG = "PeerSortingService"
    }
}
