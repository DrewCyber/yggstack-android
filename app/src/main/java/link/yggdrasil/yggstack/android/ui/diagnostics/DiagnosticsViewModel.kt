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
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.BackupConfig
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.PortStatsDetail
import link.yggdrasil.yggstack.android.data.Protocol
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
 * Display-ready Ports row: listener stats with the config-resolved name and
 * live rates precomputed, so the UI does no sorting or matching work per
 * recomposition.
 */
data class PortRow(
    val stat: PortStatsDetail,
    val displayName: String?,
    val rxRatePerSec: Double?,
    val txRatePerSec: Double?
)

/**
 * One visible section of the Ports page (proxy / expose / forward) with its
 * rows already ordered to match the Configuration screen.
 */
data class PortSection(
    val section: String,
    val titleRes: Int,
    val rows: List<PortRow>
)

/**
 * ViewModel for Diagnostics screen
 */
class DiagnosticsViewModel(
    private val repository: ConfigRepository,
    private val context: Context
) : ViewModel() {

    private var yggstackService: YggstackService? = null
    private var serviceBound = false

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

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

    private val _portStats = MutableStateFlow<List<PortStatsDetail>>(emptyList())
    val portStats: StateFlow<List<PortStatsDetail>> = _portStats.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

    private val _yggdrasilPublicKey = MutableStateFlow<String?>(null)
    val yggdrasilPublicKey: StateFlow<String?> = _yggdrasilPublicKey.asStateFlow()

    // First visible item index + pixel offset, restored when the Peers tab
    // re-enters composition after the pager disposes the page
    private val _peerStatusScrollPosition = MutableStateFlow(0 to 0)
    val peerStatusScrollPosition: StateFlow<Pair<Int, Int>> = _peerStatusScrollPosition.asStateFlow()

    private val _portsCompactMode = MutableStateFlow(false)
    val portsCompactMode: StateFlow<Boolean> = _portsCompactMode.asStateFlow()

    private val _portSections = MutableStateFlow<List<PortSection>>(emptyList())
    val portSections: StateFlow<List<PortSection>> = _portSections.asStateFlow()

    private val _activeTransitConnections = MutableStateFlow(0L)
    val activeTransitConnections: StateFlow<Long> = _activeTransitConnections.asStateFlow()

    // Consecutive-poll state for live per-listener rates (moved out of the
    // composable so it survives recomposition and page switches)
    private var prevPortStats: List<PortStatsDetail>? = null
    private var prevPortStatsTimeMs = 0L
    private val portRates = mutableMapOf<String, Pair<Double, Double>>()

    private val _isPowerSaveIdle = MutableStateFlow(false)
    val isPowerSaveIdle: StateFlow<Boolean> = _isPowerSaveIdle.asStateFlow()

    private val _idleCountdownSeconds = MutableStateFlow<Long?>(null)
    val idleCountdownSeconds: StateFlow<Long?> = _idleCountdownSeconds.asStateFlow()

    private val _powerSaveIdleSince = MutableStateFlow<Long?>(null)
    val powerSaveIdleSince: StateFlow<Long?> = _powerSaveIdleSince.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _powerSaveUpMillis = MutableStateFlow(0L)
    val powerSaveUpMillis: StateFlow<Long> = _powerSaveUpMillis.asStateFlow()

    private val _powerSaveIdleMillis = MutableStateFlow(0L)
    val powerSaveIdleMillis: StateFlow<Long> = _powerSaveIdleMillis.asStateFlow()

    private val _powerSaveStateSince = MutableStateFlow(0L)
    val powerSaveStateSince: StateFlow<Long> = _powerSaveStateSince.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? YggstackService.YggstackBinder
            yggstackService = localBinder?.getService()
            serviceBound = true
            _serviceConnected.value = true

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
                // Note: yggdrasilIp, yggdrasilPublicKey, and peerDetailsJSON collection moved to PeerStatus composable
                // to make it lifecycle-aware (only collect when Peers tab is visible)
                viewModelScope.launch {
                    service.fullConfigJSON.collect { configJson ->
                        _currentConfig.value = configJson
                    }
                }
                viewModelScope.launch {
                    service.isPowerSaveIdle.collect { idle ->
                        _isPowerSaveIdle.value = idle
                    }
                }
                viewModelScope.launch {
                    service.idleCountdownSeconds.collect { seconds ->
                        _idleCountdownSeconds.value = seconds
                    }
                }
                viewModelScope.launch {
                    service.powerSaveIdleSince.collect { since ->
                        _powerSaveIdleSince.value = since
                    }
                }
                viewModelScope.launch {
                    service.isSessionActive.collect { active ->
                        _isSessionActive.value = active
                    }
                }
                viewModelScope.launch {
                    service.powerSaveUpMillis.collect { millis ->
                        _powerSaveUpMillis.value = millis
                    }
                }
                viewModelScope.launch {
                    service.powerSaveIdleMillis.collect { millis ->
                        _powerSaveIdleMillis.value = millis
                    }
                }
                viewModelScope.launch {
                    service.powerSaveStateSince.collect { since ->
                        _powerSaveStateSince.value = since
                    }
                }

                // Sync initial state
                _logs.value = service.logs.value
                _isServiceRunning.value = service.isRunning.value
                _peerCount.value = service.peerCount.value
                // peerDetailsJSON is a SharedFlow - will be loaded when collected
                _currentConfig.value = service.fullConfigJSON.value
                _isPowerSaveIdle.value = service.isPowerSaveIdle.value
                _idleCountdownSeconds.value = service.idleCountdownSeconds.value
                _powerSaveIdleSince.value = service.powerSaveIdleSince.value
                _isSessionActive.value = service.isSessionActive.value
                _powerSaveUpMillis.value = service.powerSaveUpMillis.value
                _powerSaveIdleMillis.value = service.powerSaveIdleMillis.value
                _powerSaveStateSince.value = service.powerSaveStateSince.value
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            _serviceConnected.value = false
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
                // Section visibility and display names depend on the config
                _portSections.value = buildPortSections(_portStats.value, config)
            }
        }

        // Restore the Ports view mode across app restarts
        viewModelScope.launch {
            repository.portsCompactModeFlow.collect { compact ->
                _portsCompactMode.value = compact
            }
        }
        
        // Clear node-derived UI state only when the service session ends. The
        // service keeps running through Power Save idle, so port cards and
        // their counters must stay frozen at their last values until a full
        // stop (isSessionActive drops only then, never on idle entry).
        viewModelScope.launch {
            _isSessionActive.collect { active ->
                if (!active) {
                    _yggdrasilIp.value = null
                    _yggdrasilPublicKey.value = null
                    _peerDetails.value = emptyList()
                    _portStats.value = emptyList()
                    _portSections.value = emptyList()
                    _activeTransitConnections.value = 0L
                    prevPortStats = null
                    prevPortStatsTimeMs = 0L
                    portRates.clear()
                }
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

    fun savePeerStatusScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        _peerStatusScrollPosition.value = firstVisibleItemIndex to firstVisibleItemScrollOffset
    }

    fun setPortsCompactMode(compact: Boolean) {
        _portsCompactMode.value = compact
        viewModelScope.launch {
            repository.savePortsCompactMode(compact)
        }
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
                    cost = peerObj.optLong("Cost", 0),
                    rxBytes = peerObj.optLong("RXBytes", 0),
                    txBytes = peerObj.optLong("TXBytes", 0),
                    uptime = peerObj.optDouble("Uptime", 0.0) / 1_000_000_000.0, // nanoseconds to seconds
                    latency = peerObj.optLong("Latency", 0) / 1_000_000 // nanoseconds to milliseconds
                )
                peers.add(peer)
            }
            
            // Sort by cost (lower is better), then by URI to prevent list flapping
            peers.sortedWith(compareBy({ it.cost }, { it.uri }))
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /**
     * Collects peer details, IP, and public key from service. Should be called from composables with LaunchedEffect
     * to tie subscription lifecycle to composable visibility.
     */
    suspend fun collectPeerDetails() {
        yggstackService?.let { service ->
            kotlinx.coroutines.coroutineScope {
                // Collect all three in parallel
                launch {
                    service.peerDetailsJSON.collect { json ->
                        _peerDetails.value = parsePeerDetails(json)
                    }
                }
                launch {
                    service.yggdrasilIp.collect { ip ->
                        _yggdrasilIp.value = ip
                    }
                }
                launch {
                    service.yggdrasilPublicKey.collect { key ->
                        _yggdrasilPublicKey.value = key
                    }
                }
            }
        }
    }

    private fun parsePortStats(json: String): List<PortStatsDetail> {
        if (json.isBlank() || json == "[]") return emptyList()

        return try {
            val jsonArray = JSONArray(json)
            val listeners = mutableListOf<PortStatsDetail>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                listeners.add(
                    PortStatsDetail(
                        key = obj.optString("Key", ""),
                        kind = obj.optString("Kind", ""),
                        listenAddr = obj.optString("Listen", ""),
                        targetAddr = obj.optString("Target", ""),
                        activeConnections = obj.optLong("ActiveConns", 0),
                        totalConnections = obj.optLong("TotalConns", 0),
                        rxBytes = obj.optLong("RXBytes", 0),
                        txBytes = obj.optLong("TXBytes", 0)
                    )
                )
            }

            // Already sorted by the Go side; stable re-sort keeps order deterministic
            listeners.sortedWith(compareBy({ it.kind }, { it.listenAddr }, { it.targetAddr }))
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /**
     * Collects per-listener port stats from service. Should be called from a composable
     * with LaunchedEffect to tie subscription lifecycle to composable visibility.
     */
    suspend fun collectPortStats() {
        yggstackService?.let { service ->
            service.portStatsJSON.collect { json ->
                updatePortStats(parsePortStats(json))
            }
        }
    }

    /**
     * Publishes a new port stats snapshot plus everything derived from it
     * (live rates, transit-connection total, display-ready sections) so the
     * composable only renders and never computes.
     */
    private fun updatePortStats(stats: List<PortStatsDetail>) {
        // Live rates: bytes-per-second from deltas between consecutive polls,
        // matched by listener key so ordering changes don't corrupt them
        val now = System.currentTimeMillis()
        val prev = prevPortStats
        if (prev != null && prevPortStatsTimeMs > 0) {
            val elapsedSec = (now - prevPortStatsTimeMs) / 1000.0
            if (elapsedSec >= 0.2) {
                val prevByKey = prev.associateBy { it.key }
                for (cur in stats) {
                    val old = prevByKey[cur.key]
                    portRates[cur.key] = if (old != null) {
                        (cur.rxBytes - old.rxBytes) / elapsedSec to (cur.txBytes - old.txBytes) / elapsedSec
                    } else {
                        0.0 to 0.0
                    }
                }
            }
        }
        prevPortStats = stats
        prevPortStatsTimeMs = now

        _portStats.value = stats
        _activeTransitConnections.value =
            stats.filter { it.section != "expose" }.sumOf { it.activeConnections }
        _portSections.value = buildPortSections(stats, _yggstackConfig.value)
    }

    /**
     * Groups listener stats into the visible Ports sections, ordered to match
     * the Configuration screen. Runs once per data change instead of on every
     * recomposition.
     */
    private fun buildPortSections(
        stats: List<PortStatsDetail>,
        config: YggstackConfig?
    ): List<PortSection> {
        if (config == null) return emptyList()
        val sections = listOf(
            Triple("proxy", config.proxyEnabled, R.string.ports_section_proxy),
            Triple("expose", config.exposeEnabled, R.string.ports_section_expose),
            Triple("forward", config.forwardEnabled, R.string.ports_section_forward)
        )
        return sections.mapNotNull { (section, enabled, titleRes) ->
            if (!enabled) return@mapNotNull null
            val entries = stats.filter { it.section == section }
            if (entries.isEmpty()) return@mapNotNull null
            // Keep the same order as on the Configuration screen by sorting on
            // the mapped config entry's position; anything not matching a
            // config mapping falls back to the end
            val orderedEntries = if (section == "proxy") entries else entries.sortedBy { stat ->
                listenerConfigIndex(stat, config).let { if (it >= 0) it else Int.MAX_VALUE }
            }
            PortSection(
                section = section,
                titleRes = titleRes,
                rows = orderedEntries.map { stat ->
                    PortRow(
                        stat = stat,
                        displayName = resolveListenerName(stat, config),
                        rxRatePerSec = portRates[stat.key]?.first,
                        txRatePerSec = portRates[stat.key]?.second
                    )
                }
            )
        }
    }

    /**
     * Index of the configured mapping a live listener corresponds to, or -1 when
     * unmatched. Used for showing the mapping's short name and for keeping the
     * Ports page in the same order as the Configuration screen.
     */
    private fun listenerConfigIndex(stat: PortStatsDetail, config: YggstackConfig): Int {
        val proto = if (stat.isTcp) Protocol.TCP else Protocol.UDP
        return when (stat.section) {
            "expose" -> config.exposeMappings.indexOfFirst { m ->
                m.protocol == proto &&
                    m.yggPort.toString() == stat.listenAddr.substringAfterLast(':') &&
                    m.localPort.toString() == stat.targetAddr.substringAfterLast(':')
            }
            "forward" -> config.forwardMappings.indexOfFirst { m ->
                m.protocol == proto &&
                    m.localPort.toString() == stat.listenAddr.substringAfterLast(':') &&
                    m.remotePort.toString() == stat.targetAddr.substringAfterLast(':')
            }
            else -> -1
        }
    }

    /**
     * Best-effort match of a live listener against configured mappings to show
     * its short name; falls back to null (the card shows addresses instead).
     */
    private fun resolveListenerName(stat: PortStatsDetail, config: YggstackConfig): String? {
        return when (val index = listenerConfigIndex(stat, config)) {
            -1 -> null
            else -> when (stat.section) {
                "expose" -> config.exposeMappings[index].shortName.takeIf { it.isNotBlank() }
                "forward" -> config.forwardMappings[index].shortName.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    fun wakeNow() {
        yggstackService?.wakeNow()
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

