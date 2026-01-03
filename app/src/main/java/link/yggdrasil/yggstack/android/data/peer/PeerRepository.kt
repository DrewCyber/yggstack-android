package link.yggdrasil.yggstack.android.data.peer

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Repository for peer data management
 */
class PeerRepository(context: Context) {
    
    private val database = PeerDatabase.getDatabase(context)
    private val peerDao = database.peerDao()
    private val sortedListDao = database.sortedListDao()
    private val metadataDao = database.metadataDao()
    
    // ========== Peer Operations ==========
    
    /**
     * Get all peers
     */
    fun getAllPeers(): Flow<List<PublicPeer>> = peerDao.getAllPeers()
    
    /**
     * Get peers by protocol filter
     */
    fun getPeersByProtocol(protocols: List<PeerProtocol>): Flow<List<PublicPeer>> {
        return peerDao.getPeersByProtocol(protocols)
    }
    
    /**
     * Get manually added peers
     */
    fun getManualPeers(): Flow<List<PublicPeer>> = peerDao.getManualPeers()
    
    /**
     * Get peers by country
     */
    fun getPeersByCountry(country: String): Flow<List<PublicPeer>> {
        return peerDao.getPeersByCountry(country)
    }
    
    /**
     * Get checked peers with RTT data
     */
    fun getCheckedPeers(): Flow<List<PublicPeer>> = peerDao.getCheckedPeers()
    
    /**
     * Get single peer by URI
     */
    suspend fun getPeerByUri(uri: String): PublicPeer? = peerDao.getPeerByUri(uri)
    
    /**
     * Insert or update peers
     */
    suspend fun insertPeers(peers: List<PublicPeer>) {
        peerDao.insertPeers(peers)
    }
    
    /**
     * Insert or update single peer
     */
    suspend fun insertPeer(peer: PublicPeer) {
        peerDao.insertPeer(peer)
    }
    
    /**
     * Update peer data
     */
    suspend fun updatePeer(peer: PublicPeer) {
        peerDao.updatePeer(peer)
    }
    
    /**
     * Update multiple peers
     */
    suspend fun updatePeers(peers: List<PublicPeer>) {
        peerDao.updatePeers(peers)
    }
    
    /**
     * Clear non-manual peers (for cache refresh)
     */
    suspend fun clearNonManualPeers() {
        peerDao.clearNonManualPeers()
    }
    
    /**
     * Clear all peers
     */
    suspend fun clearAllPeers() {
        peerDao.clearAllPeers()
    }
    
    /**
     * Delete specific peer
     */
    suspend fun deletePeer(uri: String) {
        peerDao.deletePeerByUri(uri)
    }
    
    /**
     * Get peers count
     */
    suspend fun getPeersCount(): Int = peerDao.getPeersCount()
    
    /**
     * Get checked peers count
     */
    suspend fun getCheckedPeersCount(): Int = peerDao.getCheckedPeersCount()
    
    // ========== Sorted List Operations ==========
    
    /**
     * Get sorted peer list for external IP
     */
    suspend fun getSortedListForIp(externalIp: String): List<SortedPeerList> {
        return sortedListDao.getSortedListForIp(externalIp)
    }
    
    /**
     * Get sorted peer list as Flow
     */
    fun getSortedListForIpFlow(externalIp: String): Flow<List<SortedPeerList>> {
        return sortedListDao.getSortedListForIpFlow(externalIp)
    }
    
    /**
     * Get latest sorted list (limited)
     */
    suspend fun getLatestSortedList(externalIp: String, limit: Int = 100): List<SortedPeerList> {
        return sortedListDao.getLatestSortedList(externalIp, limit)
    }
    
    /**
     * Save sorted peer list
     */
    suspend fun saveSortedList(
        externalIp: String,
        peers: List<PublicPeer>,
        sortingType: SortingType
    ) {
        // Delete old list for this IP
        sortedListDao.deleteSortedListForIp(externalIp)
        
        // Create new sorted list
        val sortedList = peers.mapIndexed { index, peer ->
            SortedPeerList(
                externalIp = externalIp,
                peerUri = peer.uri,
                sortIndex = index,
                sortingType = sortingType,
                sortedAt = System.currentTimeMillis()
            )
        }
        
        // Insert new list
        sortedListDao.insertSortedList(sortedList)
        
        // Update metadata
        val metadata = PeerCacheMetadata(
            externalIp = externalIp,
            sortingType = sortingType,
            sortedAt = System.currentTimeMillis(),
            totalPeersChecked = peers.size,
            successfulPeersCount = peers.count { it.isChecked() && it.getBestRtt() != null }
        )
        metadataDao.insertMetadata(metadata)
    }
    
    /**
     * Delete sorted list for external IP
     */
    suspend fun deleteSortedListForIp(externalIp: String) {
        sortedListDao.deleteSortedListForIp(externalIp)
        metadataDao.deleteMetadata(externalIp)
    }
    
    /**
     * Clear all sorted lists
     */
    suspend fun clearAllSortedLists() {
        sortedListDao.deleteAllSortedLists()
        metadataDao.deleteAllMetadata()
    }
    
    // ========== Metadata Operations ==========
    
    /**
     * Get metadata for external IP
     */
    suspend fun getMetadata(externalIp: String): PeerCacheMetadata? {
        return metadataDao.getMetadata(externalIp)
    }
    
    /**
     * Get metadata as Flow
     */
    fun getMetadataFlow(externalIp: String): Flow<PeerCacheMetadata?> {
        return metadataDao.getMetadataFlow(externalIp)
    }
    
    /**
     * Get all metadata
     */
    fun getAllMetadata(): Flow<List<PeerCacheMetadata>> {
        return metadataDao.getAllMetadata()
    }
    
    // ========== Helper Operations ==========
    
    /**
     * Get best peers from sorted list
     * Returns actual PublicPeer objects with full data
     */
    suspend fun getBestPeers(externalIp: String, limit: Int = 10): List<PublicPeer> {
        val sortedList = getLatestSortedList(externalIp, limit)
        val allPeers = getAllPeers().first()
        
        return sortedList.mapNotNull { sortedEntry ->
            allPeers.find { it.uri == sortedEntry.peerUri }
        }
    }
    
    /**
     * Get best peer from sorted list
     */
    suspend fun getBestPeer(externalIp: String): PublicPeer? {
        val bestPeers = getBestPeers(externalIp, 1)
        return bestPeers.firstOrNull()
    }
    
    /**
     * Check if sorted list exists and is fresh
     */
    suspend fun hasFreshSortedList(externalIp: String, maxAgeMs: Long = 24 * 60 * 60 * 1000): Boolean {
        val metadata = getMetadata(externalIp) ?: return false
        return metadata.isFresh(maxAgeMs)
    }
}
