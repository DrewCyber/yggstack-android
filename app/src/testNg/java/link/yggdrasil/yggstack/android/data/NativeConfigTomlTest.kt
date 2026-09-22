package link.yggdrasil.yggstack.android.data

import org.junit.Assert.*
import org.junit.Test

/** ng-engine native config (TOML) behavior — mirrors NativeConfigJsonTest. */
class NativeConfigTomlTest {
    private val key = "ab".repeat(64)

    @Test fun buildsTomlWithPeersMulticastAndGroupPassword() {
        val config = YggstackConfig(
            privateKey = key, peers = listOf("tcp://active:1"),
            multicastBeacon = true, multicastListen = false,
            groupPasswordEnabled = true, groupPassword = "secret\"pass",
            maxBackoffEnabled = true, maxBackoff = 19
        )
        val toml = NativeConfigToml.build(config)
        assertTrue(toml.contains("private_key = \"$key\""))
        // group password escapes TOML-special characters
        assertTrue(toml.contains("group_password = \"secret\\\"pass\""))
        assertEquals(listOf("tcp://active:1?maxbackoff=19s"), peersOf(toml))
        assertTrue(toml.contains("beacon = true"))
        assertTrue(toml.contains("listen = false"))
    }

    @Test fun disablesMulticastSectionWhenOff() {
        val toml = NativeConfigToml.build(YggstackConfig(privateKey = key))
        // Explicit empty list — an absent field would fall back to the
        // core's non-empty serde default and keep multicast running.
        assertTrue(toml.contains("multicast_interfaces = []"))
        assertEquals(emptyList<String>(), peersOf(toml))
    }

    @Test fun filtersDisabledAndStalePeers() {
        val config = YggstackConfig(privateKey = key, peers = listOf("tcp://disabled:1", "tcp://active:1"),
            disabledPeers = listOf("tcp://disabled:1"), multicastListen = true,
            cachedPeers = listOf(CachedPeer("tcp://fresh:1", "multicast", 4000000, 2, 0), CachedPeer("tcp://stale:1", "multicast", 0, 2, 0)))
        assertEquals(listOf("tcp://active:1", "tcp://fresh:1"), peersOf(NativeConfigToml.build(config, now = 4000001)))
    }

    @Test fun extractsKeyFromGeneratedToml() {
        val generated = "if_name = \"auto\"\nprivate_key = \"$key\"\npeers = []\n"
        assertEquals(key, NativeConfigToml.privateKey(generated))
    }

    @Test fun sanitizeMasksSecrets() {
        val toml = NativeConfigToml.build(
            YggstackConfig(privateKey = key, groupPasswordEnabled = true, groupPassword = "secret")
        )
        val redacted = NativeConfigToml.sanitize(toml)
        assertFalse(redacted.contains(key))
        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains("private_key = \"***\""))
        assertTrue(redacted.contains("group_password = \"***\""))
    }

    @Test fun sanitizeKeepsEmptyGroupPasswordVisibleWhenDisabled() {
        val toml = NativeConfigToml.build(
            // A stale stored password must not leak or look masked when the toggle is off
            YggstackConfig(privateKey = key, groupPasswordEnabled = false, groupPassword = "stale")
        )
        val redacted = NativeConfigToml.sanitize(toml)
        assertFalse(redacted.contains("stale"))
        assertTrue(redacted.contains("group_password = \"\""))
        assertFalse(redacted.contains("group_password = \"***\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedKeyBeforeBinding() { NativeConfigToml.build(YggstackConfig(privateKey = "short")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPeerControlCharactersBeforeBinding() {
        NativeConfigToml.build(YggstackConfig(privateKey = key, peers = listOf("tcp://host:1\n")))
    }

    /** Extract the quoted peer URIs from the single `peers = [...]` line. */
    private fun peersOf(toml: String): List<String> {
        val line = toml.lineSequence().first { it.startsWith("peers = ") }
        if (line == "peers = []") return emptyList()
        return Regex("\"([^\"]*)\"").findAll(line.removePrefix("peers = [").removeSuffix("]"))
            .map { it.groupValues[1] }.toList()
    }
}
