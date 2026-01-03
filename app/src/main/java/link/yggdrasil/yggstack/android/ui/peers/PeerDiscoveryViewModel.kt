package link.yggdrasil.yggstack.android.ui.peers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.peer.PeerProtocol
import link.yggdrasil.yggstack.android.data.peer.PeerRepository
import link.yggdrasil.yggstack.android.data.peer.PeerSelectionMode
import link.yggdrasil.yggstack.android.data.peer.PublicPeer
import link.yggdrasil.yggstack.android.service.availability.*
import link.yggdrasil.yggstack.android.service.discovery.ExternalIpDetector
import link.yggdrasil.yggstack.android.service.discovery.PeerDiscoveryService
import link.yggdrasil.yggstack.android.service.sorting.PeerFilterService
import link.yggdrasil.yggstack.android.service.sorting.PeerSortingService

/**
 * ViewModel for Peer Discovery Screen
 */
class PeerDiscoveryViewModel(
    private val context: Context,
    private val peerRepository: PeerRepository,
    private val configRepository: ConfigRepository
) : ViewModel() {
    
    // Services
    private val discoveryService = PeerDiscoveryService(context)
    private val externalIpDetector = ExternalIpDetector()
    private val filterService = PeerFilterService()
    
    private val availabilityChecker = PeerAvailabilityChecker(
        pingChecker = PingChecker(),
        tcpChecker = TcpConnectChecker(),
        tlsChecker = TlsConnectChecker(),
        quicChecker = QuicConnectChecker(),
        webSocketChecker = WebSocketChecker()
    )
    
    private val sortingService = PeerSortingService(
        availabilityChecker = availabilityChecker,
        peerRepository = peerRepository
    )
    
    // State
    private val _peers = MutableStateFlow<List<PublicPeer>>(emptyList())
    val peers: StateFlow<List<PublicPeer>> = _peers.asStateFlow()
    
    private val _externalIp = MutableStateFlow<String?>(null)
    val externalIp: StateFlow<String?> = _externalIp.asStateFlow()
    
    private val _sortingInProgress = MutableStateFlow(false)
    val sortingInProgress: StateFlow<Boolean> = _sortingInProgress.asStateFlow()
    
    private val _sortingProgress = MutableStateFlow(0f)
    val sortingProgress: StateFlow<Float> = _sortingProgress.asStateFlow()
    
    private val _selectedProtocols = MutableStateFlow(PeerProtocol.values().toList())
    val selectedProtocols: StateFlow<List<PeerProtocol>> = _selectedProtocols.asStateFlow()
    
    private val _selectedPeerUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedPeerUris: StateFlow<Set<String>> = _selectedPeerUris.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        android.util.Log.d("PeerDiscoveryVM", "ViewModel created, starting initialization")
        loadPeers()
        detectExternalIp()
        loadSelectedPeers()
    }
    
    /**
     * Load peers from repository with filters
     */
    private fun loadPeers() {
        android.util.Log.d("PeerDiscoveryVM", "loadPeers() called, launching coroutine")
        viewModelScope.launch {
            android.util.Log.d("PeerDiscoveryVM", "Starting Flow collection")
            peerRepository.getAllPeers()
                .combine(selectedProtocols) { peers, protocols ->
                    android.util.Log.d("PeerDiscoveryVM", "Flow emitted: ${peers.size} peers from repository, filtering by ${protocols.size} protocols: $protocols")
                    if (peers.isNotEmpty()) {
                        android.util.Log.d("PeerDiscoveryVM", "First peer protocol: ${peers.first().protocol}, uri: ${peers.first().uri}")
                    }
                    val filtered = filterService.filterByProtocols(peers, protocols)
                    android.util.Log.d("PeerDiscoveryVM", "After filtering: ${filtered.size} peers")
                    filtered
                }
                .collect { filteredPeers ->
                    val sortedPeers = sortingService.sortByBestRtt(filteredPeers)
                    android.util.Log.d("PeerDiscoveryVM", "Setting _peers.value to ${sortedPeers.size} peers, current value: ${_peers.value.size}")
                    // Force StateFlow update by creating new list
                    _peers.value = sortedPeers.toList()
                    android.util.Log.d("PeerDiscoveryVM", "After update _peers.value.size = ${_peers.value.size}")
                }
        }
    }
    
    /**
     * Detect external IP address
     */
    private fun detectExternalIp() {
        viewModelScope.launch {
            _externalIp.value = externalIpDetector.detectExternalIp()
        }
    }
    
    /**
     * Load selected peers from config
     */
    private fun loadSelectedPeers() {
        viewModelScope.launch {
            configRepository.configFlow.collect { config ->
                _selectedPeerUris.value = config.peers.toSet()
            }
        }
    }
    
    /**
     * Download peer list from public sources
     */
    fun downloadPeerList() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            android.util.Log.d("PeerDiscoveryVM", "Starting peer download...")
            val result = discoveryService.fetchPublicPeers()
            
            result.fold(
                onSuccess = { peers ->
                    android.util.Log.d("PeerDiscoveryVM", "Downloaded ${peers.size} peers, inserting to database...")
                    peerRepository.insertPeers(peers)
                    android.util.Log.d("PeerDiscoveryVM", "Peers inserted successfully")
                    _errorMessage.value = null
                },
                onFailure = { error ->
                    android.util.Log.e("PeerDiscoveryVM", "Failed to download peers", error)
                    _errorMessage.value = "Failed to download peers: ${error.message}"
                }
            )
            
            _isLoading.value = false
        }
    }
    
    /**
     * Sort peers by ping
     */
    fun sortByPing() {
        viewModelScope.launch {
            val ip = _externalIp.value
            if (ip == null) {
                _errorMessage.value = "Cannot sort: external IP not detected"
                return@launch
            }
            
            _sortingInProgress.value = true
            _sortingProgress.value = 0f
            _errorMessage.value = null
            
            try {
                val currentPeers = _peers.value
                sortingService.sortByPing(ip, currentPeers) { current, total ->
                    _sortingProgress.value = current.toFloat() / total
                }
            } catch (e: Exception) {
                _errorMessage.value = "Sorting failed: ${e.message}"
            } finally {
                _sortingInProgress.value = false
                _sortingProgress.value = 0f
            }
        }
    }
    
    /**
     * Sort peers by connect
     */
    fun sortByConnect() {
        viewModelScope.launch {
            val ip = _externalIp.value
            if (ip == null) {
                _errorMessage.value = "Cannot sort: external IP not detected"
                return@launch
            }
            
            _sortingInProgress.value = true
            _sortingProgress.value = 0f
            _errorMessage.value = null
            
            try {
                val currentPeers = _peers.value
                sortingService.sortByConnect(ip, currentPeers) { current, total ->
                    _sortingProgress.value = current.toFloat() / total
                }
            } catch (e: Exception) {
                _errorMessage.value = "Sorting failed: ${e.message}"
            } finally {
                _sortingInProgress.value = false
                _sortingProgress.value = 0f
            }
        }
    }
    
    /**
     * Toggle peer selection
     */
    fun togglePeerSelection(uri: String) {
        viewModelScope.launch {
            val current = _selectedPeerUris.value.toMutableSet()
            if (current.contains(uri)) {
                current.remove(uri)
            } else {
                current.add(uri)
            }
            _selectedPeerUris.value = current
            
            // Update config immediately
            updateConfigWithSelectedPeers()
        }
    }
    
    /**
     * Check if peer is selected
     */
    fun isPeerSelected(uri: String): Boolean {
        return _selectedPeerUris.value.contains(uri)
    }
    
    /**
     * Toggle protocol filter
     */
    fun toggleProtocolFilter(protocol: PeerProtocol) {
        val current = _selectedProtocols.value.toMutableList()
        if (current.contains(protocol)) {
            if (current.size > 1) { // Keep at least one protocol selected
                current.remove(protocol)
            }
        } else {
            current.add(protocol)
        }
        _selectedProtocols.value = current.sortedBy { it.getPriority() }
    }
    
    /**
     * Clear peer cache (non-manual peers)
     */
    fun clearCache() {
        viewModelScope.launch {
            peerRepository.clearNonManualPeers()
            _errorMessage.value = null
        }
    }
    
    /**
     * Add manual peer
     */
    fun addManualPeer(uri: String) {
        viewModelScope.launch {
            try {
                val peer = PublicPeer.fromUri(uri, "Manual", isManual = true)
                peerRepository.insertPeer(peer)
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Invalid peer URI: ${e.message}"
            }
        }
    }
    
    /**
     * Update config with selected peers
     */
    private suspend fun updateConfigWithSelectedPeers() {
        try {
            val config = configRepository.configFlow.first()
            configRepository.saveConfig(
                config.copy(
                    peers = _selectedPeerUris.value.toList(),
                    peerSelectionMode = PeerSelectionMode.MANUAL
                )
            )
        } catch (e: Exception) {
            _errorMessage.value = "Failed to update config: ${e.message}"
        }
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    class Factory(
        private val context: Context,
        private val peerRepository: PeerRepository,
        private val configRepository: ConfigRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PeerDiscoveryViewModel::class.java)) {
                return PeerDiscoveryViewModel(context, peerRepository, configRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
