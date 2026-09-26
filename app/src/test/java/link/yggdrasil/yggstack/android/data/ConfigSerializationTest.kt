package link.yggdrasil.yggstack.android.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConfigSerializationTest {
    private val key = "ab".repeat(64)

    @Test fun snapshotPreservesEveryNondefaultOption() {
        val config = YggstackConfig(
            peers = listOf("tls://example.org:1234"), privateKey = key,
            socksProxy = "127.0.0.1:1088", httpProxy = "127.0.0.1:8080",
            dnsServer = "[300::1]:53", proxyEnabled = true,
            exposeMappings = listOf(ExposeMapping(Protocol.TCP, 80, "127.0.0.2", 8080, "web", false)),
            exposeEnabled = true,
            forwardMappings = listOf(ForwardMapping(Protocol.UDP, "127.0.0.1", 1234, "300::1", 53, "dns", false)),
            forwardEnabled = true, multicastBeacon = true, multicastListen = true, logLevel = "debug",
            groupPasswordEnabled = true, groupPassword = "quote\" slash\\ newline\n tab\t\u0000$",
            cachedPeers = listOf(CachedPeer("tcp://cached:1", "multicast", 123L, 4, 2)),
            maxBackoffEnabled = true, maxBackoff = 27, disabledPeers = listOf("tls://disabled:1"),
            powerSaveEnabled = true, powerSaveIdleTimeoutSeconds = 95,
            powerSaveSleepOnPortsIdle = false, powerSaveWakeOnPortsActive = false,
            powerSaveSleepDuringScreenOff = true, powerSaveWakeOnScreenOn = true
        )
        val encoded = ConfigSerializer.encode(config)
        assertEquals(1, Json.parseToJsonElement(encoded).jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(config, ConfigSerializer.decode(encoded))
        assertEquals(YggstackConfig(), ConfigSerializer.decode(ConfigSerializer.encode(YggstackConfig())))
    }

    @Test fun powerSaveDefaultsMatchLegacyBehavior() {
        val defaults = YggstackConfig()
        assertFalse(defaults.powerSaveEnabled)
        assertEquals(60, defaults.powerSaveIdleTimeoutSeconds)
        assertTrue(defaults.powerSaveSleepOnPortsIdle)
        assertTrue(defaults.powerSaveWakeOnPortsActive)
        assertFalse(defaults.powerSaveSleepDuringScreenOff)
        assertFalse(defaults.powerSaveWakeOnScreenOn)
    }

    @Test fun oldSnapshotWithoutPowerSaveTogglesKeepsLegacyEvents() {
        // Pre-4-toggle snapshot: only powerSaveEnabled + timeout were persisted.
        // Decoding must fall back to the legacy defaults (events 1+2 on, screen events off).
        val restored = ConfigSerializer.decode(
            """{"version":1,"config":{"powerSaveEnabled":true,"powerSaveIdleTimeoutSeconds":30}}"""
        )
        assertTrue(restored.powerSaveEnabled)
        assertEquals(30, restored.powerSaveIdleTimeoutSeconds)
        assertTrue(restored.powerSaveSleepOnPortsIdle)
        assertTrue(restored.powerSaveWakeOnPortsActive)
        assertFalse(restored.powerSaveSleepDuringScreenOff)
        assertFalse(restored.powerSaveWakeOnScreenOn)
    }

    @Test fun maxBackoffDefaultsEnabledAtMinimum() {
        val defaults = YggstackConfig()
        assertTrue(defaults.maxBackoffEnabled)
        assertEquals(5, defaults.maxBackoff)
    }

    @Test fun oldSnapshotWithoutHttpProxyFieldKeepsEmptyDefault() {
        val restored = ConfigSerializer.decode(
            """{"version":1,"config":{"socksProxy":"127.0.0.1:1080","proxyEnabled":true}}"""
        )
        assertEquals("127.0.0.1:1080", restored.socksProxy)
        assertEquals("", restored.httpProxy)
    }

    @Test fun oldSnapshotWithoutPacFieldsKeepsPacDefaults() {
        val restored = ConfigSerializer.decode(
            """{"version":1,"config":{"proxyEnabled":true,"httpProxy":"127.0.0.1:8080"}}"""
        )
        assertFalse(restored.pacEnabled)
        assertEquals(8081, restored.pacPort)
        assertFalse(restored.pacAllTraffic)
    }

    @Test fun pacFieldsRoundTripThroughSnapshot() {
        val config = YggstackConfig(
            proxyEnabled = true, httpProxy = "127.0.0.1:8080",
            pacEnabled = true, pacPort = 9911, pacAllTraffic = true
        )
        assertEquals(config, ConfigSerializer.decode(ConfigSerializer.encode(config)))
    }

    @Test fun readsUnversionedRecoverySnapshot() {
        val restored = ConfigSerializer.decode("""{"privateKey":"abc","peers":[],"exposeMappings":[{"protocol":"TCP","localPort":80,"yggPort":80}],"maxBackoff":17}""")
        assertEquals("abc", restored.privateKey)
        assertEquals(17, restored.maxBackoff)
        assertTrue(restored.exposeMappings.single().enabled)
        // Snapshot omits the toggle, so the enabled-by-default kicks in.
        assertTrue(restored.maxBackoffEnabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSnapshotVersion() {
        ConfigSerializer.decode("""{"version":2,"config":{}}""")
    }
}
