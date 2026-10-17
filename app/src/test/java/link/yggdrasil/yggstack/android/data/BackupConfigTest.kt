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

    @Test fun fromTomlRejectsContentWithNoYggstackSections() {
        // A non-backup file picked by mistake (PDF, spreadsheet, random text):
        // the lenient parser would otherwise yield an all-defaults backup that
        // wipes the config on confirm.
        val garbage = listOf(
            "%PDF-1.7\n%âãÏÓ\n2 0 obj\n<< /Type /Catalog >>",
            "Quarterly report\n================\nrevenue = 1234567\nlosses = high",
            "just some words without structure",
            ""   // empty file
        )
        garbage.forEach { content ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                BackupConfig.fromToml(content).getOrThrow()
            }
            assertEquals("Not a yggstack backup file", error.message)
        }
    }

    @Test fun fromTomlRejectsLegacyJsonBackup() {
        // JSON support was removed — all backups are TOML now; the old format
        // must fail parsing, not silently import as an empty config.
        val legacyJson = """{"yggdrasil":{"privateKey":"abc","peers":[]},
            "proxy":{"enabled":false,"socksAddress":"","dnsServer":""},
            "expose":{"enabled":false},"forward":{"enabled":false}}"""
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupConfig.fromToml(legacyJson).getOrThrow()
        }
        assertEquals("Not a yggstack backup file", error.message)
    }

    @Test fun minimalBackupWithSingleKnownSectionParses() {
        val backup = BackupConfig.fromToml("[forward]\nenabled = true").getOrThrow()
        assertTrue(backup.forward.enabled)
    }

    @Test fun httpProxyRoundTripsThroughToml() {
        val backup = BackupConfig.fromYggstackConfig(
            YggstackConfig(socksProxy = "127.0.0.1:1080", httpProxy = "127.0.0.1:8080", proxyEnabled = true)
        )
        val restored = BackupConfig.fromToml(backup.toToml()).getOrThrow()
        assertEquals("127.0.0.1:8080", restored.proxy.httpAddress)
        val applied = restored.applyTo(YggstackConfig())
        assertEquals("127.0.0.1:8080", applied.httpProxy)
        assertEquals("127.0.0.1:1080", applied.socksProxy)
    }

    @Test fun listenEntriesRoundTripThroughTomlAndLegacyImportsAsEmpty() {
        val backup = BackupConfig.fromYggstackConfig(
            YggstackConfig(privateKey = key, listenEnabled = true,
                listenEntries = listOf(
                    ListenEntry(ListenScheme.TCP, "0.0.0.0", 1234),
                    ListenEntry(ListenScheme.QUIC, "192.168.1.5", 4321))),
            includeYggdrasil = true
        )
        val restored = BackupConfig.fromToml(backup.toToml()).getOrThrow()
        assertEquals(listOf("tcp://0.0.0.0:1234", "quic://192.168.1.5:4321"),
            restored.yggdrasil!!.listenEntries.map { it.toUri() })
        assertTrue(restored.yggdrasil!!.listenEnabled)
        val applied = restored.applyTo(YggstackConfig(privateKey = key))
        assertEquals(backup.yggdrasil!!.listenEntries, applied.listenEntries)
        assertTrue(applied.listenEnabled)

        // Legacy backup without listen lines: entries empty, tick off
        val legacy = BackupConfig.fromToml(toml("")).getOrThrow()
        assertTrue(legacy.yggdrasil!!.listenEntries.isEmpty())
        assertFalse(legacy.applyTo(YggstackConfig(privateKey = key)).listenEnabled)

        // Malformed listen URIs in a hand-edited backup are skipped, not fatal
        val handEdited = BackupConfig.fromToml(toml("listen = [\"tcp://0.0.0.0:99\", \"bogus\"]")).getOrThrow()
        assertEquals(listOf("tcp://0.0.0.0:99"), handEdited.yggdrasil!!.listenEntries.map { it.toUri() })
    }

    @Test fun failoverDnsServerRoundTripsThroughToml() {
        val backup = BackupConfig.fromYggstackConfig(
            YggstackConfig(dnsServer = "[308:1::]:53", dnsServer2 = "[308:2::]:53", proxyEnabled = true)
        )
        val restored = BackupConfig.fromToml(backup.toToml()).getOrThrow()
        assertEquals("[308:2::]:53", restored.proxy.dnsServer2)
        val applied = restored.applyTo(YggstackConfig())
        assertEquals("[308:1::]:53", applied.dnsServer)
        assertEquals("[308:2::]:53", applied.dnsServer2)
    }

    @Test fun legacyBackupWithoutDnsServer2ImportsAsEmpty() {
        val fromToml = BackupConfig.fromToml(toml("")).getOrThrow()
        assertEquals("", fromToml.proxy.dnsServer2)
        assertEquals("", fromToml.applyTo(YggstackConfig()).dnsServer2)
    }

    @Test fun legacyBackupWithoutHttpAddressImportsAsEmpty() {
        val fromToml = BackupConfig.fromToml(toml("")).getOrThrow()
        assertEquals("", fromToml.proxy.httpAddress)
        assertEquals("", fromToml.applyTo(YggstackConfig()).httpProxy)
    }

    @Test fun pacSettingsRoundTripThroughTomlAndLegacyImportsDefault() {
        val backup = BackupConfig.fromYggstackConfig(
            YggstackConfig(
                proxyEnabled = true, httpProxy = "127.0.0.1:8080",
                pacEnabled = true, pacIp = "192.168.1.5", pacPort = 9911, pacAllTraffic = true
            )
        )
        val restored = BackupConfig.fromToml(backup.toToml()).getOrThrow()
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
        val restored = BackupConfig.fromToml(backup.toToml()).getOrThrow()
        assertFalse(restored.proxy.socksEnabled)
        assertTrue(restored.proxy.httpEnabled)
        val applied = restored.applyTo(YggstackConfig())
        assertFalse(applied.socksEnabled)
        assertTrue(applied.httpEnabled)
        // The unticked proxy's address survives the round trip.
        assertEquals("127.0.0.1:1080", applied.socksProxy)
    }

    @Test fun legacyBackupWithoutEnableTicksEnablesOnlySocks() {
        val fromToml = BackupConfig.fromToml(toml("")).getOrThrow()
        assertTrue(fromToml.proxy.socksEnabled)
        assertFalse(fromToml.proxy.httpEnabled)
        assertTrue(fromToml.applyTo(YggstackConfig()).socksEnabled)
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
        val restored = BackupConfig.fromToml(backup.toToml()).getOrThrow()
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
