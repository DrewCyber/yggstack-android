package link.yggdrasil.yggstack.android.data.peer

import kotlinx.serialization.Serializable

/**
 * Peer selection mode
 */
@Serializable
enum class PeerSelectionMode {
    /**
     * Fully automatic peer selection and connection
     */
    AUTO,
    
    /**
     * User selects peers from sorted list
     */
    MANUAL,
    
    /**
     * Custom manual peer input (current functionality)
     */
    CUSTOM
}
