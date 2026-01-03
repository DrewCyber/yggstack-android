package link.yggdrasil.yggstack.android.data.peer

/**
 * Type of peer list sorting
 */
enum class SortingType {
    /**
     * No sorting applied
     */
    NONE,
    
    /**
     * Sorted by ping response time
     */
    PING,
    
    /**
     * Sorted by protocol connect time
     */
    CONNECT,
    
    /**
     * Pre-sorted by metadata from JSON (response_ms)
     * Note: Currently not used as metadata is ignored
     */
    METADATA
}
