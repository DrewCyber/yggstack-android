package link.yggdrasil.yggstack.android.data

/**
 * Built-in Forward Remote Port presets offered by the Add Mapping picker.
 *
 * A preset's identity is protocol + remote IP + remote port; the local side
 * (and name/note) are only a prefill the user may adjust, so they don't count
 * toward "already added".
 */
object ForwardPresets {

    val all: List<ForwardMapping> = listOf(
        ForwardMapping(
            protocol = Protocol.TCP,
            localIp = "127.0.0.1",
            localPort = 1081,
            remoteIp = "221:762c:64a9:e47c:da08:6b98:cbb:92d5",
            remotePort = 1080,
            shortName = "SOCKS5 proxy 1081",
            note = "Outproxy by Acetone"
        ),
        ForwardMapping(
            protocol = Protocol.TCP,
            localIp = "127.0.0.1",
            localPort = 1082,
            remoteIp = "200:e3d6:1401:5aad:f6fd:f23:2e81:df59",
            remotePort = 1080,
            shortName = "SOCKS5 proxy 1082",
            note = "Outproxy by Makcim"
        ),
        ForwardMapping(
            protocol = Protocol.TCP,
            localIp = "127.0.0.1",
            localPort = 8443,
            remoteIp = "321:c99a:91a1:cd2c::1",
            remotePort = 8443,
            shortName = "Telegram MTProto 8443",
            note = "https://t.me/proxy?server=127.0.0.1&port=8443&secret=1b3e58008e4a2838505ea99838db21df"
        ),
        ForwardMapping(
            protocol = Protocol.TCP,
            localIp = "127.0.0.1",
            localPort = 8843,
            remoteIp = "208:84:68:55:2f91:8484:8d60:2fca",
            remotePort = 8843,
            shortName = "Telegram MTProto 8843",
            note = "by Revertron\nhttps://t.me/proxy?server=127.0.0.1&port=8843&secret=dd208e184d06275b7e07b56459d1efd409"
        ),
        ForwardMapping(
            protocol = Protocol.TCP,
            localIp = "127.0.0.1",
            localPort = 1083,
            remoteIp = "200:e3d6:1401:5aad:f6fd:f23:2e81:df59",
            remotePort = 1083,
            shortName = "Shadowsocks 1083",
            note = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTowMDAwMDAwMA@127.0.0.1:1083\nby Makcim"
        ),
        ForwardMapping(
            protocol = Protocol.TCP,
            localIp = "127.0.0.1",
            localPort = 5201,
            remoteIp = "200:e3d6:1401:5aad:f6fd:f23:2e81:df59",
            remotePort = 5201,
            shortName = "Iperf3 5201",
            note = "by Makcim"
        )
    )

    /** True when an existing mapping matches the preset's remote endpoint. */
    fun isAdded(preset: ForwardMapping, existing: List<ForwardMapping>): Boolean =
        existing.any {
            it.protocol == preset.protocol &&
                it.remoteIp == preset.remoteIp &&
                it.remotePort == preset.remotePort
        }
}
