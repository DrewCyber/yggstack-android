package link.yggdrasil.yggstack.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Backup configuration containing only exportable settings
 */
@Serializable
data class BackupConfig(
    val proxy: ProxySettings,
    val expose: ExposeSettings,
    val forward: ForwardSettings
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "    "
            ignoreUnknownKeys = true
        }

        /**
         * Create backup from YggstackConfig
         */
        fun fromYggstackConfig(config: YggstackConfig): BackupConfig {
            return BackupConfig(
                proxy = ProxySettings(
                    enabled = config.proxyEnabled,
                    socksAddress = config.socksProxy,
                    dnsServer = config.dnsServer
                ),
                expose = ExposeSettings(
                    enabled = config.exposeEnabled,
                    mappings = config.exposeMappings
                ),
                forward = ForwardSettings(
                    enabled = config.forwardEnabled,
                    mappings = config.forwardMappings
                )
            )
        }

        /**
         * Parse backup from JSON string
         */
        fun fromJson(jsonString: String): Result<BackupConfig> {
            return try {
                val backup = json.decodeFromString<BackupConfig>(jsonString)
                Result.success(backup)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Convert backup to JSON string
     */
    fun toJson(): String {
        return json.encodeToString(this)
    }

    /**
     * Apply backup to existing config (merge settings)
     */
    fun applyTo(config: YggstackConfig): YggstackConfig {
        return config.copy(
            proxyEnabled = proxy.enabled,
            socksProxy = proxy.socksAddress,
            dnsServer = proxy.dnsServer,
            exposeEnabled = expose.enabled,
            exposeMappings = expose.mappings,
            forwardEnabled = forward.enabled,
            forwardMappings = forward.mappings
        )
    }

    /**
     * Validate backup configuration
     */
    fun validate(): Result<Unit> {
        return try {
            // Validate expose ports
            expose.mappings.forEach { mapping ->
                if (mapping.localPort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid local port: ${mapping.localPort}"))
                }
                if (mapping.yggPort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid Yggdrasil port: ${mapping.yggPort}"))
                }
            }

            // Validate forward ports
            forward.mappings.forEach { mapping ->
                if (mapping.localPort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid local port: ${mapping.localPort}"))
                }
                if (mapping.remotePort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid remote port: ${mapping.remotePort}"))
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Proxy configuration settings
 */
@Serializable
data class ProxySettings(
    val enabled: Boolean,
    val socksAddress: String,
    val dnsServer: String
)


/**
 * Expose configuration settings
 */
@Serializable
data class ExposeSettings(
    val enabled: Boolean,
    val mappings: List<ExposeMapping>
)

/**
 * Forward configuration settings
 */
@Serializable
data class ForwardSettings(
    val enabled: Boolean,
    val mappings: List<ForwardMapping>
)