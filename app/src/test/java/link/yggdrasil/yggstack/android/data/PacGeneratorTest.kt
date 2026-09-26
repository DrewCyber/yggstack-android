package link.yggdrasil.yggstack.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacGeneratorTest {

    private fun config(
        proxyEnabled: Boolean = true,
        http: String = "127.0.0.1:8080",
        socks: String = "",
        allTraffic: Boolean = false
    ) = YggstackConfig(
        proxyEnabled = proxyEnabled,
        socksProxy = socks,
        httpProxy = http,
        pacEnabled = true,
        pacAllTraffic = allTraffic
    )

    private val pac = PacGenerator.generate(config())

    @Test fun yggOnlyRoutesYggThroughProxyAndEverythingElseDirect() {
        val script = pac
        assertTrue(script.contains("""dnsDomainIs(host, ".ygg")"""))
        assertTrue(script.contains("PROXY 127.0.0.1:8080"))
        assertTrue(script.contains("return \"DIRECT\""))
        // No DIRECT fallback on the proxy rule: .ygg names cannot resolve
        // via system DNS, a fallback would only add a resolver timeout.
        val proxyLine = script.lines().first { it.contains("PROXY") }
        assertFalse(proxyLine.contains("DIRECT"))
    }

    @Test fun httpProxyOnlyWhenSocksDisabled() {
        assertFalse(pac.contains("SOCKS"))
    }

    @Test fun socksAppendedAsFallbackWhenEnabled() {
        val script = PacGenerator.generate(config(socks = "127.0.0.1:1080"))
        assertTrue(script.contains("PROXY 127.0.0.1:8080; SOCKS 127.0.0.1:1080"))
    }

    @Test fun socksOnlyWhenHttpDisabled() {
        val script = PacGenerator.generate(config(http = "", socks = "127.0.0.1:1080"))
        assertTrue(script.contains("SOCKS 127.0.0.1:1080"))
        assertFalse(script.contains("PROXY "))
    }

    @Test fun wildcardListenAddressesNormalizeToLoopback() {
        val script = PacGenerator.generate(config(http = "0.0.0.0:8080", socks = "[::]:1080"))
        assertTrue(script.contains("PROXY 127.0.0.1:8080; SOCKS 127.0.0.1:1080"))
    }

    @Test fun allTrafficModeBypassesLocalAddresses() {
        val script = PacGenerator.generate(config(allTraffic = true))
        assertTrue(script.contains("isPlainHostName(host)"))
        assertTrue(script.contains("shExpMatch(host, \"localhost\")"))
        assertTrue(script.contains("shExpMatch(host, \"192.168.*\")"))
        assertTrue(script.contains("shExpMatch(host, \"169.254.*\")"))
        // The default return is the proxy, not DIRECT.
        val lastReturn = script.lines().last { it.contains("return \"") }
        assertTrue(lastReturn.contains("PROXY"))
    }

    @Test fun allTrafficModeKeeps172PrivateRangeCoverage() {
        val script = PacGenerator.generate(config(allTraffic = true))
        // 172.20-172.29 covered by the 172.2?.* pattern.
        assertTrue(script.contains("shExpMatch(host, \"172.2?.*\")"))
        assertTrue(script.contains("shExpMatch(host, \"172.16.*\")"))
        assertTrue(script.contains("shExpMatch(host, \"172.31.*\")"))
    }

    @Test fun noUsableProxyServesAllDirect() {
        val script = PacGenerator.generate(config(http = "", socks = ""))
        assertTrue(script.contains("return \"DIRECT\""))
        assertFalse(script.contains("PROXY"))
        assertFalse(script.contains("SOCKS"))
    }

    @Test fun proxyDisabledYieldsNoProxyRules() {
        val script = PacGenerator.generate(config(proxyEnabled = false))
        assertFalse(script.contains("PROXY"))
    }

    @Test fun pacUrlShape() {
        assertEquals("http://127.0.0.1:8081/proxy.pac", PacGenerator.pacUrl(8081))
        assertEquals("/proxy.pac", PacGenerator.PAC_PATH)
    }
}
