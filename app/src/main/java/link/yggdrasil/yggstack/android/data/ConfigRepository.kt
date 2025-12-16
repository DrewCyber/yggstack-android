package link.yggdrasil.yggstack.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
        private val MULTICAST_ENABLED = booleanPreferencesKey("multicast_enabled")
        private val DIAGNOSTICS_TAB_KEY = intPreferencesKey("diagnostics_tab")
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
            multicastEnabled = preferences[MULTICAST_ENABLED] ?: true
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
            preferences[MULTICAST_ENABLED] = config.multicastEnabled
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
     * Generate a new private key (placeholder - will be implemented with yggstack binding)
     */
    private fun generatePrivateKey(): String {
        // TODO: Implement actual key generation using yggstack
        return ""
    }
}

