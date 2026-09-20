package link.yggdrasil.yggstackng.android.data

/**
 * Builds the TOML configuration consumed by the Rust yggdrasil-ng core
 * (mirror of NativeConfigJson, which targets the Go core's JSON schema).
 *
 * The Rust core ignores unknown fields, but only the ones below are
 * guaranteed to be honored; if_name/admin_listen are forced by the library
 * itself and listed here only so the diagnostics card matches what the
 * core will actually run with.
 */
internal object NativeConfigToml {
    private val privateKeyRegex = Regex("""private_key\s*=\s*"([^"]+)"""")

    fun privateKey(value: String): String = privateKeyRegex.find(value)
        ?.groupValues?.get(1)
        ?: throw IllegalArgumentException("No private_key field in config")

    fun build(config: YggstackConfig, generated: String? = null, now: Long = System.currentTimeMillis()): String {
        val key = if (generated != null) privateKey(generated) else config.privateKey
        require(key.length == 128 && key.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Private key must contain 128 hexadecimal characters"
        }
        val peers = config.peers.filter { it !in config.disabledPeers }.toMutableList()
        if (config.multicastListen) {
            peers += config.cachedPeers.filter {
                it.lastSeen > now - 60 * 60 * 1000L && it.successCount > it.failureCount &&
                    it.uri !in config.disabledPeers && it.uri !in peers
            }.map { it.uri }
        }
        val configuredPeers = peers.distinct().map { peer ->
            require(peer.isNotBlank() && peer.none { it.code < 32 || it.code == 127 }) {
                "Peer URI must be nonempty and contain no control characters"
            }
            if (config.maxBackoffEnabled && !peer.contains("maxbackoff=")) {
                peer + (if (peer.contains('?')) "&" else "?") + "maxbackoff=${config.maxBackoff}s"
            } else peer
        }
        val peerLines = if (configuredPeers.isEmpty()) {
            "peers = []"
        } else {
            "peers = [" + configuredPeers.joinToString(", ") { "\"$it\"" } + "]"
        }
        val multicastSection = if (config.multicastBeacon || config.multicastListen) {
            """
            [[multicast_interfaces]]
            filter = "*"
            beacon = ${config.multicastBeacon}
            listen = ${config.multicastListen}
            """.trimIndent()
        } else {
            "# multicast_interfaces disabled"
        }
        return buildString {
            appendLine("private_key = \"$key\"")
            appendLine(peerLines)
            appendLine("listen = [\"tcp://[::]:0\"]")
            appendLine("admin_listen = \"none\"")
            appendLine("if_name = \"none\"")
            appendLine("if_mtu = 65535")
            appendLine("node_info_privacy = false")
            // Closed-network group encryption (yggdrasil-ng core); empty = open network
            val groupPassword = if (config.groupPasswordEnabled) config.groupPassword else ""
            appendLine("group_password = \"${groupPassword.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
            appendLine()
            appendLine(multicastSection)
        }.trim() + "\n"
    }

    fun sanitize(value: String): String = value.replace(
        privateKeyRegex
    ) { _ -> "private_key = \"***\"" }
        .replace(
            Regex("""group_password\s*=\s*"([^"]*)"""")
        ) { _ -> "group_password = \"***\"" }
}
