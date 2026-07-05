package link.yggdrasil.yggstack.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigRepositoryTest {

    @Test
    fun normalizeDnsServer_handlesExpectedIpv4AndIpv6Forms() {
        val cases = listOf(
            "308:62:45:62::" to "[308:62:45:62::]:53",
            "[308:62:45:62::]" to "[308:62:45:62::]:53",
            "[308:62:45:62::]:53" to "[308:62:45:62::]:53",
            "[308:62:45:62::]:1023" to "[308:62:45:62::]:1023",
            "1.1.1.1" to "1.1.1.1:53",
            "1.1.1.1:53" to "1.1.1.1:53",
            "1.1.1.1:1024" to "1.1.1.1:1024"
        )

        cases.forEach { (input, expected) ->
            assertEquals(expected, ConfigRepository.normalizeDnsServer(input))
        }
    }
}