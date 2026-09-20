package link.yggdrasil.yggstack.android.engine

import link.yggdrasil.yggstack.android.data.NativeConfigJson
import link.yggdrasil.yggstack.android.data.YggstackConfig
import link.yggdrasil.yggstack.mobile.LogCallback
import link.yggdrasil.yggstack.mobile.Mobile
import link.yggdrasil.yggstack.mobile.Yggstack

/**
 * Go-flavor engine: thin adapter over the gomobile AAR
 * (link.yggdrasil.yggstack.mobile), including the JSON config format.
 */
internal class GoEngine : NativeEngine {
    private val yggstack: Yggstack = Mobile.newYggstack()

    override fun setLogCallback(callback: NativeLogCallback) {
        yggstack.setLogCallback(object : LogCallback {
            override fun onLog(message: String) {
                callback.onLog(message)
            }
        })
    }

    override fun setLogLevel(level: String) = yggstack.setLogLevel(level)

    override fun generateConfigText(): String = Mobile.generateConfig()

    override fun buildNativeConfig(config: YggstackConfig, generated: String?): String =
        NativeConfigJson.build(config, generated)

    override fun privateKeyOf(nativeConfig: String): String = NativeConfigJson.privateKey(nativeConfig)

    override fun sanitizeNativeConfig(nativeConfig: String): String =
        NativeConfigJson.sanitize(nativeConfig)

    override fun loadConfig(nativeConfig: String) = yggstack.loadConfigJSON(nativeConfig)

    override fun start(socksAddress: String, dnsServer: String) =
        yggstack.start(socksAddress, dnsServer)

    override fun stop() = yggstack.stop()

    override fun isRunning(): Boolean = yggstack.isRunning()

    override fun getAddress(): String? = yggstack.address

    override fun getPublicKey(): String? = yggstack.publicKey

    override fun getSubnet(): String? = yggstack.subnet

    override fun addLocalTcpMapping(localAddr: String, remoteAddr: String) =
        yggstack.addLocalTCPMapping(localAddr, remoteAddr)

    override fun addLocalUdpMapping(localAddr: String, remoteAddr: String) =
        yggstack.addLocalUDPMapping(localAddr, remoteAddr)

    override fun removeLocalTcpMapping(localAddr: String, remoteAddr: String) =
        yggstack.removeLocalTCPMapping(localAddr, remoteAddr)

    override fun removeLocalUdpMapping(localAddr: String, remoteAddr: String) =
        yggstack.removeLocalUDPMapping(localAddr, remoteAddr)

    override fun addRemoteTcpMapping(remotePort: Long, localAddr: String) =
        yggstack.addRemoteTCPMapping(remotePort, localAddr)

    override fun addRemoteUdpMapping(remotePort: Long, localAddr: String) =
        yggstack.addRemoteUDPMapping(remotePort, localAddr)

    override fun removeRemoteTcpMapping(remotePort: Long, localAddr: String) =
        yggstack.removeRemoteTCPMapping(remotePort, localAddr)

    override fun removeRemoteUdpMapping(remotePort: Long, localAddr: String) =
        yggstack.removeRemoteUDPMapping(remotePort, localAddr)

    override fun clearLocalMappings() = yggstack.clearLocalMappings()

    override fun clearRemoteMappings() = yggstack.clearRemoteMappings()

    override fun addLivePeer(uri: String) = yggstack.addLivePeer(uri)

    override fun removeLivePeer(uri: String) = yggstack.removeLivePeer(uri)

    override fun retryPeersNow() = yggstack.retryPeersNow()

    override fun getPeersJson(): String? = yggstack.getPeersJSON()

    override fun getListenersJson(): String? = yggstack.getListenersJSON()
}
