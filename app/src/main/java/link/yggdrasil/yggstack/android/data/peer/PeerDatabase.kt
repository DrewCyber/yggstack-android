package link.yggdrasil.yggstack.android.data.peer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database for peer discovery and management
 */
@Database(
    entities = [
        PublicPeer::class,
        PeerCacheMetadata::class,
        SortedPeerList::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(PeerTypeConverters::class)
abstract class PeerDatabase : RoomDatabase() {
    
    abstract fun peerDao(): PeerDao
    abstract fun sortedListDao(): SortedListDao
    abstract fun metadataDao(): MetadataDao
    
    companion object {
        @Volatile
        private var INSTANCE: PeerDatabase? = null
        
        fun getDatabase(context: Context): PeerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PeerDatabase::class.java,
                    "peer_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Clear database instance (for testing)
         */
        fun clearInstance() {
            INSTANCE = null
        }
    }
}
