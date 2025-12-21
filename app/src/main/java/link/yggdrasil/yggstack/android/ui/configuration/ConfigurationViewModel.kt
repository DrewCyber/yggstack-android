package link.yggdrasil.yggstack.android.ui.configuration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import link.yggdrasil.yggstack.android.data.*
import link.yggdrasil.yggstack.android.service.YggstackConfigParcelable
import link.yggdrasil.yggstack.android.service.YggstackService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for Configuration screen
 */
class ConfigurationViewModel(
    private val repository: ConfigRepository,
    private val context: Context
) : ViewModel() {

    private val _config = MutableStateFlow(YggstackConfig())
    val config: StateFlow<YggstackConfig> = _config.asStateFlow()

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

    private val _showPrivateKey = MutableStateFlow(false)
    val showPrivateKey: StateFlow<Boolean> = _showPrivateKey.asStateFlow()

    private val _scrollPosition = MutableStateFlow(0)
    val scrollPosition: StateFlow<Int> = _scrollPosition.asStateFlow()

    private var yggstackService: YggstackService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? YggstackService.YggstackBinder
            yggstackService = localBinder?.getService()
            serviceBound = true

            // Sync initial state immediately and check for state desync
            yggstackService?.let { service ->
                val isActuallyRunning = service.isRunning.value
                val hasIpAddress = service.yggdrasilIp.value != null
                
                // Check for state desynchronization: service thinks it's running but Go isn't actually running
                if (isActuallyRunning && !hasIpAddress) {
                    // This indicates the Go part crashed or was killed without proper cleanup
                    // The service state says running but there's no IP address
                    android.util.Log.w("ConfigViewModel", "State desync detected: service reports running but no IP address")
                    // Force state sync - service should handle this internally
                }
                
                // If service isn't running but should be (e.g., after system restart with saved config)
                // Start it with the current configuration
                if (!isActuallyRunning && hasIpAddress == false) {
                    // Check if there's a saved config that indicates we should be running
                    // This will be handled by the service's onStartCommand when it gets null intent
                    // UI just needs to reflect the current state
                }
                
                // Set initial state based on actual service state
                _serviceState.value = if (isActuallyRunning) {
                    ServiceState.Running
                } else {
                    ServiceState.Stopped
                }
                _yggdrasilIp.value = service.yggdrasilIp.value

                // Observe service state changes
                viewModelScope.launch {
                    service.isRunning.collect { running ->
                        // Update state based on actual service state
                        _serviceState.value = if (running) {
                            ServiceState.Running
                        } else {
                            ServiceState.Stopped
                        }
                    }
                }
                
                // Observe transition state to properly disable button during operations
                viewModelScope.launch {
                    service.isTransitioning.collect { transitioning ->
                        if (transitioning) {
                            // Don't override - let existing state determine if Starting or Stopping
                            if (_serviceState.value is ServiceState.Stopped) {
                                _serviceState.value = ServiceState.Starting
                            } else if (_serviceState.value is ServiceState.Running) {
                                _serviceState.value = ServiceState.Stopping
                            }
                        }
                        // When transition completes, isRunning observer will update to final state
                    }
                }
                
                viewModelScope.launch {
                    service.yggdrasilIp.collect { ip ->
                        _yggdrasilIp.value = ip
                    }
                }

                // Observe generated private key and save it
                viewModelScope.launch {
                    service.generatedPrivateKey.collect { key ->
                        if (key != null && _config.value.privateKey.isBlank()) {
                            // Save the generated key to config
                            updateConfig(_config.value.copy(privateKey = key))
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            yggstackService = null
            _serviceState.value = ServiceState.Stopped
            _yggdrasilIp.value = null
        }
    }

    init {
        loadConfig()
        bindToService()
    }

    override fun onCleared() {
        super.onCleared()
        unbindFromService()
    }

    private fun bindToService() {
        val intent = Intent(context, YggstackService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        if (serviceBound) {
            context.unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                _config.value = config
            }
        }
    }

    fun addPeer(peerUri: String) {
        if (peerUri.isNotBlank()) {
            val currentPeers = _config.value.peers.toMutableList()
            currentPeers.add(peerUri.trim())
            updateConfig(_config.value.copy(peers = currentPeers))
        }
    }

    fun removePeer(peerUri: String) {
        val currentPeers = _config.value.peers.toMutableList()
        currentPeers.remove(peerUri)
        updateConfig(_config.value.copy(peers = currentPeers))
    }

    fun updatePeer(oldPeer: String, newPeer: String) {
        val currentPeers = _config.value.peers.toMutableList()
        val index = currentPeers.indexOf(oldPeer)
        if (index != -1 && newPeer !in currentPeers) {
            currentPeers[index] = newPeer.trim()
            updateConfig(_config.value.copy(peers = currentPeers))
        }
    }

    fun updatePrivateKey(privateKey: String) {
        updateConfig(_config.value.copy(privateKey = privateKey))
    }

    fun updateSocksProxy(proxy: String) {
        updateConfig(_config.value.copy(socksProxy = proxy))
    }

    fun updateDnsServer(dns: String) {
        updateConfig(_config.value.copy(dnsServer = dns))
    }

    fun toggleProxyEnabled() {
        updateConfig(_config.value.copy(proxyEnabled = !_config.value.proxyEnabled))
    }

    fun addExposeMapping(mapping: ExposeMapping) {
        val currentMappings = _config.value.exposeMappings.toMutableList()
        currentMappings.add(mapping)
        updateConfig(_config.value.copy(exposeMappings = currentMappings))
    }

    fun removeExposeMapping(mapping: ExposeMapping) {
        val currentMappings = _config.value.exposeMappings.toMutableList()
        currentMappings.remove(mapping)
        updateConfig(_config.value.copy(exposeMappings = currentMappings))
    }

    fun updateExposeMapping(oldMapping: ExposeMapping, newMapping: ExposeMapping) {
        val currentMappings = _config.value.exposeMappings.toMutableList()
        val index = currentMappings.indexOf(oldMapping)
        if (index != -1) {
            currentMappings[index] = newMapping
            updateConfig(_config.value.copy(exposeMappings = currentMappings))
        }
    }

    fun toggleExposeEnabled() {
        updateConfig(_config.value.copy(exposeEnabled = !_config.value.exposeEnabled))
    }

    fun addForwardMapping(mapping: ForwardMapping) {
        val currentMappings = _config.value.forwardMappings.toMutableList()
        currentMappings.add(mapping)
        updateConfig(_config.value.copy(forwardMappings = currentMappings))
    }

    fun removeForwardMapping(mapping: ForwardMapping) {
        val currentMappings = _config.value.forwardMappings.toMutableList()
        currentMappings.remove(mapping)
        updateConfig(_config.value.copy(forwardMappings = currentMappings))
    }

    fun updateForwardMapping(oldMapping: ForwardMapping, newMapping: ForwardMapping) {
        val currentMappings = _config.value.forwardMappings.toMutableList()
        val index = currentMappings.indexOf(oldMapping)
        if (index != -1) {
            currentMappings[index] = newMapping
            updateConfig(_config.value.copy(forwardMappings = currentMappings))
        }
    }

    fun toggleForwardEnabled() {
        updateConfig(_config.value.copy(forwardEnabled = !_config.value.forwardEnabled))
    }

    fun setMulticastEnabled(enabled: Boolean) {
        updateConfig(_config.value.copy(multicastEnabled = enabled))
    }

    fun setLogLevel(level: String) {
        updateConfig(_config.value.copy(logLevel = level))
    }

    fun toggleShowPrivateKey() {
        _showPrivateKey.value = !_showPrivateKey.value
    }

    fun saveScrollPosition(position: Int) {
        _scrollPosition.value = position
    }

    fun startService() {
        viewModelScope.launch {
            _serviceState.value = ServiceState.Starting
            try {
                val intent = Intent(context, YggstackService::class.java).apply {
                    action = YggstackService.ACTION_START
                    putExtra(
                        YggstackService.EXTRA_CONFIG,
                        YggstackConfigParcelable.fromYggstackConfig(_config.value)
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                // Add a timeout fallback to ensure state updates if service doesn't notify
                kotlinx.coroutines.delay(3000)
                // If still in Starting state after 3 seconds, check if we have an IP (means it started)
                if (_serviceState.value is ServiceState.Starting) {
                    if (_yggdrasilIp.value != null) {
                        // Service started but state wasn't updated, force it
                        _serviceState.value = ServiceState.Running
                    } else {
                        // Service didn't start, reset to stopped
                        _serviceState.value = ServiceState.Stopped
                    }
                }
            } catch (e: Exception) {
                _serviceState.value = ServiceState.Error(e.message ?: "Unknown error")
                // Fallback to stopped state after error
                kotlinx.coroutines.delay(2000)
                _serviceState.value = ServiceState.Stopped
            }
        }
    }

    fun stopService() {
        viewModelScope.launch {
            _serviceState.value = ServiceState.Stopping
            try {
                val intent = Intent(context, YggstackService::class.java).apply {
                    action = YggstackService.ACTION_STOP
                }
                context.startService(intent)

                // Add a timeout fallback to reset state if service doesn't respond
                kotlinx.coroutines.delay(3000)
                if (_serviceState.value is ServiceState.Stopping) {
                    _serviceState.value = ServiceState.Stopped
                    _yggdrasilIp.value = null
                }
            } catch (e: Exception) {
                _serviceState.value = ServiceState.Error(e.message ?: "Unknown error")
                // Fallback to stopped state after error
                kotlinx.coroutines.delay(2000)
                _serviceState.value = ServiceState.Stopped
            }
        }
    }

    private fun updateConfig(config: YggstackConfig) {
        _config.value = config
        viewModelScope.launch {
            repository.saveConfig(config)
        }
    }

    class Factory(
        private val repository: ConfigRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ConfigurationViewModel::class.java)) {
                return ConfigurationViewModel(repository, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

