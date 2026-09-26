package link.yggdrasil.yggstack.android.engine

import link.yggdrasil.yggstack.android.data.YggstackConfig

fun interface NativeLogCallback {
    fun onLog(message: String)
}

/**
 * Abstraction over the native yggstack engine. Two builds exist:
 * the Go flavor (gomobile AAR) and the ng flavor (Rust UniFFI bindings),
 * each providing its own [EngineFactory] in its flavor source set.
 *
 * The interface intentionally mirrors the Go mobile API shape; the Rust
 * engine adapts to it internally (TOML config, spec-string mappings).
 */
interface NativeEngine {
    fun setLogCallback(callback: NativeLogCallback)
    fun setLogLevel(level: String)

    /** Generate a fresh native config text (engine-specific format). */
    fun generateConfigText(): String

    /** Build the native config text for [config]. [generated], when present,
     *  is [generateConfigText] output used to mint a new key. */
    fun buildNativeConfig(config: YggstackConfig, generated: String?): String

    /** Extract the private key from native config text. */
    fun privateKeyOf(nativeConfig: String): String

    /** Mask secrets in native config text for the diagnostics display. */
    fun sanitizeNativeConfig(nativeConfig: String): String

    fun loadConfig(nativeConfig: String)
    fun start(socksAddress: String, httpAddress: String, dnsServer: String)
    fun stop()
    fun isRunning(): Boolean

    fun getAddress(): String?
    fun getPublicKey(): String?
    fun getSubnet(): String?

    fun addLocalTcpMapping(localAddr: String, remoteAddr: String)
    fun addLocalUdpMapping(localAddr: String, remoteAddr: String)
    fun removeLocalTcpMapping(localAddr: String, remoteAddr: String)
    fun removeLocalUdpMapping(localAddr: String, remoteAddr: String)
    fun addRemoteTcpMapping(remotePort: Long, localAddr: String)
    fun addRemoteUdpMapping(remotePort: Long, localAddr: String)
    fun removeRemoteTcpMapping(remotePort: Long, localAddr: String)
    fun removeRemoteUdpMapping(remotePort: Long, localAddr: String)
    fun clearLocalMappings()
    fun clearRemoteMappings()

    fun addLivePeer(uri: String)
    fun removeLivePeer(uri: String)
    fun retryPeersNow()

    /** Peer stats JSON (shared schema), or null when the engine instance is
     *  corrupted (the Go AAR can signal this; the Rust engine never does). */
    fun getPeersJson(): String?

    /** Per-listener stats JSON (shared schema), or null. */
    fun getListenersJson(): String?
}
