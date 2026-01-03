package link.yggdrasil.yggstack.android.data.peer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for sorted peer lists
 */
@Dao
interface SortedListDao {
    /**
     * Get sorted peer list for specific external IP
     */
    @Query("""
        SELECT * FROM sorted_peer_lists 
        WHERE externalIp = :externalIp 
        ORDER BY sortIndex ASC
    """)
    suspend fun getSortedListForIp(externalIp: String): List<SortedPeerList>
    
    /**
     * Get sorted peer list as Flow
     */
    @Query("""
        SELECT * FROM sorted_peer_lists 
        WHERE externalIp = :externalIp 
        ORDER BY sortIndex ASC
    """)
    fun getSortedListForIpFlow(externalIp: String): Flow<List<SortedPeerList>>
    
    /**
     * Get latest sorted list for external IP
     */
    @Query("""
        SELECT * FROM sorted_peer_lists 
        WHERE externalIp = :externalIp 
        ORDER BY sortedAt DESC, sortIndex ASC
        LIMIT :limit
    """)
    suspend fun getLatestSortedList(externalIp: String, limit: Int = 100): List<SortedPeerList>
    
    /**
     * Insert sorted peer list
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSortedList(list: List<SortedPeerList>)
    
    /**
     * Delete sorted list for specific external IP
     */
    @Query("DELETE FROM sorted_peer_lists WHERE externalIp = :externalIp")
    suspend fun deleteSortedListForIp(externalIp: String)
    
    /**
     * Delete all sorted lists
     */
    @Query("DELETE FROM sorted_peer_lists")
    suspend fun deleteAllSortedLists()
    
    /**
     * Get metadata for sorted list
     */
    @Query("""
        SELECT externalIp, sortingType, MAX(sortedAt) as sortedAt, 
               COUNT(*) as totalPeersChecked, COUNT(*) as successfulPeersCount
        FROM sorted_peer_lists 
        WHERE externalIp = :externalIp
        GROUP BY externalIp, sortingType
    """)
    suspend fun getListMetadata(externalIp: String): PeerCacheMetadata?
}
