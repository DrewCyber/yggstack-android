package io.github.yggstack.android.ui.configuration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yggstack.android.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for Configuration screen
 */
class ConfigurationViewModel(
    private val repository: ConfigRepository
) : ViewModel() {

    private val _config = MutableStateFlow(YggstackConfig())
    val config: StateFlow<YggstackConfig> = _config.asStateFlow()

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

    private val _showPrivateKey = MutableStateFlow(false)
    val showPrivateKey: StateFlow<Boolean> = _showPrivateKey.asStateFlow()

    init {
        loadConfig()
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

    fun toggleForwardEnabled() {
        updateConfig(_config.value.copy(forwardEnabled = !_config.value.forwardEnabled))
    }

    fun toggleShowPrivateKey() {
        _showPrivateKey.value = !_showPrivateKey.value
    }

    fun startService() {
        viewModelScope.launch {
            _serviceState.value = ServiceState.Starting
            // TODO: Implement actual service start with yggstack binding
            // For now, simulate successful start
            kotlinx.coroutines.delay(500)
            _serviceState.value = ServiceState.Running
            _yggdrasilIp.value = "324:71e:281a:9ed3::1" // Placeholder
        }
    }

    fun stopService() {
        viewModelScope.launch {
            _serviceState.value = ServiceState.Stopping
            // TODO: Implement actual service stop
            kotlinx.coroutines.delay(500)
            _serviceState.value = ServiceState.Stopped
            _yggdrasilIp.value = null
        }
    }

    private fun updateConfig(config: YggstackConfig) {
        _config.value = config
        viewModelScope.launch {
            repository.saveConfig(config)
        }
    }

    class Factory(private val repository: ConfigRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ConfigurationViewModel::class.java)) {
                return ConfigurationViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

