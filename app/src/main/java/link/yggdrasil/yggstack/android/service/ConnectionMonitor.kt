package link.yggdrasil.yggstack.android.service

import android.util.Log
import link.yggdrasil.yggstack.android.data.Protocol
import link.yggdrasil.yggstack.android.data.TrackedConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors active connections and tracks idle time for low power mode
 */
class ConnectionMonitor {
    private val connections = ConcurrentHashMap<String, TrackedConnection>()
    private val mutex = Mutex()
    private var lastGlobalActivity: Long = System.currentTimeMillis()
    
    companion object {
        private const val TAG = "ConnectionMonitor"
    }
    
    /**
     * Track a new connection
     */
    suspend fun trackConnection(connectionId: String, protocol: Protocol) {
        mutex.withLock {
            val connection = TrackedConnection(
                id = connectionId,
                protocol = protocol
            )
            connections[connectionId] = connection
            lastGlobalActivity = System.currentTimeMillis()
            
            Log.d(TAG, "Tracking connection: $connectionId ($protocol), total: ${connections.size}")
        }
    }
    
    /**
     * Update activity timestamp for a connection
     */
    suspend fun updateActivity(connectionId: String, bytesRx: Long = 0, bytesTx: Long = 0) {
        mutex.withLock {
            connections[connectionId]?.let { conn ->
                conn.updateActivity(bytesRx, bytesTx)
                lastGlobalActivity = System.currentTimeMillis()
                
                Log.v(TAG, "Activity on $connectionId: rx=$bytesRx, tx=$bytesTx")
            }
        }
    }
    
    /**
     * Stop tracking a connection
     */
    suspend fun closeConnection(connectionId: String) {
        mutex.withLock {
            connections.remove(connectionId)
            
            Log.d(TAG, "Closed connection: $connectionId, remaining: ${connections.size}")
            
            // Update global activity if there are still active connections
            if (connections.isNotEmpty()) {
                lastGlobalActivity = System.currentTimeMillis()
            }
        }
    }
    
    /**
     * Get count of active connections
     */
    fun getActiveConnectionCount(): Int {
        return connections.size
    }
    
    /**
     * Get idle time in seconds since last activity on any connection
     */
    fun getIdleTimeSeconds(): Long {
        if (connections.isNotEmpty()) {
            // If there are open connections, use their last activity time
            val mostRecentActivity = connections.values
                .maxByOrNull { it.lastActivityAt }
                ?.lastActivityAt ?: lastGlobalActivity
            return (System.currentTimeMillis() - mostRecentActivity) / 1000
        }
        // No connections, use global last activity
        return (System.currentTimeMillis() - lastGlobalActivity) / 1000
    }
    
    /**
     * Check if node should stop based on timeout
     * Returns true if no connections and idle time exceeds timeout
     */
    fun shouldStopNode(timeoutSeconds: Int): Boolean {
        val hasNoConnections = connections.isEmpty()
        val isIdle = getIdleTimeSeconds() >= timeoutSeconds
        
        return hasNoConnections && isIdle
    }
    
    /**
     * Reset idle timer (useful when manually starting node)
     */
    suspend fun resetIdleTimer() {
        mutex.withLock {
            lastGlobalActivity = System.currentTimeMillis()
        }
    }
    
    /**
     * Clear all tracked connections
     */
    suspend fun clearAll() {
        mutex.withLock {
            connections.clear()
            lastGlobalActivity = System.currentTimeMillis()
            Log.d(TAG, "Cleared all tracked connections")
        }
    }
    
    /**
     * Get detailed connection statistics
     */
    fun getConnectionStats(): Map<String, Any> {
        return mapOf(
            "activeConnections" to connections.size,
            "idleSeconds" to getIdleTimeSeconds(),
            "totalBytesRx" to connections.values.sumOf { it.bytesReceived },
            "totalBytesTx" to connections.values.sumOf { it.bytesSent }
        )
    }
    
    /**
     * Get list of connection IDs for debugging
     */
    fun getConnectionIds(): List<String> {
        return connections.keys.toList()
    }
}
