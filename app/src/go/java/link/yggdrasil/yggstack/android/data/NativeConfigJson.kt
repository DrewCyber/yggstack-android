package link.yggdrasil.yggstack.android.data

import kotlinx.serialization.json.*

internal object NativeConfigJson {
    private val json = Json { prettyPrint = true }

    fun privateKey(value: String): String = json.parseToJsonElement(value).jsonObject
        .getValue("PrivateKey").jsonPrimitive.content

    fun build(config: YggstackConfig, generated: String? = null, now: Long = System.currentTimeMillis()): String {
        val fields = generated?.let { json.parseToJsonElement(it).jsonObject.toMutableMap() }
            ?: mutableMapOf()
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
        fields["PrivateKey"] = JsonPrimitive(key)
        fields.putIfAbsent("Certificate", JsonNull)
        fields["Peers"] = JsonArray(configuredPeers.map(::JsonPrimitive))
        fields.putIfAbsent("InterfacePeers", JsonObject(emptyMap()))
        fields["Listen"] = JsonArray(emptyList())
        fields["AdminListen"] = JsonPrimitive("none")
        fields["MulticastInterfaces"] = JsonArray(if (config.multicastBeacon || config.multicastListen) {
            val defaults = (fields["MulticastInterfaces"] as? JsonArray)?.takeIf { it.isNotEmpty() }
                ?: JsonArray(listOf(buildJsonObject { put("Regex", ".*"); put("Password", "") }))
            defaults.map { item -> JsonObject(item.jsonObject.toMutableMap().apply {
                put("Beacon", JsonPrimitive(config.multicastBeacon))
                put("Listen", JsonPrimitive(config.multicastListen))
            }) }
        } else emptyList())
        fields.putIfAbsent("AllowedPublicKeys", JsonArray(emptyList()))
        fields["GroupPassword"] = JsonPrimitive(if (config.groupPasswordEnabled) config.groupPassword else "")
        fields.putIfAbsent("IfName", JsonPrimitive("auto"))
        fields.putIfAbsent("IfMTU", JsonPrimitive(65535))
        fields.putIfAbsent("NodeInfoPrivacy", JsonPrimitive(false))
        fields.putIfAbsent("NodeInfo", JsonNull)
        return json.encodeToString(JsonObject.serializer(), JsonObject(fields))
    }

    fun sanitize(value: String): String {
        val fields = json.parseToJsonElement(value).jsonObject.toMutableMap()
        fields["PrivateKey"] = JsonPrimitive("***")
        // An empty GroupPassword means the feature is off — masking it would
        // fabricate a secret where the console-generated config shows "".
        if ((fields["GroupPassword"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true) {
            fields["GroupPassword"] = JsonPrimitive("***")
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(fields))
    }
}
