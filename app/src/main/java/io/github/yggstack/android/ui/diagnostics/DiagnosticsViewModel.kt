package io.github.yggstack.android.ui.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yggstack.android.data.ConfigRepository
import io.github.yggstack.android.service.YggstackService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for Diagnostics screen
 */
class DiagnosticsViewModel(
    private val repository: ConfigRepository,
    private val context: Context
) : ViewModel() {

    private var yggstackService: YggstackService? = null
    private var serviceBound = false

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _currentConfig = MutableStateFlow<String>("")
    val currentConfig: StateFlow<String> = _currentConfig.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? YggstackService.YggstackBinder
            yggstackService = localBinder?.getService()
            serviceBound = true

            // Observe service data
            yggstackService?.let { service ->
                viewModelScope.launch {
                    service.logs.collect { logList ->
                        _logs.value = logList
                    }
                }
                viewModelScope.launch {
                    service.isRunning.collect { running ->
                        _isServiceRunning.value = running
                    }
                }

                // Sync initial state
                _logs.value = service.logs.value
                _isServiceRunning.value = service.isRunning.value
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            yggstackService = null
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
                // Build config JSON for display
                val configJson = buildConfigJson(config)
                _currentConfig.value = configJson
            }
        }
    }

    private fun buildConfigJson(config: io.github.yggstack.android.data.YggstackConfig): String {
        return """
{
  "Peers": ${config.peers.joinToString(",\n    ", "[\n    \"", "\"\n  ]")},
  "PrivateKey": "${if (config.privateKey.isNotBlank()) config.privateKey else ""}",
  "Listen": [],
  "AdminListen": "none",
  "MulticastInterfaces": [],
  "InterfacePeers": {},
  "AllowedPublicKeys": [],
  "NodeInfo": {},
  "NodeInfoPrivacy": false,
  "ProxyEnabled": ${config.proxyEnabled},
  "SOCKSProxy": "${config.socksProxy}",
  "DNSServer": "${config.dnsServer}",
  "ExposeEnabled": ${config.exposeEnabled},
  "ForwardEnabled": ${config.forwardEnabled}
}
        """.trimIndent()
    }

    fun clearLogs() {
        // Clear logs in the service, not just the UI
        yggstackService?.clearLogs()
        // Also clear local state immediately for responsive UI
        _logs.value = emptyList()
    }

    class Factory(
        private val repository: ConfigRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DiagnosticsViewModel::class.java)) {
                return DiagnosticsViewModel(repository, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

