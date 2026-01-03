package link.yggdrasil.yggstack.android.data.peer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for public peers cache
 */
@Dao
interface PeerDao {
    /**
     * Get all peers ordered by addition date (newest first)
     */
    @Query("SELECT * FROM peers_cache ORDER BY addedAt DESC")
    fun getAllPeers(): Flow<List<PublicPeer>>
    
    /**
     * Get peers by specific protocols
     */
    @Query("SELECT * FROM peers_cache WHERE protocol IN (:protocols) ORDER BY addedAt DESC")
    fun getPeersByProtocol(protocols: List<PeerProtocol>): Flow<List<PublicPeer>>
    
    /**
     * Get only manually added peers
     */
    @Query("SELECT * FROM peers_cache WHERE isManuallyAdded = 1 ORDER BY addedAt DESC")
    fun getManualPeers(): Flow<List<PublicPeer>>
    
    /**
     * Get peers by country
     */
    @Query("SELECT * FROM peers_cache WHERE country = :country ORDER BY addedAt DESC")
    fun getPeersByCountry(country: String): Flow<List<PublicPeer>>
    
    /**
     * Get checked peers with RTT data
     */
    @Query("SELECT * FROM peers_cache WHERE lastChecked IS NOT NULL ORDER BY addedAt DESC")
    fun getCheckedPeers(): Flow<List<PublicPeer>>
    
    /**
     * Get peer by URI
     */
    @Query("SELECT * FROM peers_cache WHERE uri = :uri")
    suspend fun getPeerByUri(uri: String): PublicPeer?
    
    /**
     * Insert or replace peers
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeers(peers: List<PublicPeer>)
    
    /**
     * Insert or replace single peer
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PublicPeer)
    
    /**
     * Update peer data
     */
    @Update
    suspend fun updatePeer(peer: PublicPeer)
    
    /**
     * Update multiple peers
     */
    @Update
    suspend fun updatePeers(peers: List<PublicPeer>)
    
    /**
     * Delete non-manual peers (for cache refresh)
     */
    @Query("DELETE FROM peers_cache WHERE isManuallyAdded = 0")
    suspend fun clearNonManualPeers()
    
    /**
     * Delete all peers
     */
    @Query("DELETE FROM peers_cache")
    suspend fun clearAllPeers()
    
    /**
     * Delete specific peer by URI
     */
    @Query("DELETE FROM peers_cache WHERE uri = :uri")
    suspend fun deletePeerByUri(uri: String)
    
    /**
     * Get count of all peers
     */
    @Query("SELECT COUNT(*) FROM peers_cache")
    suspend fun getPeersCount(): Int
    
    /**
     * Get count of checked peers
     */
    @Query("SELECT COUNT(*) FROM peers_cache WHERE lastChecked IS NOT NULL")
    suspend fun getCheckedPeersCount(): Int
}
