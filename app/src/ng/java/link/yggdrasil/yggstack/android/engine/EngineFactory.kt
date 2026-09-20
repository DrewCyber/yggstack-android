package link.yggdrasil.yggstack.android.engine

import uniffi.yggstack_mobile.checkQuicPeer

/**
 * ng-flavor engine factory over the Rust yggstack-ng UniFFI bindings.
 * The Rust core has no QUIC dial-out, so QUIC peer checks report unknown.
 */
object EngineFactory {
    const val ENGINE_ID = "ng"

    fun create(): NativeEngine = RustEngine()

    fun checkQuicPeer(uri: String): Long = try {
        checkQuicPeer(uri)
    } catch (e: Exception) {
        -1L
    }
}
