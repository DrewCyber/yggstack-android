package link.yggdrasil.yggstack.android.ui.configuration.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.PeerListForIp
import link.yggdrasil.yggstack.android.data.PublicPeerInfo
import link.yggdrasil.yggstack.android.data.PublicPeersCache
import link.yggdrasil.yggstack.android.service.PeerFetcherService
import link.yggdrasil.yggstack.android.service.PeerPingerService

/**
 * ViewModel for Peer Discovery screen
 */
class PeerDiscoveryViewModel(
    private val repository: ConfigRepository
) : ViewModel() {

    private val peerFetcher = PeerFetcherService()
    private val peerPinger = PeerPingerService()

    private val _externalIp = MutableStateFlow<String?>(null)
    val externalIp: StateFlow<String?> = _externalIp.asStateFlow()

    private val _peers = MutableStateFlow<List<PublicPeerInfo>>(emptyList())
    val peers: StateFlow<List<PublicPeerInfo>> = _peers.asStateFlow()

    private val _selectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val selectedPeers: StateFlow<Set<String>> = _selectedPeers.asStateFlow()

    // Configured peers in config.peers order — the selected list above the
    // discovery divider mirrors the Peers card's order on Configuration
    private val _configPeers = MutableStateFlow<List<String>>(emptyList())
    val configPeers: StateFlow<List<String>> = _configPeers.asStateFlow()

    // Countries picked in the filter (empty = show all); persisted
    private val _countryFilter = MutableStateFlow<Set<String>>(emptySet())
    val countryFilter: StateFlow<Set<String>> = _countryFilter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingMessage = MutableStateFlow("")
    val loadingMessage: StateFlow<String> = _loadingMessage.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _sortType = MutableStateFlow<String?>(null)
    val sortType: StateFlow<String?> = _sortType.asStateFlow()

    private var sortJob: kotlinx.coroutines.Job? = null

    private val _isCancellable = MutableStateFlow(false)
    val isCancellable: StateFlow<Boolean> = _isCancellable.asStateFlow()

    init {
        // Keep the selection mirrored on config.peers: peers added through
        // the manual Add Peer dialog (outside this screen) must show up as
        // checked here without a re-entry. togglePeerSelection writes config
        // first, so its own updates arrive here as no-ops.
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                _configPeers.value = config.peers
                _selectedPeers.value = config.peers.toSet()
            }
        }

        // Restore the persisted country filter
        viewModelScope.launch {
            repository.countryFilterFlow.collect { countries ->
                _countryFilter.value = countries
            }
        }
    }

    /**
     * Replace the country filter wholesale (empty set clears it)
     */
    fun setCountryFilter(countries: Set<String>) {
        _countryFilter.value = countries
        viewModelScope.launch {
            repository.saveCountryFilter(countries)
        }
    }

    /**
     * Toggle one country in the filter, keeping the dropdown open for
     * multi-select
     */
    fun toggleCountryFilter(country: String) {
        val current = _countryFilter.value
        setCountryFilter(
            if (country in current) current - country else current + country
        )
    }

    /**
     * Refresh external IP and load list for current network
     */
    fun refreshExternalIp(): Job = viewModelScope.launch {
            try {
                // Sync isCancellable state with actual Job state
                val isRttCheckRunning = sortJob?.isActive == true
                _isCancellable.value = isRttCheckRunning
                
                // If RTT check is running, don't interfere with its loading states
                if (isRttCheckRunning) {
                    // Just sync selected peers and exit
                    val config = repository.configFlow.first()
                    val peersInConfig = config.peers.toSet()
                    _selectedPeers.value = peersInConfig
                    return@launch
                }
                
                // Get last known IP from cache
                val lastKnownIp = repository.getLastExternalIp()
                
                // Show last known IP initially (or "unknown" if never detected)
                _externalIp.value = lastKnownIp ?: "unknown"
                
                // Load peers for last known IP or unknown
                loadPeersForCurrentIp(lastKnownIp ?: "unknown")
                
                // Sync selected peers with config
                val config = repository.configFlow.first()
                val peersInConfig = config.peers.toSet()
                _selectedPeers.value = peersInConfig
                
                // Now detect current external IP
                _isLoading.value = true
                _errorMessage.value = null
                
                val ipResult = peerFetcher.getExternalIp { status ->
                    _loadingMessage.value = status
                }
                
                _isLoading.value = false
                _loadingMessage.value = ""
                
                if (ipResult.isSuccess) {
                    val detectedIp = ipResult.getOrNull()
                    if (detectedIp != null) {
                        _externalIp.value = detectedIp
                        // Save as last known IP
                        repository.saveLastExternalIp(detectedIp)
                        // Load peers for detected IP
                        loadPeersForCurrentIp(detectedIp)
                    } else {
                        // Failed to detect - stay with unknown
                        _externalIp.value = "unknown"
                        _errorMessage.value = "Failed to detect external IP"
                        loadPeersForCurrentIp("unknown")
                    }
                } else {
                    // Failed to detect - stay with unknown
                    _externalIp.value = "unknown"
                    _errorMessage.value = "Failed to detect external IP: ${ipResult.exceptionOrNull()?.message}"
                    loadPeersForCurrentIp("unknown")
                }
                
            } catch (e: Exception) {
                _isLoading.value = false
                _loadingMessage.value = ""
                _externalIp.value = "unknown"
                _errorMessage.value = "Error: ${e.message}"
                loadPeersForCurrentIp("unknown")
            }
        }

    /**
     * Load peers for current IP from cache or create unsorted list
     */
    private suspend fun loadPeersForCurrentIp(ip: String) {
        // Get global cache
        val globalCache = repository.getPublicPeersCache()
        
        if (globalCache.peers.isEmpty()) {
            // No global cache - show empty
            _peers.value = emptyList()
            _sortType.value = null
            return
        }
        
        // Check if we have sorted list for this IP
        val sortedList = repository.getSortedListForIp(ip)
        
        if (sortedList != null) {
            // Use sorted list with RTT data for this IP
            _peers.value = sortedList.peers
            _sortType.value = sortedList.sortType
        } else {
            // No sorted list - show unsorted from global cache (without RTT)
            _peers.value = globalCache.peers
            _sortType.value = "unsorted"
            
            // Save as unsorted for this IP
            repository.saveSortedListForIp(
                ip,
                PeerListForIp(
                    peers = globalCache.peers,
                    sortedAt = System.currentTimeMillis(),
                    sortType = "unsorted"
                )
            )
        }
    }

    /**
     * Fetch public peers from remote source (update global cache)
     */
    fun fetchPeers() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _loadingMessage.value = "Downloading peers list..."
                _errorMessage.value = null
                _progress.value = 0f

                val result = peerFetcher.fetchPublicPeers()
                
                if (result.isSuccess) {
                    val fetchedPeers = result.getOrNull() ?: emptyList()
                    
                    // Update global cache
                    repository.savePublicPeersCache(
                        PublicPeersCache(
                            peers = fetchedPeers,
                            downloadedAt = System.currentTimeMillis()
                        )
                    )
                    
                    // Clear all sorted lists (they're now outdated)
                    repository.clearAllSortedLists()
                    
                    // Show unsorted list
                    _peers.value = fetchedPeers
                    _sortType.value = "unsorted"
                    
                    // Save unsorted list for current IP
                    val ip = _externalIp.value
                    if (ip != null) {
                        repository.saveSortedListForIp(
                            ip,
                            PeerListForIp(
                                peers = fetchedPeers,
                                sortedAt = System.currentTimeMillis(),
                                sortType = "unsorted"
                            )
                        )
                    }
                    
                    _errorMessage.value = null
                } else {
                    // Failed to download - show error, keep current data
                    _errorMessage.value = "Peers list download failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Peers list download failed: ${e.message}"
            } finally {
                _isLoading.value = false
                _loadingMessage.value = ""
                _progress.value = 0f
            }
        }
    }

    /**
     * Sort peers by RTT (protocol connect check). With a country filter
     * active, only the filtered peers are checked; the rest keep their
     * existing RTT data and stay in the list behind the checked ones.
     */
    fun sortByRTT() {
        // Cancel previous sort if running
        sortJob?.cancel()

        sortJob = viewModelScope.launch {
            val originalPeers = _peers.value  // Save original list
            try {
                val currentPeers = _peers.value
                val filter = _countryFilter.value
                val peersToCheck = if (filter.isEmpty()) currentPeers
                    else currentPeers.filter { it.country in filter }

                if (peersToCheck.isEmpty()) {
                    _errorMessage.value = if (filter.isEmpty())
                        "No peers to sort. Fetch peers first."
                    else
                        "No peers to check for the selected countries."
                    return@launch
                }

                // Out-of-filter peers: untouched by the check, appended
                // after the checked ones so clearing the filter later
                // doesn't lose them
                val checkedUris = peersToCheck.mapTo(mutableSetOf()) { it.uri }
                val rest = currentPeers.filter { it.uri !in checkedUris }

                // Reset RTT values to ensure full recheck
                _peers.value = peersToCheck.map { it.copy(rtt = null, lastChecked = null) } + rest

                _isLoading.value = true
                _isCancellable.value = true
                _loadingMessage.value = "Checking peers..."
                _errorMessage.value = null
                _progress.value = 0f

                // Check the filtered peers individually with incremental updates
                val sortedPeers = peerPinger.checkPeersByHostWithProgress(
                    peers = peersToCheck,
                    onProgress = { checked, total ->
                        _progress.value = checked.toFloat() / total.toFloat()
                        _loadingMessage.value = "Checking peers: $checked/$total"
                    },
                    onIncrementalUpdate = { updatedPeers ->
                        // Update list immediately as RTT data comes in
                        _peers.value = updatedPeers + rest
                    }
                )

                val mergedPeers = sortedPeers + rest
                _peers.value = mergedPeers
                _sortType.value = "connect"

                // Save sorted peers with RTT data for current IP
                val ip = _externalIp.value
                if (ip != null) {
                    repository.saveSortedListForIp(
                        ip,
                        PeerListForIp(
                            peers = mergedPeers,  // Store full peers with RTT for this IP
                            sortedAt = System.currentTimeMillis(),
                            sortType = "connect"
                        )
                    )
                }

                _errorMessage.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by user - restore original list
                _peers.value = originalPeers
            } catch (e: Exception) {
                _errorMessage.value = "Error during RTT check: ${e.message}"
            } finally {
                _isLoading.value = false
                _isCancellable.value = false
                _loadingMessage.value = ""
                _progress.value = 0f
                sortJob = null
            }
        }
    }

    /**
     * Cancel ongoing RTT check
     */
    fun cancelSort() {
        sortJob?.cancel()
        sortJob = null
    }

    // In-memory guard for autoRttCheckOnFirstLaunch: the composable trigger
    // re-fires on every Configuration tab re-entry; only the first one runs
    private var firstLaunchAutoCheckStarted = false

    /**
     * Automatic RTT check for the very first app start: the Discover Peers
     * button on the Configuration screen then already fills with check
     * progress while the user works through the startup permission dialogs.
     * Gated by a persisted one-shot flag — later app starts never auto-check.
     */
    fun autoRttCheckOnFirstLaunch() {
        if (firstLaunchAutoCheckStarted) return
        firstLaunchAutoCheckStarted = true
        viewModelScope.launch {
            // Consumed even when the check below ends up not running:
            // "first app start" is one-shot, not "first successful start"
            if (!repository.consumeFirstLaunchRttCheck()) return@launch

            // The bundled public-peers seed is saved to the cache during app
            // startup (MainActivity); wait briefly for it instead of racing
            // an empty peer list
            val deadline = System.currentTimeMillis() + 10_000
            while (!repository.hasPublicPeersCache() && System.currentTimeMillis() < deadline) {
                delay(250)
            }
            if (!repository.hasPublicPeersCache()) return@launch

            refreshExternalIp().join()
            // Empty list would just set "No peers to sort" as an error
            if (_peers.value.isEmpty()) return@launch
            sortByRTT()
        }
    }

    /**
     * Toggle peer selection and sync with config
     */
    suspend fun togglePeerSelection(uri: String) {
        val config = repository.configFlow.first()
        val isInConfig = uri in config.peers
        
        if (isInConfig) {
            // Remove from config and selection
            val updatedConfig = config.copy(
                peers = config.peers - uri
            )
            repository.saveConfig(updatedConfig)
            _selectedPeers.value = _selectedPeers.value - uri
        } else {
            // Add to config and selection
            val updatedConfig = config.copy(
                peers = config.peers + uri
            )
            repository.saveConfig(updatedConfig)
            _selectedPeers.value = _selectedPeers.value + uri
        }
    }

    /**
     * Get display list - returns peers in original sort order
     */
    fun getDisplayPeers(): StateFlow<List<PublicPeerInfo>> = _peers.asStateFlow()

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }

    class Factory(private val repository: ConfigRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PeerDiscoveryViewModel::class.java)) {
                return PeerDiscoveryViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
