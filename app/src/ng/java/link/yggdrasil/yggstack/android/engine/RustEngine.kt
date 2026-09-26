package link.yggdrasil.yggstack.android.engine

import link.yggdrasil.yggstack.android.data.NativeConfigToml
import link.yggdrasil.yggstack.android.data.YggstackConfig
import uniffi.yggstack_mobile.LogCallback
import uniffi.yggstack_mobile.YggstackException
import uniffi.yggstack_mobile.YggstackMobile

/**
 * ng-flavor engine: adapter over the Rust yggstack-ng UniFFI bindings.
 *
 * The Rust library works in TOML and spec strings ("localPort:[remote]:port",
 * "yggPort:localIp:localPort"); this adapter converts from the Go-shaped
 * [NativeEngine] call surface the service code uses.
 */
internal class RustEngine : NativeEngine {
    private val yggstack: YggstackMobile = YggstackMobile()

    override fun setLogCallback(callback: NativeLogCallback) {
        yggstack.setLogCallback(object : LogCallback {
            override fun onLog(message: String) {
                callback.onLog(message)
            }
        })
    }

    override fun setLogLevel(level: String) = yggstack.setLogLevel(level)

    override fun generateConfigText(): String = uniffi.yggstack_mobile.generateConfig()

    override fun buildNativeConfig(config: YggstackConfig, generated: String?): String =
        NativeConfigToml.build(config, generated)

    override fun privateKeyOf(nativeConfig: String): String = NativeConfigToml.privateKey(nativeConfig)

    override fun sanitizeNativeConfig(nativeConfig: String): String =
        NativeConfigToml.sanitize(nativeConfig)

    override fun loadConfig(nativeConfig: String) = yggstack.loadConfig(nativeConfig)

    override fun start(socksAddress: String, httpAddress: String, dnsServer: String) {
        yggstack.setSocks(socksAddress)
        yggstack.setHttp(httpAddress)
        yggstack.setNameserver(dnsServer)
        try {
            yggstack.start()
        } catch (e: YggstackException.AlreadyRunning) {
            // Match Go semantics: starting a freshly created engine instance
        }
    }

    override fun stop() = yggstack.stop()

    override fun isRunning(): Boolean = yggstack.isRunning()

    override fun getAddress(): String? = yggstack.getAddress()

    override fun getPublicKey(): String? = yggstack.getPublicKey()

    override fun getSubnet(): String? = yggstack.getSubnet()

    override fun addLocalTcpMapping(localAddr: String, remoteAddr: String) =
        yggstack.addLocalTcp(localForwardSpec(localAddr, remoteAddr))

    override fun addLocalUdpMapping(localAddr: String, remoteAddr: String) =
        yggstack.addLocalUdp(localForwardSpec(localAddr, remoteAddr))

    override fun removeLocalTcpMapping(localAddr: String, remoteAddr: String) =
        yggstack.removeLocalTcp(localForwardSpec(localAddr, remoteAddr))

    override fun removeLocalUdpMapping(localAddr: String, remoteAddr: String) =
        yggstack.removeLocalUdp(localForwardSpec(localAddr, remoteAddr))

    override fun addRemoteTcpMapping(remotePort: Long, localAddr: String) =
        yggstack.addRemoteTcp(remoteExposeSpec(remotePort, localAddr))

    override fun addRemoteUdpMapping(remotePort: Long, localAddr: String) =
        yggstack.addRemoteUdp(remoteExposeSpec(remotePort, localAddr))

    override fun removeRemoteTcpMapping(remotePort: Long, localAddr: String) =
        yggstack.removeRemoteTcp(remoteExposeSpec(remotePort, localAddr))

    override fun removeRemoteUdpMapping(remotePort: Long, localAddr: String) =
        yggstack.removeRemoteUdp(remoteExposeSpec(remotePort, localAddr))

    override fun clearLocalMappings() = yggstack.clearMappings()

    override fun clearRemoteMappings() = yggstack.clearMappings()

    override fun addLivePeer(uri: String) = yggstack.addLivePeer(uri)

    override fun removeLivePeer(uri: String) = yggstack.removeLivePeer(uri)

    override fun retryPeersNow() = yggstack.retryPeersNow()

    override fun getPeersJson(): String? = yggstack.getPeersJson()

    override fun getListenersJson(): String? = yggstack.getListenersJson()

    private fun localForwardSpec(localAddr: String, remoteAddr: String): String {
        val (localIp, localPort) = splitHostPort(localAddr)
        val (remoteIp, remotePort) = splitHostPort(remoteAddr)
        return "$localIp:$localPort:[$remoteIp]:$remotePort"
    }

    private fun remoteExposeSpec(remotePort: Long, localAddr: String): String {
        val (localIp, localPort) = splitHostPort(localAddr)
        return "$remotePort:$localIp:$localPort"
    }

    private fun splitHostPort(addr: String): Pair<String, Int> {
        val idx = addr.lastIndexOf(':')
        require(idx > 0) { "malformed address '$addr'" }
        val host = addr.substring(0, idx).removePrefix("[").removeSuffix("]")
        val port = addr.substring(idx + 1).toInt()
        return host to port
    }
}
