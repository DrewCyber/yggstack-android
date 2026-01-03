package link.yggdrasil.yggstack.android.data.peer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sorted peer list entry for specific external IP
 */
@Entity(tableName = "sorted_peer_lists")
data class SortedPeerList(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val externalIp: String,
    val peerUri: String,
    val sortIndex: Int,
    val sortingType: SortingType,
    val sortedAt: Long
)
