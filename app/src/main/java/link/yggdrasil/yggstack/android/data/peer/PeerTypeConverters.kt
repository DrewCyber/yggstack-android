package link.yggdrasil.yggstack.android.data.peer

import androidx.room.TypeConverter

/**
 * Type converters for Room database
 */
class PeerTypeConverters {
    
    @TypeConverter
    fun fromPeerProtocol(protocol: PeerProtocol): String {
        return protocol.name
    }
    
    @TypeConverter
    fun toPeerProtocol(value: String): PeerProtocol {
        return PeerProtocol.valueOf(value)
    }
    
    @TypeConverter
    fun fromSortingType(sortingType: SortingType): String {
        return sortingType.name
    }
    
    @TypeConverter
    fun toSortingType(value: String): SortingType {
        return SortingType.valueOf(value)
    }
}
