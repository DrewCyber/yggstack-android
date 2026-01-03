package link.yggdrasil.yggstack.android.data.peer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for peer cache metadata
 */
@Dao
interface MetadataDao {
    /**
     * Get metadata for specific external IP
     */
    @Query("SELECT * FROM peer_cache_metadata WHERE externalIp = :externalIp")
    suspend fun getMetadata(externalIp: String): PeerCacheMetadata?
    
    /**
     * Get metadata as Flow
     */
    @Query("SELECT * FROM peer_cache_metadata WHERE externalIp = :externalIp")
    fun getMetadataFlow(externalIp: String): Flow<PeerCacheMetadata?>
    
    /**
     * Get all metadata entries
     */
    @Query("SELECT * FROM peer_cache_metadata ORDER BY sortedAt DESC")
    fun getAllMetadata(): Flow<List<PeerCacheMetadata>>
    
    /**
     * Insert or update metadata
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(metadata: PeerCacheMetadata)
    
    /**
     * Delete metadata for specific external IP
     */
    @Query("DELETE FROM peer_cache_metadata WHERE externalIp = :externalIp")
    suspend fun deleteMetadata(externalIp: String)
    
    /**
     * Delete all metadata
     */
    @Query("DELETE FROM peer_cache_metadata")
    suspend fun deleteAllMetadata()
}
