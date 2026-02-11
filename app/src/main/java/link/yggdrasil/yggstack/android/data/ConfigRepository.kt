package link.yggdrasil.yggstack.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "yggstack_config")

/**
 * Repository for managing Yggstack configuration persistence
 */
class ConfigRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private val PEERS_KEY = stringPreferencesKey("peers")
        private val PRIVATE_KEY = stringPreferencesKey("private_key")
        private val SOCKS_PROXY = stringPreferencesKey("socks_proxy")
        private val DNS_SERVER = stringPreferencesKey("dns_server")
        private val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        private val EXPOSE_MAPPINGS = stringPreferencesKey("expose_mappings")
        private val EXPOSE_ENABLED = booleanPreferencesKey("expose_enabled")
        private val FORWARD_MAPPINGS = stringPreferencesKey("forward_mappings")
        private val FORWARD_ENABLED = booleanPreferencesKey("forward_enabled")
        private val THEME_KEY = stringPreferencesKey("theme")
        private val AUTOSTART_KEY = booleanPreferencesKey("autostart")
        private val AUTO_UPDATE_KEY = booleanPreferencesKey("auto_update")
        private val MULTICAST_BEACON = booleanPreferencesKey("multicast_beacon")
        private val MULTICAST_LISTEN = booleanPreferencesKey("multicast_listen")
        private val LOG_LEVEL = stringPreferencesKey("log_level")
        private val CACHED_PEERS = stringPreferencesKey("cached_peers")
        private val MAX_BACKOFF = intPreferencesKey("max_backoff")
        private val LOGS_ENABLED = booleanPreferencesKey("logs_enabled")
        private val DIAGNOSTICS_TAB_KEY = intPreferencesKey("diagnostics_tab")
        private val PUBLIC_PEERS_CACHE = stringPreferencesKey("public_peers_cache")
        private val SORTED_PEERS_CACHE = stringPreferencesKey("sorted_peers_cache")
        private val LAST_EXTERNAL_IP = stringPreferencesKey("last_external_ip")
        private val LANGUAGE_KEY = stringPreferencesKey("language")
    }

    /**
     * Get configuration as Flow
     */
    val configFlow: Flow<YggstackConfig> = context.dataStore.data.map { preferences ->
        YggstackConfig(
            peers = preferences[PEERS_KEY]?.let {
                json.decodeFromString<List<String>>(it)
            } ?: emptyList(),
            privateKey = preferences[PRIVATE_KEY] ?: generatePrivateKey(),
            socksProxy = preferences[SOCKS_PROXY] ?: "127.0.0.1:1080",
            dnsServer = preferences[DNS_SERVER] ?: "[308:62:45:62::]:53",
            proxyEnabled = preferences[PROXY_ENABLED] ?: false,
            exposeMappings = preferences[EXPOSE_MAPPINGS]?.let {
                json.decodeFromString<List<ExposeMapping>>(it)
            } ?: emptyList(),
            exposeEnabled = preferences[EXPOSE_ENABLED] ?: false,
            forwardMappings = preferences[FORWARD_MAPPINGS]?.let {
                json.decodeFromString<List<ForwardMapping>>(it)
            } ?: emptyList(),
            forwardEnabled = preferences[FORWARD_ENABLED] ?: false,
            multicastBeacon = preferences[MULTICAST_BEACON] ?: false,
            multicastListen = preferences[MULTICAST_LISTEN] ?: false,
            logLevel = preferences[LOG_LEVEL] ?: "error",
            cachedPeers = preferences[CACHED_PEERS]?.let {
                json.decodeFromString<List<CachedPeer>>(it)
            } ?: emptyList(),
            maxBackoff = preferences[MAX_BACKOFF] ?: 5
        )
    }

    /**
     * Save configuration
     */
    suspend fun saveConfig(config: YggstackConfig) {
        context.dataStore.edit { preferences ->
            preferences[PEERS_KEY] = json.encodeToString(config.peers)
            preferences[PRIVATE_KEY] = config.privateKey
            preferences[SOCKS_PROXY] = config.socksProxy
            preferences[DNS_SERVER] = config.dnsServer
            preferences[PROXY_ENABLED] = config.proxyEnabled
            preferences[EXPOSE_MAPPINGS] = json.encodeToString(config.exposeMappings)
            preferences[EXPOSE_ENABLED] = config.exposeEnabled
            preferences[FORWARD_MAPPINGS] = json.encodeToString(config.forwardMappings)
            preferences[FORWARD_ENABLED] = config.forwardEnabled
            preferences[MULTICAST_BEACON] = config.multicastBeacon
            preferences[MULTICAST_LISTEN] = config.multicastListen
            preferences[LOG_LEVEL] = config.logLevel
            preferences[CACHED_PEERS] = json.encodeToString(config.cachedPeers)
            preferences[MAX_BACKOFF] = config.maxBackoff
        }
    }

    /**
     * Get theme preference
     */
    val themeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: "system"
    }

    /**
     * Save theme preference
     */
    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
    }

    /**
     * Get autostart preference
     */
    val autostartFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTOSTART_KEY] ?: false
    }

    /**
     * Save autostart preference
     */
    suspend fun saveAutostart(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTOSTART_KEY] = enabled
        }
    }

    /**
     * Get auto-update preference
     */
    val autoUpdateFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_UPDATE_KEY] ?: true  // Default to enabled
    }

    /**
     * Save auto-update preference
     */
    suspend fun saveAutoUpdate(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_UPDATE_KEY] = enabled
        }
    }

    /**
     * Get logs enabled preference
     */
    val logsEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LOGS_ENABLED] ?: false  // Default to disabled for production
    }

    /**
     * Save logs enabled preference
     */
    suspend fun saveLogsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOGS_ENABLED] = enabled
        }
    }

    /**
     * Get diagnostics tab preference
     */
    val diagnosticsTabFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[DIAGNOSTICS_TAB_KEY] ?: 0
    }

    /**
     * Save diagnostics tab preference
     */
    suspend fun saveDiagnosticsTab(tabIndex: Int) {
        context.dataStore.edit { preferences ->
            preferences[DIAGNOSTICS_TAB_KEY] = tabIndex
        }
    }

    /**
     * Get language preference
     */
    val languageFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: "system"
    }

    /**
     * Save language preference
     */
    suspend fun saveLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language
        }
    }

    /**
     * Generate a new private key (placeholder - will be implemented with yggstack binding)
     */
    private fun generatePrivateKey(): String {
        // TODO: Implement actual key generation using yggstack
        return ""
    }

    // ============ Public Peers Cache Management ============

    /**
     * Get global public peers cache
     */
    suspend fun getPublicPeersCache(): PublicPeersCache {
        val preferences = context.dataStore.data.first()
        return preferences[PUBLIC_PEERS_CACHE]?.let {
            json.decodeFromString<PublicPeersCache>(it)
        } ?: PublicPeersCache()
    }

    /**
     * Save global public peers cache
     */
    suspend fun savePublicPeersCache(cache: PublicPeersCache) {
        context.dataStore.edit { preferences ->
            preferences[PUBLIC_PEERS_CACHE] = json.encodeToString(cache)
        }
    }

    /**
     * Check if public peers cache exists
     */
    suspend fun hasPublicPeersCache(): Boolean {
        val cache = getPublicPeersCache()
        return cache.peers.isNotEmpty()
    }

    /**
     * Get sorted peers cache
     */
    private suspend fun getSortedPeersCache(): SortedPeersCache {
        val preferences = context.dataStore.data.first()
        return preferences[SORTED_PEERS_CACHE]?.let {
            json.decodeFromString<SortedPeersCache>(it)
        } ?: SortedPeersCache()
    }

    /**
     * Get sorted list for specific external IP
     */
    suspend fun getSortedListForIp(externalIp: String): PeerListForIp? {
        val sortedCache = getSortedPeersCache()
        return sortedCache.sortedByIp[externalIp]
    }

    /**
     * Save sorted list for specific external IP
     */
    suspend fun saveSortedListForIp(externalIp: String, peerList: PeerListForIp) {
        val sortedCache = getSortedPeersCache()
        val updatedCache = sortedCache.copy(
            sortedByIp = sortedCache.sortedByIp + (externalIp to peerList)
        )
        context.dataStore.edit { preferences ->
            preferences[SORTED_PEERS_CACHE] = json.encodeToString(updatedCache)
        }
    }

    /**
     * Get last known external IP (or null if never detected)
     */
    suspend fun getLastExternalIp(): String? {
        val preferences = context.dataStore.data.first()
        return preferences[LAST_EXTERNAL_IP]
    }

    /**
     * Save last known external IP
     */
    suspend fun saveLastExternalIp(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_EXTERNAL_IP] = ip
        }
    }

    /**
     * Clear all sorted lists (when global cache is updated)
     */
    suspend fun clearAllSortedLists() {
        context.dataStore.edit { preferences ->
            preferences.remove(SORTED_PEERS_CACHE)
        }
    }

    /**
     * Clear all public peers data
     */
    suspend fun clearAllPublicPeersData() {
        context.dataStore.edit { preferences ->
            preferences.remove(PUBLIC_PEERS_CACHE)
            preferences.remove(SORTED_PEERS_CACHE)
        }
    }
}

