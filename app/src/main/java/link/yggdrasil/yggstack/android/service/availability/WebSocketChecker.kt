package link.yggdrasil.yggstack.android.service.availability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Service for checking WebSocket (WS/WSS) connectivity
 */
class WebSocketChecker {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    /**
     * Check WebSocket connection to URI
     * Returns RTT in milliseconds or null if connection fails
     */
    suspend fun checkConnect(uri: String, timeoutMs: Int = 10000): Long? = 
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(uri).build()
                val startTime = System.currentTimeMillis()
                
                val connected = AtomicBoolean(false)
                val rtt = AtomicLong(-1)
                val latch = CountDownLatch(1)
                
                val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected.set(true)
                        rtt.set(System.currentTimeMillis() - startTime)
                        latch.countDown()
                        webSocket.close(1000, "Check complete")
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "WebSocket connection failed for $uri", t)
                        latch.countDown()
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        latch.countDown()
                    }
                })
                
                // Wait for connection or timeout
                val success = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                
                if (success && connected.get()) {
                    val finalRtt = rtt.get()
                    Log.d(TAG, "WebSocket connect $uri: ${finalRtt}ms")
                    finalRtt
                } else {
                    Log.d(TAG, "WebSocket connect $uri: failed or timeout")
                    webSocket.cancel()
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connect failed for $uri", e)
                null
            }
        }
    
    companion object {
        private const val TAG = "WebSocketChecker"
    }
}
