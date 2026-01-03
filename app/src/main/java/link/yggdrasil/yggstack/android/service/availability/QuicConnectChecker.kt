package link.yggdrasil.yggstack.android.service.availability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import link.yggdrasil.yggstack.mobile.Mobile

/**
 * Service for checking QUIC connectivity via Go mobile bindings
 */
class QuicConnectChecker {
    
    /**
     * Check QUIC connection to host:port using Go mobile bindings
     * Returns RTT in milliseconds or null if connection fails
     */
    suspend fun checkConnect(host: String, port: Int, timeoutMs: Int = 10000): Long? = 
        withContext(Dispatchers.IO) {
            try {
                val rtt = Mobile.checkQuicConnect(host, port.toLong(), timeoutMs.toLong())
                
                if (rtt > 0) {
                    Log.d(TAG, "QUIC connect $host:$port: ${rtt}ms")
                    rtt
                } else {
                    Log.d(TAG, "QUIC connect $host:$port: failed")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "QUIC connect failed for $host:$port", e)
                null
            }
        }
    
    companion object {
        private const val TAG = "QuicConnectChecker"
    }
}
