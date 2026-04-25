package link.yggdrasil.yggstackng.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Yggdrasil-specific settings included in a full backup
 */
@Serializable
data class YggdrasilSettings(
    val privateKey: String,
    val peers: List<String>,
    val multicastBeacon: Boolean,
    val multicastListen: Boolean,
    val maxBackoffEnabled: Boolean = true,
    val maxBackoff: Int
)

/**
 * Backup configuration containing exportable settings.
 * When [yggdrasil] is non-null, the backup includes Yggdrasil parameters in addition
 * to Yggstack (proxy / expose / forward) settings.
 */
@Serializable
data class BackupConfig(
    val yggdrasil: YggdrasilSettings? = null,
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
         * Create backup from YggstackConfig.
         * @param includeYggdrasil when true, also backs up private key, peers, multicast and maxBackoff.
         */
        fun fromYggstackConfig(config: YggstackConfig, includeYggdrasil: Boolean = false): BackupConfig {
            return BackupConfig(
                yggdrasil = if (includeYggdrasil) YggdrasilSettings(
                    privateKey = config.privateKey,
                    peers = config.peers,
                    multicastBeacon = config.multicastBeacon,
                    multicastListen = config.multicastListen,
                    maxBackoffEnabled = config.maxBackoffEnabled,
                    maxBackoff = config.maxBackoff
                ) else null,
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
         * Parse backup from TOML string (new format).
         * Uses a custom line-by-line parser that handles IPv6 addresses and
         * arbitrary string values without relying on ktoml.
         */
        fun fromToml(tomlString: String): Result<BackupConfig> = runCatching {
            // ---------- mutable accumulators ----------
            var section = ""  // "", "yggdrasil", "proxy", "expose", "forward", "expose.mappings", "forward.mappings"

            // yggdrasil
            var ygPrivateKey = ""
            val ygPeers = mutableListOf<String>()
            var ygMulticastBeacon = false
            var ygMulticastListen = false
            var ygMaxBackoffEnabled = true
            var ygMaxBackoff = 0
            var hasYggdrasil = false

            // proxy
            var proxyEnabled = false
            var proxySocks = ""
            var proxyDns = ""

            // expose
            var exposeEnabled = false
            val exposeMappings = mutableListOf<ExposeMapping>()

            // forward
            var forwardEnabled = false
            val forwardMappings = mutableListOf<ForwardMapping>()

            // current array-of-table entry
            var curLocalPort = 0
            var curLocalIp = "127.0.0.1"
            var curYggPort = 0
            var curRemotePort = 0
            var curRemoteIp = ""
            var curProtocol = Protocol.TCP
            var curShortName = ""
            var inExposeMappingEntry = false
            var inForwardMappingEntry = false

            fun flushMapping() {
                if (inExposeMappingEntry) {
                    exposeMappings += ExposeMapping(
                        protocol = curProtocol,
                        localPort = curLocalPort,
                        localIp = curLocalIp,
                        yggPort = curYggPort,
                        shortName = curShortName
                    )
                    inExposeMappingEntry = false
                }
                if (inForwardMappingEntry) {
                    forwardMappings += ForwardMapping(
                        protocol = curProtocol,
                        localIp = curLocalIp,
                        localPort = curLocalPort,
                        remoteIp = curRemoteIp,
                        remotePort = curRemotePort,
                        shortName = curShortName
                    )
                    inForwardMappingEntry = false
                }
                curLocalPort = 0; curLocalIp = "127.0.0.1"; curYggPort = 0; curRemotePort = 0
                curRemoteIp = ""; curProtocol = Protocol.TCP; curShortName = ""
            }

            for (rawLine in tomlString.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                // --- array-of-tables header [[…]] ---
                if (line.startsWith("[[")) {
                    flushMapping()
                    val headerInner = line.removePrefix("[[").removeSuffix("]]").trim()
                    section = headerInner
                    when (headerInner) {
                        "expose.mappings"  -> { inExposeMappingEntry  = true }
                        "forward.mappings" -> { inForwardMappingEntry = true }
                    }
                    continue
                }

                // --- standard table header […] ---
                if (line.startsWith("[")) {
                    flushMapping()
                    section = line.removePrefix("[").removeSuffix("]").trim()
                    when (section) {
                        "yggdrasil" -> hasYggdrasil = true
                    }
                    continue
                }

                // --- key = value ---
                val eqIdx = line.indexOf('=')
                if (eqIdx < 0) continue
                val key = line.substring(0, eqIdx).trim()
                val rawVal = line.substring(eqIdx + 1).trim()

                fun strVal() = rawVal.removeSurrounding("\"").tomlUnescape()
                fun boolVal() = rawVal.trim() == "true"
                fun intVal()  = rawVal.trim().toInt()

                when (section) {
                    "yggdrasil" -> when (key) {
                        "privateKey"      -> ygPrivateKey       = strVal()
                        "peers"           -> ygPeers.addAll(parseTomlStringArray(rawVal))
                        "multicastBeacon" -> ygMulticastBeacon  = boolVal()
                        "multicastListen" -> ygMulticastListen  = boolVal()
                        "maxBackoffEnabled" -> ygMaxBackoffEnabled = boolVal()
                        "maxBackoff"      -> ygMaxBackoff        = intVal()
                    }
                    "proxy" -> when (key) {
                        "enabled"     -> proxyEnabled = boolVal()
                        "socksAddress" -> proxySocks   = strVal()
                        "dnsServer"   -> proxyDns      = strVal()
                    }
                    "expose" -> when (key) {
                        "enabled" -> exposeEnabled = boolVal()
                    }
                    "forward" -> when (key) {
                        "enabled" -> forwardEnabled = boolVal()
                    }
                    "expose.mappings" -> when (key) {
                        "localPort" -> curLocalPort = intVal()
                        "localIp"   -> curLocalIp   = strVal()
                        "yggPort"   -> curYggPort   = intVal()
                        "protocol"  -> curProtocol  = Protocol.valueOf(strVal().uppercase())
                        "shortName" -> curShortName = strVal()
                    }
                    "forward.mappings" -> when (key) {
                        "localPort"  -> curLocalPort  = intVal()
                        "localIp"    -> curLocalIp    = strVal()
                        "remoteIp"   -> curRemoteIp   = strVal()
                        "remotePort" -> curRemotePort = intVal()
                        "protocol"   -> curProtocol   = Protocol.valueOf(strVal().uppercase())
                        "shortName"  -> curShortName  = strVal()
                    }
                }
            }
            flushMapping()

            BackupConfig(
                yggdrasil = if (hasYggdrasil) YggdrasilSettings(
                    privateKey      = ygPrivateKey,
                    peers           = ygPeers,
                    multicastBeacon = ygMulticastBeacon,
                    multicastListen = ygMulticastListen,
                    maxBackoffEnabled = ygMaxBackoffEnabled,
                    maxBackoff      = ygMaxBackoff
                ) else null,
                proxy   = ProxySettings(proxyEnabled, proxySocks, proxyDns),
                expose  = ExposeSettings(exposeEnabled, exposeMappings),
                forward = ForwardSettings(forwardEnabled, forwardMappings)
            )
        }

        /** Parse a TOML inline string array: ["a", "b", …] → List<String> */
        private fun parseTomlStringArray(raw: String): List<String> {
            val inner = raw.trim().removePrefix("[").removeSuffix("]")
            if (inner.isBlank()) return emptyList()
            val result = mutableListOf<String>()
            var i = 0
            while (i < inner.length) {
                when {
                    inner[i] == '"' -> {
                        val end = inner.indexOf('"', i + 1)
                        if (end < 0) break
                        result += inner.substring(i + 1, end).tomlUnescape()
                        i = end + 1
                    }
                    else -> i++
                }
            }
            return result
        }

        /**
         * Parse backup from JSON string (legacy format – backward compatibility).
         */
        fun fromJson(jsonString: String): Result<BackupConfig> {
            return try {
                val backup = json.decodeFromString<BackupConfig>(jsonString)
                Result.success(backup)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Parse backup from a string, auto-detecting TOML or JSON format.
         * JSON is detected by a leading '{'. Everything else is treated as TOML.
         */
        fun fromString(content: String): Result<BackupConfig> {
            return if (content.trimStart().startsWith("{")) {
                fromJson(content)
            } else {
                fromToml(content)
            }
        }
    }

    /**
     * Serialise backup to TOML string (manual builder — no external library required).
     */
    fun toToml(): String = buildString {
        if (yggdrasil != null) {
            appendLine("[yggdrasil]")
            appendLine("privateKey = \"${yggdrasil.privateKey.tomlEscape()}\"")
            val peersToml = yggdrasil.peers.joinToString(", ") { "\"${it.tomlEscape()}\"" }
            appendLine("peers = [$peersToml]")
            appendLine("multicastBeacon = ${yggdrasil.multicastBeacon}")
            appendLine("multicastListen = ${yggdrasil.multicastListen}")
            appendLine("maxBackoffEnabled = ${yggdrasil.maxBackoffEnabled}")
            appendLine("maxBackoff = ${yggdrasil.maxBackoff}")
            appendLine()
        }

        appendLine("[proxy]")
        appendLine("enabled = ${proxy.enabled}")
        appendLine("socksAddress = \"${proxy.socksAddress.tomlEscape()}\"")
        appendLine("dnsServer = \"${proxy.dnsServer.tomlEscape()}\"")
        appendLine()

        appendLine("[expose]")
        appendLine("enabled = ${expose.enabled}")
        expose.mappings.forEach { m ->
            appendLine()
            appendLine("[[expose.mappings]]")
            appendLine("localPort = ${m.localPort}")
            appendLine("localIp = \"${m.localIp.tomlEscape()}\"")
            appendLine("yggPort = ${m.yggPort}")
            appendLine("protocol = \"${m.protocol.name}\"")
            if (m.shortName.isNotBlank()) appendLine("shortName = \"${m.shortName.tomlEscape()}\"")
        }
        appendLine()

        appendLine("[forward]")
        appendLine("enabled = ${forward.enabled}")
        forward.mappings.forEach { m ->
            appendLine()
            appendLine("[[forward.mappings]]")
            appendLine("localPort = ${m.localPort}")
            appendLine("localIp = \"${m.localIp.tomlEscape()}\"")
            appendLine("remoteIp = \"${m.remoteIp.tomlEscape()}\"")
            appendLine("remotePort = ${m.remotePort}")
            appendLine("protocol = \"${m.protocol.name}\"")
            if (m.shortName.isNotBlank()) appendLine("shortName = \"${m.shortName.tomlEscape()}\"")
        }
    }

    /**
     * Apply backup to existing config (merge settings).
     * Yggdrasil parameters are only overwritten when [yggdrasil] is non-null.
     */
    fun applyTo(config: YggstackConfig): YggstackConfig {
        val base = config.copy(
            proxyEnabled = proxy.enabled,
            socksProxy = proxy.socksAddress,
            dnsServer = proxy.dnsServer,
            exposeEnabled = expose.enabled,
            exposeMappings = expose.mappings,
            forwardEnabled = forward.enabled,
            forwardMappings = forward.mappings
        )
        return yggdrasil?.let {
            base.copy(
                privateKey = it.privateKey,
                peers = it.peers,
                multicastBeacon = it.multicastBeacon,
                multicastListen = it.multicastListen,
                maxBackoffEnabled = it.maxBackoffEnabled,
                maxBackoff = it.maxBackoff
            )
        } ?: base
    }

    /**
     * Validate backup configuration
     */
    fun validate(): Result<Unit> {
        return try {
            expose.mappings.forEach { mapping ->
                if (mapping.localPort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid local port: ${mapping.localPort}"))
                }
                if (mapping.yggPort !in 1..65535) {
                    return@validate Result.failure(IllegalArgumentException("Invalid Yggdrasil port: ${mapping.yggPort}"))
                }
            }
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
    val mappings: List<ExposeMapping> = emptyList()
)

/**
 * Forward configuration settings
 */
@Serializable
data class ForwardSettings(
    val enabled: Boolean,
    val mappings: List<ForwardMapping> = emptyList()
)

// TOML string escape helpers
private fun String.tomlEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
private fun String.tomlUnescape(): String = replace("\\\"", "\"").replace("\\\\", "\\")