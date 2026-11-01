package link.yggdrasil.yggstack.android.engine

import link.yggdrasil.yggstack.android.data.YggstackConfig

fun interface NativeLogCallback {
    fun onLog(message: String)
}

fun interface NativePingCallback {
    fun onResult(result: String)
}

fun interface NativePeersCallback {
    /** Full peer snapshot JSON (same schema as [NativeEngine.getPeersJson]),
     *  delivered on every change of the connected-peer set plus once as a
     *  baseline right after the engine starts. */
    fun onPeersChanged(peersJson: String)
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

    /** Register the peer-change callback. Call before [start]; the callback
     *  fires on an engine thread — hop before touching UI state. */
    fun setPeersCallback(callback: NativePeersCallback)

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

    /** Deterministically free native resources; the engine is unusable after.
     *  Critical for the ng flavor: without it the Rust runtime is only freed
     *  at GC time, racing the next engine started in the same process. */
    fun release() {}

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

    /** Start an ICMPv6 echo ("ping") session to [address] (a Yggdrasil
     *  address in 200::/7) through the engine's internal netstack. Sends
     *  [count] probes (0 = until stopped) every [intervalMs] ms, each
     *  waiting up to [timeoutMs] for its reply. Results stream through
     *  [callback] as JSON events (shared schema with both engines):
     *  `{"Type":"probe","Seq":N,"Success":bool,"RttMs":float|"Error":str}`
     *  and a final `{"Type":"done","Reason":"completed"|"stopped"|"error"}`.
     *  Replaces any running session; throws when the engine is not running.
     *  The first probe to a never-contacted address may time out (overlay
     *  key lookup) — it is reported as a plain timeout. */
    fun startPing(address: String, count: Int, timeoutMs: Long, intervalMs: Long, callback: NativePingCallback)

    /** Stop the running ping session, if any. The callback receives its
     *  final done event before this returns. */
    fun stopPing()

    /** One-shot liveness probe of a remote Yggdrasil TCP service
     *  ([yggAddrPort] is "[200:...]:port"), dialed through the engine's own
     *  netstack — no proxy or mapping involved. Returns "connected" (a real
     *  service answered the handshake), "refused" (RST: nothing listens on
     *  that port), "timeout" (no answer within [timeoutMs]) or "error"
     *  (engine not running / malformed address). Dialled twice with a short
     *  gap: the first dial on a cold start can lose the race with the
     *  overlay's key lookup — the retry dials through the cached key.
     *  Blocks for up to ~2x [timeoutMs]; call off the main thread. */
    fun checkTcpPort(yggAddrPort: String, timeoutMs: Long): String

    /** One-shot UDP probe: sends datagrams to a remote Yggdrasil UDP port
     *  and listens for any reply (a mid-window re-probe covers the
     *  lookup-consumed first datagram; port 53 is probed with a real
     *  minimal DNS query). Returns "replied" (a service answered),
     *  "silent" (no reply — inconclusive in UDP, not proof of death) or
     *  "error". Blocks for up to [timeoutMs]; call off the main thread. */
    fun checkUdpPort(yggAddrPort: String, timeoutMs: Long): String
}
