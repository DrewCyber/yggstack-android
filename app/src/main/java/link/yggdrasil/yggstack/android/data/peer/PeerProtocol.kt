package link.yggdrasil.yggstack.android.data.peer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Network protocol type for Yggdrasil peers
 */
@Parcelize
enum class PeerProtocol : Parcelable {
    TCP,
    TLS,
    QUIC,
    WS,
    WSS;
    
    /**
     * Get priority for protocol selection (lower is better)
     * Priority: TCP > TLS > QUIC > WS > WSS
     */
    fun getPriority(): Int = when (this) {
        TCP -> 1
        TLS -> 2
        QUIC -> 3
        WS -> 4
        WSS -> 5
    }
    
    companion object {
        /**
         * Extract protocol from peer URI
         */
        fun fromUri(uri: String): PeerProtocol {
            return when {
                uri.startsWith("tcp://") -> TCP
                uri.startsWith("tls://") -> TLS
                uri.startsWith("quic://") -> QUIC
                uri.startsWith("ws://") -> WS
                uri.startsWith("wss://") -> WSS
                else -> TCP // Default to TCP
            }
        }
    }
}
