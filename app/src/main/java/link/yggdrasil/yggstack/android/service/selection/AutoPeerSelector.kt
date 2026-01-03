package link.yggdrasil.yggstack.android.service.selection

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.peer.PeerRepository
import link.yggdrasil.yggstack.android.data.peer.PublicPeer
import link.yggdrasil.yggstack.android.service.discovery.ExternalIpDetector
import link.yggdrasil.yggstack.android.service.sorting.PeerSortingService

/**
 * Service for automatic peer selection
 * Handles AUTO mode peer selection logic
 */
class AutoPeerSelector(
    private val peerRepository: PeerRepository,
    private val sortingService: PeerSortingService,
    private val externalIpDetector: ExternalIpDetector,
    private val configRepository: ConfigRepository
) {
    
    /**
     * Select best peer for current external IP
     * Returns peer URI or null if no suitable peer found
     */
    suspend fun selectBestPeer(): String? {
        val externalIp = externalIpDetector.detectExternalIp()
        
        if (externalIp == null) {
            Log.w(TAG, "Cannot select peer: external IP detection failed")
            return null
        }
        
        Log.d(TAG, "Selecting best peer for external IP: $externalIp")
        
        // Check if we have fresh sorted list
        val hasFreshList = sortingService.hasFreshSortedList(externalIp)
        
        if (!hasFreshList) {
            Log.d(TAG, "No fresh sorted list, performing quick ping sort")
            // No sorted list - perform quick ping sort
            val allPeers = peerRepository.getAllPeers().first()
            
            if (allPeers.isEmpty()) {
                Log.w(TAG, "No peers available in cache")
                return null
            }
            
            val sortedPeers = sortingService.sortByPing(externalIp, allPeers) { _, _ -> }
            
            // Launch background connect sort
            launchBackgroundConnectSort(externalIp, allPeers)
            
            val bestPeer = sortedPeers.firstOrNull()
            if (bestPeer != null) {
                Log.d(TAG, "Selected best peer (ping): ${bestPeer.uri}")
                return bestPeer.uri
            }
        } else {
            Log.d(TAG, "Using existing sorted list")
            // Get best peer from sorted list
            val bestPeer = peerRepository.getBestPeer(externalIp)
            if (bestPeer != null) {
                Log.d(TAG, "Selected best peer (cached): ${bestPeer.uri}")
                return bestPeer.uri
            }
        }
        
        Log.w(TAG, "No suitable peer found")
        return null
    }
    
    /**
     * Launch background connect sort for better peer selection
     */
    private fun launchBackgroundConnectSort(externalIp: String, peers: List<PublicPeer>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting background connect sort")
                val sortedPeers = sortingService.sortByConnect(externalIp, peers) { _, _ -> }
                
                val bestPeer = sortedPeers.firstOrNull()
                if (bestPeer != null) {
                    Log.d(TAG, "Background sort complete, best peer: ${bestPeer.uri}")
                    // Update config with better peer
                    updateConfigWithPeer(bestPeer.uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background connect sort failed", e)
            }
        }
    }
    
    /**
     * Update configuration with selected peer
     * Adds peer to beginning of peers list
     */
    private suspend fun updateConfigWithPeer(peerUri: String) {
        try {
            val config = configRepository.configFlow.first()
            
            // Add new peer to beginning, remove duplicates
            val updatedPeers = listOf(peerUri) + config.peers.filter { it != peerUri }
            
            val updatedConfig = config.copy(
                peers = updatedPeers,
                lastAutoSelectedPeer = peerUri
            )
            
            configRepository.saveConfig(updatedConfig)
            Log.d(TAG, "Config updated with peer: $peerUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update config with peer", e)
        }
    }
    
    /**
     * Handle network change event
     * Selects new best peer if external IP changed
     */
    suspend fun handleNetworkChange(currentExternalIp: String, previousExternalIp: String?): String? {
        if (currentExternalIp == previousExternalIp) {
            Log.d(TAG, "External IP unchanged, no peer change needed")
            return null
        }
        
        Log.d(TAG, "External IP changed: $previousExternalIp -> $currentExternalIp")
        
        // Check if we have sorted list for new IP
        val hasFreshList = sortingService.hasFreshSortedList(currentExternalIp)
        
        if (hasFreshList) {
            // Use existing sorted list
            val bestPeer = peerRepository.getBestPeer(currentExternalIp)
            if (bestPeer != null) {
                Log.d(TAG, "Using cached best peer for new IP: ${bestPeer.uri}")
                updateConfigWithPeer(bestPeer.uri)
                return bestPeer.uri
            }
        }
        
        // Start background sorting for new IP
        val allPeers = peerRepository.getAllPeers().first()
        if (allPeers.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                sortingService.sortByConnect(currentExternalIp, allPeers) { _, _ -> }
                val bestPeer = peerRepository.getBestPeer(currentExternalIp)
                if (bestPeer != null) {
                    updateConfigWithPeer(bestPeer.uri)
                }
            }
        }
        
        // Keep current peer for now
        return null
    }
    
    companion object {
        private const val TAG = "AutoPeerSelector"
    }
}
