package link.yggdrasil.yggstack.android.engine

/**
 * ng-flavor engine factory over the Rust yggstack-ng UniFFI bindings
 * (yggdrasil-ng core, QUIC RTT probe included).
 */
object EngineFactory {
    const val ENGINE_ID = "ng"

    fun create(): NativeEngine = RustEngine()

    fun checkQuicPeer(uri: String): Long = try {
        // Fully qualified: an unqualified call here resolves to this member
        // (infinite recursion, StackOverflowError).
        uniffi.yggstack_mobile.checkQuicPeer(uri)
    } catch (e: Exception) {
        -1L
    }
}
