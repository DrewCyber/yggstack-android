package link.yggdrasil.yggstack.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal object ConfigSerializer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Snapshot(val version: Int = 1, val config: YggstackConfig)

    fun encode(config: YggstackConfig): String = json.encodeToString(Snapshot(config = config))

    fun decode(value: String): YggstackConfig {
        val root = json.parseToJsonElement(value).jsonObject
        if ("version" !in root) {
            // Unversioned recovery snapshots cannot recover fields that were never saved.
            return json.decodeFromString<YggstackConfig>(value)
        }
        val snapshot = json.decodeFromString<Snapshot>(value)
        require(snapshot.version == 1) { "Unsupported configuration version: ${snapshot.version}" }
        return snapshot.config
    }
}
