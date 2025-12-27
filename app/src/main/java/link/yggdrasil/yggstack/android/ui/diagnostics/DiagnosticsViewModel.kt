package link.yggdrasil.yggstack.android.ui.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import link.yggdrasil.yggstack.android.data.BackupConfig
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.YggstackConfig
import link.yggdrasil.yggstack.android.service.YggstackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.File

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
    
    private val _yggstackConfig = MutableStateFlow<YggstackConfig?>(null)
    val yggstackConfig: StateFlow<YggstackConfig?> = _yggstackConfig.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _totalPeerCount = MutableStateFlow(0)
    val totalPeerCount: StateFlow<Int> = _totalPeerCount.asStateFlow()

    private val _peerDetails = MutableStateFlow<List<link.yggdrasil.yggstack.android.data.PeerDetail>>(emptyList())
    val peerDetails: StateFlow<List<link.yggdrasil.yggstack.android.data.PeerDetail>> = _peerDetails.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

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
                viewModelScope.launch {
                    service.peerCount.collect { count ->
                        _peerCount.value = count
                    }
                }
                viewModelScope.launch {
                    service.totalPeerCount.collect { count ->
                        _totalPeerCount.value = count
                    }
                }
                viewModelScope.launch {
                    service.totalPeerCount.collect { count ->
                        _totalPeerCount.value = count
                    }
                }
                viewModelScope.launch {
                    service.yggdrasilIp.collect { ip ->
                        _yggdrasilIp.value = ip
                    }
                }
                // Note: peerDetailsJSON collection moved to PeerStatus composable
                // to make it lifecycle-aware (only collect when Peers tab is visible)
                viewModelScope.launch {
                    service.fullConfigJSON.collect { configJson ->
                        _currentConfig.value = configJson
                    }
                }

                // Sync initial state
                _logs.value = service.logs.value
                _isServiceRunning.value = service.isRunning.value
                _peerCount.value = service.peerCount.value
                // peerDetailsJSON is a SharedFlow - will be loaded when collected
                _currentConfig.value = service.fullConfigJSON.value
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
        
        // Load current config from repository
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                _yggstackConfig.value = config
            }
        }
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
        // Config is now loaded from service's fullConfigJSON flow
        // in onServiceConnected, so this method can be simplified or removed
    }

    fun clearLogs() {
        // Clear logs in the service, not just the UI
        yggstackService?.clearLogs()
        // Also clear local state immediately for responsive UI
        _logs.value = emptyList()
    }
    
    fun importBackup(backup: BackupConfig) {
        viewModelScope.launch {
            // Get current config
            val currentConfig = _yggstackConfig.value ?: return@launch
            
            // Apply backup to current config
            val updatedConfig = backup.applyTo(currentConfig)
            
            // Save updated config
            repository.saveConfig(updatedConfig)
            
            // Update local state
            _yggstackConfig.value = updatedConfig
        }
    }
    
    fun downloadLogs(context: Context) {
        viewModelScope.launch {
            try {
                val logFile = withContext(Dispatchers.IO) {
                    yggstackService?.getLogFile()
                }
                
                if (logFile != null) {
                    withContext(Dispatchers.IO) {
                        // Copy log file to external cache dir so it can be shared
                        val cacheDir = context.externalCacheDir ?: context.cacheDir
                        val shareFile = File(cacheDir, "yggstack_logs.txt")
                        logFile.copyTo(shareFile, overwrite = true)
                        
                        // Share the file
                        withContext(Dispatchers.Main) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                shareFile
                            )
                            
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            
                            val chooser = Intent.createChooser(intent, "Download Logs")
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No log file available", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error downloading logs: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parsePeerDetails(json: String): List<link.yggdrasil.yggstack.android.data.PeerDetail> {
        if (json.isBlank() || json == "[]") return emptyList()
        
        return try {
            val jsonArray = JSONArray(json)
            val peers = mutableListOf<link.yggdrasil.yggstack.android.data.PeerDetail>()
            
            for (i in 0 until jsonArray.length()) {
                val peerObj = jsonArray.getJSONObject(i)
                val peer = link.yggdrasil.yggstack.android.data.PeerDetail(
                    uri = peerObj.optString("URI", ""),
                    up = peerObj.optBoolean("Up", false),
                    inbound = peerObj.optBoolean("Inbound", false),
                    port = peerObj.optLong("Port", 0),
                    priority = peerObj.optInt("Priority", 0),
                    rxBytes = peerObj.optLong("RXBytes", 0),
                    txBytes = peerObj.optLong("TXBytes", 0),
                    uptime = peerObj.optDouble("Uptime", 0.0) / 1_000_000_000.0, // nanoseconds to seconds
                    latency = peerObj.optLong("Latency", 0) / 1_000_000 // nanoseconds to milliseconds
                )
                peers.add(peer)
            }
            
            // Sort by URI to prevent list flapping
            peers.sortedBy { it.uri }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /**
     * Collects peer details from service. Should be called from composables with LaunchedEffect
     * to tie subscription lifecycle to composable visibility.
     */
    suspend fun collectPeerDetails() {
        yggstackService?.peerDetailsJSON?.collect { json ->
            _peerDetails.value = parsePeerDetails(json)
        }
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

