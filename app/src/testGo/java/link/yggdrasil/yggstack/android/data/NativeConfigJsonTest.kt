package link.yggdrasil.yggstack.android.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Go-engine native config (JSON) behavior. */
class NativeConfigJsonTest {
    private val key = "ab".repeat(64)

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

    @Test fun sanitizeKeepsEmptyGroupPasswordVisibleWhenDisabled() {
        // A stale stored password must not leak or look masked when the toggle is off
        val config = YggstackConfig(privateKey = key, groupPasswordEnabled = false, groupPassword = "stale")
        val redacted = Json.parseToJsonElement(NativeConfigJson.sanitize(NativeConfigJson.build(config))).jsonObject
        assertEquals("", redacted.getValue("GroupPassword").jsonPrimitive.content)
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
