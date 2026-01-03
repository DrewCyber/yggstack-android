package link.yggdrasil.yggstack.android.service.discovery

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Service for detecting external IP address
 */
class ExternalIpDetector {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val ipv4Sources = listOf(
        "https://api.ipify.org",
        "https://icanhazip.com",
        "https://ifconfig.me/ip",
        "https://checkip.amazonaws.com"
    )
    
    private val ipv6Sources = listOf(
        "https://api6.ipify.org",
        "https://icanhazip.com"
    )
    
    /**
     * Detect external IP address
     * Prefers IPv4, falls back to IPv6 if IPv4 is unavailable
     */
    suspend fun detectExternalIp(): String? = withContext(Dispatchers.IO) {
        // Try to get IPv4 first
        val ipv4 = detectIpv4()
        if (ipv4 != null) {
            Log.d(TAG, "Detected IPv4: $ipv4")
            return@withContext ipv4
        }
        
        // Fall back to IPv6 if IPv4 unavailable
        val ipv6 = detectIpv6()
        if (ipv6 != null) {
            Log.d(TAG, "Detected IPv6: $ipv6")
            return@withContext ipv6
        }
        
        Log.w(TAG, "Failed to detect external IP")
        null
    }
    
    /**
     * Try to detect IPv4 address
     */
    private suspend fun detectIpv4(): String? {
        for (source in ipv4Sources) {
            try {
                val ip = fetchIp(source)
                if (ip != null && isValidIpv4(ip)) {
                    return ip
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get IPv4 from $source", e)
            }
        }
        return null
    }
    
    /**
     * Try to detect IPv6 address
     */
    private suspend fun detectIpv6(): String? {
        for (source in ipv6Sources) {
            try {
                val ip = fetchIp(source)
                if (ip != null && isValidIpv6(ip)) {
                    return ip
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get IPv6 from $source", e)
            }
        }
        return null
    }
    
    /**
     * Fetch IP from URL
     */
    private fun fetchIp(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                response.body?.string()?.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch from $url", e)
            null
        }
    }
    
    /**
     * Validate IPv4 address format
     */
    private fun isValidIpv4(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false
            
            parts.all { part ->
                val num = part.toIntOrNull() ?: return false
                num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validate IPv6 address format
     */
    private fun isValidIpv6(ip: String): Boolean {
        return try {
            val address = InetAddress.getByName(ip)
            address is Inet6Address
        } catch (e: Exception) {
            false
        }
    }
    
    companion object {
        private const val TAG = "ExternalIpDetector"
    }
}
