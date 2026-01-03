package link.yggdrasil.yggstack.android.data.peer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata for peer cache associated with external IP
 */
@Entity(tableName = "peer_cache_metadata")
data class PeerCacheMetadata(
    @PrimaryKey
    val externalIp: String,
    
    val sortingType: SortingType,
    val sortedAt: Long,
    val totalPeersChecked: Int,
    val successfulPeersCount: Int
) {
    /**
     * Check if sorting is fresh (less than 24 hours old)
     */
    fun isFresh(maxAgeMs: Long = 24 * 60 * 60 * 1000): Boolean {
        return System.currentTimeMillis() - sortedAt < maxAgeMs
    }
    
    /**
     * Get success rate percentage
     */
    fun getSuccessRate(): Float {
        if (totalPeersChecked == 0) return 0f
        return (successfulPeersCount.toFloat() / totalPeersChecked) * 100
    }
}
