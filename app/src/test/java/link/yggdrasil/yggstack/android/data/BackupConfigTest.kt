package link.yggdrasil.yggstack.android.data

import org.junit.Assert.*
import org.junit.Test

/** Backup import behavior for the maxBackoff settings and the HTTP proxy field. */
class BackupConfigTest {
    private val key = "ab".repeat(64)

    private fun toml(extraYggdrasilLines: String): String = """
        [yggdrasil]
        privateKey = "$key"
        peers = []
        multicastBeacon = false
        multicastListen = false
        $extraYggdrasilLines
        [proxy]
        enabled = false
        socksAddress = "127.0.0.1:1080"
        dnsServer = "[308:62:45:62::]:53"
    """.trimIndent()

    @Test fun backupWithoutMaxBackoffRestoresEnabledAtMinimum() {
        val backup = BackupConfig.fromToml(toml("")).getOrThrow()
        assertTrue(backup.yggdrasil!!.maxBackoffEnabled)
        assertEquals(5, backup.yggdrasil!!.maxBackoff)
        val applied = backup.applyTo(YggstackConfig(privateKey = key))
        assertTrue(applied.maxBackoffEnabled)
        assertEquals(5, applied.maxBackoff)
    }

    @Test fun outOfRangeMaxBackoffClampsToSliderRangeOnApply() {
        // A legacy round trip could persist 0 (or any hand-edited value).
        val backup = BackupConfig.fromToml(toml("maxBackoffEnabled = true\nmaxBackoff = 0")).getOrThrow()
        assertEquals(0, backup.yggdrasil!!.maxBackoff)
        assertEquals(5, backup.applyTo(YggstackConfig(privateKey = key)).maxBackoff)

        val high = BackupConfig.fromToml(toml("maxBackoff = 99")).getOrThrow()
        assertEquals(30, high.applyTo(YggstackConfig(privateKey = key)).maxBackoff)
    }

    @Test fun legacyJsonBackupWithoutMaxBackoffDecodesWithDefaults() {
        val json = """{"yggdrasil":{"privateKey":"abc","peers":[],"multicastBeacon":false,"multicastListen":false},
            "proxy":{"enabled":false,"socksAddress":"","dnsServer":""},
            "expose":{"enabled":false},"forward":{"enabled":false}}"""
        val backup = BackupConfig.fromJson(json).getOrThrow()
        assertTrue(backup.yggdrasil!!.maxBackoffEnabled)
        assertEquals(5, backup.yggdrasil!!.maxBackoff)
    }

    @Test fun httpProxyRoundTripsThroughToml() {
        val backup = BackupConfig.fromYggstackConfig(
            YggstackConfig(socksProxy = "127.0.0.1:1080", httpProxy = "127.0.0.1:8080", proxyEnabled = true)
        )
        val restored = BackupConfig.fromString(backup.toToml()).getOrThrow()
        assertEquals("127.0.0.1:8080", restored.proxy.httpAddress)
        val applied = restored.applyTo(YggstackConfig())
        assertEquals("127.0.0.1:8080", applied.httpProxy)
        assertEquals("127.0.0.1:1080", applied.socksProxy)
    }

    @Test fun legacyBackupWithoutHttpAddressImportsAsEmpty() {
        // TOML without an httpAddress line…
        val fromToml = BackupConfig.fromToml(toml("")).getOrThrow()
        assertEquals("", fromToml.proxy.httpAddress)
        assertEquals("", fromToml.applyTo(YggstackConfig()).httpProxy)
        // …and legacy JSON without an httpAddress key.
        val json = """{"proxy":{"enabled":true,"socksAddress":"127.0.0.1:1080","dnsServer":""},
            "expose":{"enabled":false},"forward":{"enabled":false}}"""
        val fromJson = BackupConfig.fromJson(json).getOrThrow()
        assertEquals("", fromJson.proxy.httpAddress)
    }

    @Test fun pacSettingsRoundTripThroughTomlAndLegacyImportsDefault() {
        val backup = BackupConfig.fromYggstackConfig(
            YggstackConfig(
                proxyEnabled = true, httpProxy = "127.0.0.1:8080",
                pacEnabled = true, pacPort = 9911, pacAllTraffic = true
            )
        )
        val restored = BackupConfig.fromString(backup.toToml()).getOrThrow()
        assertTrue(restored.proxy.pacEnabled)
        assertEquals(9911, restored.proxy.pacPort)
        assertTrue(restored.proxy.pacAllTraffic)
        val applied = restored.applyTo(YggstackConfig())
        assertTrue(applied.pacEnabled)
        assertEquals(9911, applied.pacPort)
        assertTrue(applied.pacAllTraffic)

        // Legacy TOML without PAC keys → defaults.
        val legacy = BackupConfig.fromToml(toml("")).getOrThrow()
        assertFalse(legacy.proxy.pacEnabled)
        assertEquals(8081, legacy.proxy.pacPort)
        assertFalse(legacy.proxy.pacAllTraffic)
    }
}
