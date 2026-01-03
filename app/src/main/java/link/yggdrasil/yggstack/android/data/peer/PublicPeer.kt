package link.yggdrasil.yggstack.android.data.peer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Public Yggdrasil peer model
 */
@Entity(tableName = "peers_cache")
data class PublicPeer(
    @PrimaryKey
    val uri: String,
    
    val protocol: PeerProtocol,
    val host: String,
    val port: Int,
    val country: String,
    
    // Availability check results
    val pingMs: Long? = null,
    val connectRtt: Long? = null,
    val lastChecked: Long? = null,
    
    // Management metadata
    val addedAt: Long = System.currentTimeMillis(),
    val isManuallyAdded: Boolean = false
) {
    /**
     * Get display name for peer (country + protocol)
     */
    fun getDisplayName(): String = "$country (${protocol.name})"
    
    /**
     * Check if peer has been checked for availability
     */
    fun isChecked(): Boolean = lastChecked != null
    
    /**
     * Get best available RTT (prefer connect over ping)
     */
    fun getBestRtt(): Long? = connectRtt ?: pingMs
    
    companion object {
        /**
         * Parse peer URI to extract host and port
         */
        fun parseUri(uri: String): Pair<String, Int> {
            val withoutProtocol = uri.substringAfter("://")
            val parts = withoutProtocol.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.substringBefore("/")?.toIntOrNull() ?: 0
            return host to port
        }
        
        /**
         * Create PublicPeer from URI
         */
        fun fromUri(uri: String, country: String = "Unknown", isManual: Boolean = false): PublicPeer {
            val protocol = PeerProtocol.fromUri(uri)
            val (host, port) = parseUri(uri)
            
            return PublicPeer(
                uri = uri,
                protocol = protocol,
                host = host,
                port = port,
                country = country,
                isManuallyAdded = isManual
            )
        }
    }
}
