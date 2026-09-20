package link.yggdrasil.yggstackng.android.data

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

    @Test fun bothNativeBuildPathsEncodeSpecialCharacters() {
        val password = "quote\" slash\\ newline\n tab\t\r\b\u0000$1"
        val peer = "tls://example.org:1234?password=quote\"slash\\$1"
        val config = YggstackConfig(privateKey = key, peers = listOf(peer), groupPasswordEnabled = true,
            groupPassword = password, maxBackoffEnabled = true, maxBackoff = 19, multicastListen = true)
        val generated = """{"PrivateKey":"$key","MulticastInterfaces":[{"Regex":"en.*","Port":1234}],"NodeInfo":{"test":"preserved"}}"""
        for (base in listOf(null, generated)) {
            val result = Json.parseToJsonElement(NativeConfigJson.build(config, base)).jsonObject
            assertEquals(password, result.getValue("GroupPassword").jsonPrimitive.content)
            assertEquals("$peer&maxbackoff=19s", result.getValue("Peers").jsonArray.single().jsonPrimitive.content)
            assertTrue(result.getValue("MulticastInterfaces").jsonArray.single().jsonObject.getValue("Listen").jsonPrimitive.boolean)
            val redacted = NativeConfigJson.sanitize(result.toString())
            assertFalse(redacted.contains(key))
            assertEquals("***", Json.parseToJsonElement(redacted).jsonObject.getValue("GroupPassword").jsonPrimitive.content)
        }
    }

    @Test fun filtersDisabledAndStalePeersForEitherIdentityPath() {
        val config = YggstackConfig(privateKey = key, peers = listOf("tcp://disabled:1", "tcp://active:1"),
            disabledPeers = listOf("tcp://disabled:1"), multicastListen = true,
            cachedPeers = listOf(CachedPeer("tcp://fresh:1", "multicast", 4000000, 2, 0), CachedPeer("tcp://stale:1", "multicast", 0, 2, 0)))
        val result = Json.parseToJsonElement(NativeConfigJson.build(config, now = 4000001)).jsonObject
        assertEquals(listOf("tcp://active:1", "tcp://fresh:1"), result.getValue("Peers").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedKeyBeforeBinding() { NativeConfigJson.build(YggstackConfig(privateKey = "short")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPeerControlCharactersBeforeBinding() {
        NativeConfigJson.build(YggstackConfig(privateKey = key, peers = listOf("tcp://host:1\n")))
    }
}
