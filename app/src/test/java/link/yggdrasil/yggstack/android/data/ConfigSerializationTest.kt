package link.yggdrasil.yggstack.android.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConfigSerializationTest {
    private val key = "ab".repeat(64)

    @Test fun snapshotPreservesEveryNondefaultOption() {
        val config = YggstackConfig(
            peers = listOf("tls://example.org:1234"), privateKey = key,
            socksProxy = "127.0.0.1:1088", dnsServer = "[300::1]:53", proxyEnabled = true,
            exposeMappings = listOf(ExposeMapping(Protocol.TCP, 80, "127.0.0.2", 8080, "web", false)),
            exposeEnabled = true,
            forwardMappings = listOf(ForwardMapping(Protocol.UDP, "127.0.0.1", 1234, "300::1", 53, "dns", false)),
            forwardEnabled = true, multicastBeacon = true, multicastListen = true, logLevel = "debug",
            groupPasswordEnabled = true, groupPassword = "quote\" slash\\ newline\n tab\t\u0000$",
            cachedPeers = listOf(CachedPeer("tcp://cached:1", "multicast", 123L, 4, 2)),
            maxBackoffEnabled = true, maxBackoff = 27, disabledPeers = listOf("tls://disabled:1"),
            powerSaveEnabled = true, powerSaveIdleTimeoutSeconds = 95
        )
        val encoded = ConfigSerializer.encode(config)
        assertEquals(1, Json.parseToJsonElement(encoded).jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(config, ConfigSerializer.decode(encoded))
        assertEquals(YggstackConfig(), ConfigSerializer.decode(ConfigSerializer.encode(YggstackConfig())))
    }

    @Test fun readsUnversionedRecoverySnapshot() {
        val restored = ConfigSerializer.decode("""{"privateKey":"abc","peers":[],"exposeMappings":[{"protocol":"TCP","localPort":80,"yggPort":80}],"maxBackoff":17}""")
        assertEquals("abc", restored.privateKey)
        assertEquals(17, restored.maxBackoff)
        assertTrue(restored.exposeMappings.single().enabled)
        assertFalse(restored.maxBackoffEnabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSnapshotVersion() {
        ConfigSerializer.decode("""{"version":2,"config":{}}""")
    }
}
