package link.yggdrasil.yggstack.android.service.availability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Service for checking peer availability via ping
 */
class PingChecker {
    
    /**
     * Check if host is reachable via ping
     * Returns RTT in milliseconds or null if unreachable
     */
    suspend fun checkPing(host: String, timeoutMs: Int = 5000): Long? = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(host)
            val startTime = System.currentTimeMillis()
            val isReachable = address.isReachable(timeoutMs)
            val endTime = System.currentTimeMillis()
            
            if (isReachable) {
                val rtt = endTime - startTime
                Log.d(TAG, "Ping $host: ${rtt}ms")
                rtt
            } else {
                Log.d(TAG, "Ping $host: unreachable")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed for $host", e)
            null
        }
    }
    
    companion object {
        private const val TAG = "PingChecker"
    }
}
