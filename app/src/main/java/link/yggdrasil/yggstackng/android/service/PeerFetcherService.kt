package link.yggdrasil.yggstackng.android.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.yggdrasil.yggstackng.android.data.PublicPeerInfo
import org.xbill.DNS.ARecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Service for fetching public peers from remote sources
 */
class PeerFetcherService {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        private const val PRIMARY_URL = "https://publicpeers.neilalexander.dev/publicnodes.json"
        private const val MIRROR_URL = "https://peers.yggdrasil.link/publicnodes.json"
        private const val TIMEOUT_MS = 10000
    }

    /**
     * Fetch public peers from remote source
     */
    suspend fun fetchPublicPeers(): Result<List<PublicPeerInfo>> = withContext(Dispatchers.IO) {
        try {
            // Try primary URL first
            val jsonString = fetchUrl(PRIMARY_URL) ?: fetchUrl(MIRROR_URL)
            
            if (jsonString == null) {
                return@withContext Result.failure(Exception("Failed to fetch from both primary and mirror URLs"))
            }

            val peers = parsePublicNodesJson(jsonString)
            Result.success(peers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Load public peers from the bundled asset (publicnodes.json packed into the APK).
     * Used as an immediate seed on first launch so the user is never left with an empty list.
     */
    suspend fun loadBundledPeers(context: Context): Result<List<PublicPeerInfo>> = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.assets.open("publicnodes.json").bufferedReader().use { it.readText() }
            val peers = parsePublicNodesJson(jsonString)
            Result.success(peers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get current external IPv4 address
     * Tries ipify.org first, falls back to DNS query via OpenDNS
     */
    suspend fun getExternalIp(onStatusUpdate: ((String) -> Unit)? = null): Result<String> = withContext(Dispatchers.IO) {
        // Try ipify.org first
        onStatusUpdate?.invoke("Checking external IP (ipify)...")
        try {
            val ip = fetchUrl("https://api.ipify.org?format=text")?.trim()
            if (!ip.isNullOrBlank()) {
                return@withContext Result.success(ip)
            }
        } catch (e: Exception) {
            // Ignore, try DNS fallback
        }
        
        // Fallback to DNS query via OpenDNS
        onStatusUpdate?.invoke("Checking external IP (DNS)...")
        try {
            val ip = getExternalIpViaDns()
            if (ip != null) {
                Result.success(ip)
            } else {
                Result.failure(Exception("Failed to get IP from both ipify and DNS"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to get IP from both ipify and DNS: ${e.message}", e))
        }
    }

    /**
     * Get external IP via DNS query to OpenDNS
     * Equivalent to: dig +short myip.opendns.com @resolver1.opendns.com
     */
    private fun getExternalIpViaDns(): String? {
        return try {
            // Configure resolver to use OpenDNS (208.67.222.222)
            val resolver = SimpleResolver("208.67.222.222")
            resolver.setTimeout(java.time.Duration.ofSeconds(5))
            
            // Query myip.opendns.com for A record
            val lookup = Lookup("myip.opendns.com", Type.A)
            lookup.setResolver(resolver)
            val records = lookup.run()
            
            // Extract IP from first A record
            if (records != null && records.isNotEmpty()) {
                val aRecord = records[0] as? ARecord
                aRecord?.address?.hostAddress
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetch content from URL
     */
    private fun fetchUrl(urlString: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()
            
            return response.toString()
        } catch (e: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parse publicnodes.json format
     * Structure: { "country.md": { "protocol://host:port": { "key": "...", ... }, ... }, ... }
     */
    private fun parsePublicNodesJson(jsonString: String): List<PublicPeerInfo> {
        val peers = mutableListOf<PublicPeerInfo>()
        
        try {
            val rootObject = json.parseToJsonElement(jsonString).jsonObject
            
            // Iterate through countries
            rootObject.forEach { (countryFile, countryData) ->
                val country = countryFile.removeSuffix(".md")
                    .split("-")
                    .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
                
                val peerMap = countryData.jsonObject
                
                // Iterate through peers in this country
                peerMap.forEach { (uri, _) ->
                    try {
                        peers.add(
                            PublicPeerInfo(
                                uri = uri,
                                country = country
                            )
                        )
                    } catch (e: Exception) {
                        // Skip invalid peer entries
                    }
                }
            }
        } catch (e: Exception) {
            throw Exception("Failed to parse JSON: ${e.message}", e)
        }
        
        return peers
    }
}
