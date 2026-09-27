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
                pacEnabled = true, pacIp = "192.168.1.5", pacPort = 9911, pacAllTraffic = true
            )
        )
        val restored = BackupConfig.fromString(backup.toToml()).getOrThrow()
        assertTrue(restored.proxy.pacEnabled)
        assertEquals("192.168.1.5", restored.proxy.pacIp)
        assertEquals(9911, restored.proxy.pacPort)
        assertTrue(restored.proxy.pacAllTraffic)
        val applied = restored.applyTo(YggstackConfig())
        assertTrue(applied.pacEnabled)
        assertEquals("192.168.1.5", applied.pacIp)
        assertEquals(9911, applied.pacPort)
        assertTrue(applied.pacAllTraffic)

        // Legacy TOML without PAC keys → defaults.
        val legacy = BackupConfig.fromToml(toml("")).getOrThrow()
        assertFalse(legacy.proxy.pacEnabled)
        assertEquals("127.0.0.1", legacy.proxy.pacIp)
        assertEquals(8081, legacy.proxy.pacPort)
        assertFalse(legacy.proxy.pacAllTraffic)
    }

    @Test fun perProxyEnableTicksRoundTripThroughToml() {
        val backup = BackupConfig.fromYggstackConfig(
            YggstackConfig(
                socksProxy = "127.0.0.1:1080", httpProxy = "127.0.0.1:8080",
                proxyEnabled = true, socksEnabled = false, httpEnabled = true
            )
        )
        val restored = BackupConfig.fromString(backup.toToml()).getOrThrow()
        assertFalse(restored.proxy.socksEnabled)
        assertTrue(restored.proxy.httpEnabled)
        val applied = restored.applyTo(YggstackConfig())
        assertFalse(applied.socksEnabled)
        assertTrue(applied.httpEnabled)
        // The unticked proxy's address survives the round trip.
        assertEquals("127.0.0.1:1080", applied.socksProxy)
    }

    @Test fun legacyBackupWithoutEnableTicksEnablesOnlySocks() {
        // Legacy TOML without socksEnabled/httpEnabled lines…
        val fromToml = BackupConfig.fromToml(toml("")).getOrThrow()
        assertTrue(fromToml.proxy.socksEnabled)
        assertFalse(fromToml.proxy.httpEnabled)
        assertTrue(fromToml.applyTo(YggstackConfig()).socksEnabled)
        // …and legacy JSON without the keys.
        val json = """{"proxy":{"enabled":true,"socksAddress":"127.0.0.1:1080","dnsServer":""},
            "expose":{"enabled":false},"forward":{"enabled":false}}"""
        val fromJson = BackupConfig.fromJson(json).getOrThrow()
        assertTrue(fromJson.proxy.socksEnabled)
        assertFalse(fromJson.proxy.httpEnabled)
    }

    @Test fun mappingNotesRoundTripThroughToml() {
        // Multiline note with quotes, backslash, tab and CRLF — the nastiest
        // case for the line-by-line TOML parser; everything must stay escaped
        // onto one physical line and come back byte-identical.
        val tricky = "api key: \"abc\"\\42\nline two with back\\slash and\ttab\r\nend"
        val backup = BackupConfig.fromYggstackConfig(
            YggstackConfig(
                exposeEnabled = true,
                exposeMappings = listOf(ExposeMapping(Protocol.TCP, 80, "127.0.0.1", 8080, "web", tricky)),
                forwardEnabled = true,
                forwardMappings = listOf(
                    ForwardMapping(Protocol.UDP, "127.0.0.1", 1234, "300::1", 53, "dns", "secret: qwerty123")
                )
            )
        )
        val restored = BackupConfig.fromString(backup.toToml()).getOrThrow()
        assertEquals(tricky, restored.expose.mappings.single().note)
        assertEquals("secret: qwerty123", restored.forward.mappings.single().note)
        val applied = restored.applyTo(YggstackConfig())
        assertEquals(tricky, applied.exposeMappings.single().note)
        assertEquals("secret: qwerty123", applied.forwardMappings.single().note)
    }

    @Test fun legacyBackupWithoutNoteImportsAsEmpty() {
        val backup = BackupConfig.fromToml("""
            [proxy]
            enabled = false
            socksAddress = "127.0.0.1:1080"
            dnsServer = ""
            [expose]
            enabled = true
            [[expose.mappings]]
            localPort = 80
            localIp = "127.0.0.1"
            yggPort = 8080
            protocol = "TCP"
            shortName = "web"
        """.trimIndent()).getOrThrow()
        assertEquals("", backup.expose.mappings.single().note)
    }
}
