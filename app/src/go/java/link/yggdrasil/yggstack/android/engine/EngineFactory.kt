package link.yggdrasil.yggstack.android.engine

import link.yggdrasil.yggstack.mobile.Mobile

/**
 * Go-flavor engine factory. Lives in the `go` product-flavor source set;
 * the `ng` flavor provides an equivalent factory over the Rust bindings.
 */
object EngineFactory {
    const val ENGINE_ID = "go"

    fun create(): NativeEngine = GoEngine()

    /** Measure RTT to a QUIC peer via the Go stack, or -1 when unsupported. */
    fun checkQuicPeer(uri: String): Long = Mobile.checkQUICPeer(uri)
}
