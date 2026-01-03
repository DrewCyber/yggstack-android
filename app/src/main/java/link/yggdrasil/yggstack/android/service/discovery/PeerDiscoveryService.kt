package link.yggdrasil.yggstack.android.service.discovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import link.yggdrasil.yggstack.android.data.peer.PeerProtocol
import link.yggdrasil.yggstack.android.data.peer.PublicPeer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Service for discovering and fetching public Yggdrasil peers
 */
class PeerDiscoveryService(private val context: Context) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val sources = listOf(
        "https://publicpeers.neilalexander.dev/publicnodes.json",
        "https://peers.yggdrasil.link/publicnodes.json"
    )
    
    /**
     * Fetch public peers from available sources
     */
    suspend fun fetchPublicPeers(): Result<List<PublicPeer>> = withContext(Dispatchers.IO) {
        for (source in sources) {
            try {
                Log.d(TAG, "Fetching peers from $source")
                val request = Request.Builder().url(source).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    if (json != null) {
                        val peers = parsePublicNodesJson(json)
                        Log.d(TAG, "Successfully fetched ${peers.size} peers from $source")
                        return@withContext Result.success(peers)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch from $source", e)
                continue
            }
        }
        Result.failure(Exception("All sources failed"))
    }
    
    /**
     * Parse public nodes JSON to PublicPeer list
     */
    private fun parsePublicNodesJson(json: String): List<PublicPeer> {
        val peers = mutableListOf<PublicPeer>()
        
        try {
            val rootObject = JSONObject(json)
            
            rootObject.keys().forEach { countryKey ->
                // Remove .md extension and capitalize first letter
                val country = countryKey.removeSuffix(".md")
                    .replaceFirstChar { it.uppercase() }
                
                val countryObject = rootObject.getJSONObject(countryKey)
                
                countryObject.keys().forEach { uri ->
                    try {
                        val protocol = PeerProtocol.fromUri(uri)
                        val (host, port) = PublicPeer.parseUri(uri)
                        
                        // Ignore metadata from JSON (up, response_ms, key, last_seen)
                        // as they relate to another host
                        peers.add(
                            PublicPeer(
                                uri = uri,
                                protocol = protocol,
                                host = host,
                                port = port,
                                country = country,
                                pingMs = null,
                                connectRtt = null,
                                lastChecked = null
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse peer URI: $uri", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON", e)
        }
        
        return peers
    }
    
    companion object {
        private const val TAG = "PeerDiscoveryService"
    }
}
