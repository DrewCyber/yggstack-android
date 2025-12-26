package link.yggdrasil.yggstack.android.data

/**
 * Represents a tracked connection for low power mode monitoring
 */
data class TrackedConnection(
    val id: String,
    val protocol: Protocol,
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis(),
    var bytesReceived: Long = 0,
    var bytesSent: Long = 0
) {
    /**
     * Check if connection is idle for given timeout
     */
    fun isIdleFor(timeoutSeconds: Int): Boolean {
        val idleTimeMs = System.currentTimeMillis() - lastActivityAt
        return idleTimeMs >= (timeoutSeconds * 1000)
    }
    
    /**
     * Get idle time in seconds
     */
    fun getIdleSeconds(): Long {
        return (System.currentTimeMillis() - lastActivityAt) / 1000
    }
    
    /**
     * Update activity timestamp
     */
    fun updateActivity(bytesRx: Long = 0, bytesTx: Long = 0) {
        lastActivityAt = System.currentTimeMillis()
        bytesReceived += bytesRx
        bytesSent += bytesTx
    }
}
