package link.yggdrasil.yggstack.android.service.availability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Service for checking TLS connectivity
 */
class TlsConnectChecker {
    
    /**
     * Check TLS connection to host:port
     * Returns RTT in milliseconds or null if connection fails
     */
    suspend fun checkConnect(host: String, port: Int, timeoutMs: Int = 10000): Long? = 
        withContext(Dispatchers.IO) {
            var socket: SSLSocket? = null
            try {
                val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val startTime = System.currentTimeMillis()
                
                socket = socketFactory.createSocket() as SSLSocket
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.startHandshake() // Complete TLS handshake
                
                val endTime = System.currentTimeMillis()
                
                val rtt = endTime - startTime
                Log.d(TAG, "TLS connect $host:$port: ${rtt}ms")
                rtt
            } catch (e: Exception) {
                Log.e(TAG, "TLS connect failed for $host:$port", e)
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
        private const val TAG = "TlsConnectChecker"
    }
}
