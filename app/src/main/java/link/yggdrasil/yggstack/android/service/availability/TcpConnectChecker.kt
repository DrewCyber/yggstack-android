package link.yggdrasil.yggstack.android.service.availability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Service for checking TCP connectivity
 */
class TcpConnectChecker {
    
    /**
     * Check TCP connection to host:port
     * Returns RTT in milliseconds or null if connection fails
     */
    suspend fun checkConnect(host: String, port: Int, timeoutMs: Int = 10000): Long? = 
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val address = InetSocketAddress(host, port)
                socket = Socket()
                val startTime = System.currentTimeMillis()
                
                socket.connect(address, timeoutMs)
                val endTime = System.currentTimeMillis()
                
                val rtt = endTime - startTime
                Log.d(TAG, "TCP connect $host:$port: ${rtt}ms")
                rtt
            } catch (e: Exception) {
                Log.e(TAG, "TCP connect failed for $host:$port", e)
                null
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        }
    
    companion object {
        private const val TAG = "TcpConnectChecker"
    }
}
