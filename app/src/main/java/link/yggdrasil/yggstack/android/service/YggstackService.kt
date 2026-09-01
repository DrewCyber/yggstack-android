package link.yggdrasil.yggstack.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import link.yggdrasil.yggstack.android.BuildConfig
import link.yggdrasil.yggstack.android.MainActivity
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.YggstackConfig
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.PersistentLogger
import link.yggdrasil.yggstack.android.data.ExposeMapping
import link.yggdrasil.yggstack.android.data.ForwardMapping
import link.yggdrasil.yggstack.android.data.Protocol
import link.yggdrasil.yggstack.android.data.CachedPeer
import link.yggdrasil.yggstack.android.data.hasActiveExposedPorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import link.yggdrasil.yggstack.mobile.LogCallback
import link.yggdrasil.yggstack.mobile.Mobile
import link.yggdrasil.yggstack.mobile.Yggstack
import org.json.JSONArray
import org.json.JSONObject
import android.content.SharedPreferences
import link.yggdrasil.yggstack.android.utils.LocaleHelper
import kotlinx.coroutines.runBlocking

/**
 * Foreground service for running Yggstack
 */
class YggstackService : Service() {

    override fun attachBaseContext(newBase: Context) {
        // getString() elsewhere in the service (e.g. Power Save notifications) must follow
        // the app's saved language, not the system locale - the service is created directly
        // by the OS and never goes through MainActivity's attachBaseContext override.
        val language = runBlocking { ConfigRepository(newBase).languageFlow.first() }
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, language))
    }

    private val binder = YggstackBinder()
    private var yggstack: Yggstack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var persistentLogger: PersistentLogger
    private var peerDetailsJob: kotlinx.coroutines.Job? = null
    private var portStatsJob: kotlinx.coroutines.Job? = null
    private lateinit var sharedPreferences: SharedPreferences
    
    // Subscription monitoring for peer details / port stats (separate so each
    // screen's tab visibility independently controls its own poll loop)
    private var peerDetailsSubscriptionJob: kotlinx.coroutines.Job? = null
    private var portStatsSubscriptionJob: kotlinx.coroutines.Job? = null

    // Power Save: idle-detection monitor + wake-on-connection placeholder listeners
    private var idlePowerSaveMonitorJob: kotlinx.coroutines.Job? = null
    private val placeholderListeners = mutableListOf<PlaceholderListener>()
    private val wakeTriggerLock = Any()
    @Volatile private var wakeInProgress = false

    // Placeholder bind retry: the real listeners of a powering-down node can
    // still hold the port briefly after stop() returns, and a single failed
    // bind would leave the port permanently unable to wake the node.
    private val PLACEHOLDER_BIND_ATTEMPTS = 10
    private val PLACEHOLDER_BIND_RETRY_DELAY_MS = 500L

    // Connections held between a placeholder wake trigger and the node's real
    // listener coming up, relayed by the splice proxy (spliceHeldConnection).
    private val heldSpliceSockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
    private val heldSpliceJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
    private val SPLICE_HOLD_TIMEOUT_MS = 25_000L
    private val SPLICE_READY_POLL_MS = 250L
    private val SPLICE_UPSTREAM_CONNECT_TIMEOUT_MS = 3_000L
    
    // Operation state management
    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()
    private val operationMutex = kotlinx.coroutines.sync.Mutex()
    
    // Screen state monitoring
    private var screenStateReceiver: BroadcastReceiver? = null
    
    // Network connectivity monitoring
    private enum class NetworkType {
        NONE, WIFI, CELLULAR, ETHERNET, VPN, OTHER
    }
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager by lazy { 
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager 
    }
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val networkTypeMap = mutableMapOf<Long, NetworkType>() // Track network handle -> type
    private var currentNetworkType = NetworkType.NONE
    private var lastNetworkType: String? = null
    private var lastNetworkChangeTime: Long = 0
    private var lastNetworkRetryTime: Long = 0
    private val NETWORK_CHANGE_DEBOUNCE_MS = 5000L // 5 seconds for multicast handling
    private val NETWORK_STABILIZATION_DELAY_MS = 300L // Wait for network to stabilize before retry
    private val FLAP_PROTECTION_COOLDOWN_MS = 500L // Prevent rapid retry spam
    private var isOnWifi: Boolean = false
    private var isInitialNetworkCallback: Boolean = true // Skip retry on first callback after registration
    private var hasNoNetwork: Boolean = true // Track if we're in no-network state
    private var networkRetryJob: Job? = null // Track pending retry job for cancellation
    
    // Peer cache constants
    private val PEER_CACHE_MAX_SIZE = 10 // Maximum number of cached peers
    private val PEER_CACHE_STALE_TIME_MS = 60 * 60 * 1000L // 1 hour
    private val PEER_CACHE_UPDATE_INTERVAL_MS = 60 * 1000L // Update cache every 60 seconds
    private var peerCacheUpdateJob: Job? = null // Track peer cache update job
    
    // Store last config for automatic restart after crash
    private var lastConfig: YggstackConfig? = null
    private var crashRestartAttempts = 0
    
    // Logs enabled setting and current log level
    private var logsEnabled: Boolean = true
    private var currentLogLevel: String = "error"

    // Raw GetListenersJSON payload from the port stats poller's last tick,
    // shared with the Power Save idle monitor so the two loops don't each pay
    // a JNI call per second while the Ports tab is open. Volatile: written by
    // the poller, read by the monitor, both on serviceScope.
    @Volatile private var lastRawListenersJSON: String? = null

    // Last posted foreground notification content; identical updates are
    // skipped so the 1s peer poll doesn't keep waking SystemUI
    private var lastNotificationStatus: String? = null
    private var lastNotificationPeerCount = -1
    private var lastNotificationTotalPeerCount = -1
    private var lastNotificationYggdrasilIp: String? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPowerSaveIdle = MutableStateFlow(false)
    val isPowerSaveIdle: StateFlow<Boolean> = _isPowerSaveIdle.asStateFlow()

    private val _idleCountdownSeconds = MutableStateFlow<Long?>(null)
    val idleCountdownSeconds: StateFlow<Long?> = _idleCountdownSeconds.asStateFlow()

    private val _powerSaveIdleSince = MutableStateFlow<Long?>(null)
    val powerSaveIdleSince: StateFlow<Long?> = _powerSaveIdleSince.asStateFlow()

    // True from a successful start until a full stop. Unlike isRunning it does
    // NOT drop when Power Save powers the node down, so UI state tied to the
    // service session (Ports cards, traffic counters) survives idle periods.
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    // Power Save session accounting: cumulative up/idle time since the last
    // full start. "Up" accrues while the node runs, "idle" while Power Save
    // has it powered down; the segment currently in progress is derived from
    // powerSaveStateSince. Both totals reset on full stop / fresh start.
    private val _powerSaveUpMillis = MutableStateFlow(0L)
    val powerSaveUpMillis: StateFlow<Long> = _powerSaveUpMillis.asStateFlow()

    private val _powerSaveIdleMillis = MutableStateFlow(0L)
    val powerSaveIdleMillis: StateFlow<Long> = _powerSaveIdleMillis.asStateFlow()

    private val _powerSaveStateSince = MutableStateFlow(0L)
    val powerSaveStateSince: StateFlow<Long> = _powerSaveStateSince.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

    private val _yggdrasilPublicKey = MutableStateFlow<String?>(null)
    val yggdrasilPublicKey: StateFlow<String?> = _yggdrasilPublicKey.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _totalPeerCount = MutableStateFlow(0)
    val totalPeerCount: StateFlow<Int> = _totalPeerCount.asStateFlow()

    private val _peerDetailsJSON = MutableSharedFlow<String>(replay = 1)
    val peerDetailsJSON: SharedFlow<String> = _peerDetailsJSON.asSharedFlow()

    private val _portStatsJSON = MutableSharedFlow<String>(replay = 1)
    val portStatsJSON: SharedFlow<String> = _portStatsJSON.asSharedFlow()

    // Per-listener counters for the current service session. The Go node
    // starts every instance (fresh start or Power Save wake) with zeroed
    // stats, so the Ports screen is fed session-cumulative totals instead:
    // each poll adds the delta between the node's raw counters and the last
    // raw snapshot for the same listener key to the running total. Keys are
    // derived from mapping addresses by the Go layer, so they match across
    // node restarts.
    private data class ListenerCounters(val totalConns: Long, val rxBytes: Long, val txBytes: Long)
    private val rawPortCounters = java.util.concurrent.ConcurrentHashMap<String, ListenerCounters>()
    private val cumulativePortCounters = java.util.concurrent.ConcurrentHashMap<String, ListenerCounters>()

    private val _generatedPrivateKey = MutableStateFlow<String?>(null)
    val generatedPrivateKey: StateFlow<String?> = _generatedPrivateKey.asStateFlow()

    private val _fullConfigJSON = MutableStateFlow<String>("")
    val fullConfigJSON: StateFlow<String> = _fullConfigJSON.asStateFlow()

    /**
     * Truncate private key for security - shows only first 8 and last 8 characters
     */
    private fun truncatePrivateKey(key: String): String {
        return if (key.length > 20) {
            "${key.take(8)}...${key.takeLast(8)}"
        } else {
            "***"
        }
    }

    /**
     * Sanitize config JSON by replacing private key with truncated version
     */
    private fun sanitizeConfigJson(json: String): String {
        return json.replace(
            Regex("\"PrivateKey\":\\s*\"([^\"]{20,})\""),
        ) { matchResult ->
            val key = matchResult.groupValues[1]
            "\"PrivateKey\": \"${truncatePrivateKey(key)}\""
        }
    }

    private fun jsonEscape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun applyGroupPasswordToConfigJson(configJson: String, config: YggstackConfig): String {
        val groupPassword = if (config.groupPasswordEnabled && config.groupPassword.isNotBlank()) {
            config.groupPassword
        } else {
            ""
        }
        val escapedGroupPassword = jsonEscape(groupPassword)
        return if (Regex("\"GroupPassword\":\\s*\"[^\"]*\"").containsMatchIn(configJson)) {
            configJson.replace(
                Regex("\"GroupPassword\":\\s*\"[^\"]*\""),
                "\"GroupPassword\": \"$escapedGroupPassword\""
            )
        } else {
            configJson.replace(
                Regex("\"IfName\":"),
                "\"GroupPassword\": \"$escapedGroupPassword\",\n  \"IfName\":"
            )
        }
    }

    inner class YggstackBinder : Binder() {
        fun getService(): YggstackService = this@YggstackService
    }

    fun clearLogs() {
        serviceScope.launch {
            persistentLogger.clearLogs()
            _logs.value = emptyList()
        }
    }
    
    suspend fun getLogFile() = persistentLogger.getLogFile()

    /**
     * Persists whether a service session is active, for the "Keep last state"
     * app-start policy: a process killed without a clean stop (app
     * update/install, force kill, system kill) leaves the flag at true, and
     * the next app launch restores the service. Power Save idle keeps it true
     * - the session is still active while the node sleeps.
     */
    private fun persistServiceWasRunning(running: Boolean) {
        serviceScope.launch {
            try {
                ConfigRepository(applicationContext).saveServiceWasRunning(running)
            } catch (e: Exception) {
                logWarn("Could not persist service running state: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        persistentLogger = PersistentLogger(this)
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        registerScreenStateReceiver()
        verifyPermissions()
        
        // Load logs enabled setting
        serviceScope.launch {
            val repository = ConfigRepository(this@YggstackService)
            repository.logsEnabledFlow.collect { enabled ->
                logsEnabled = enabled
            }
        }
        
        // Load existing logs on startup
        serviceScope.launch {
            _logs.value = persistentLogger.readLogs()
        }
        
        // Load lastConfig from persistent storage
        loadLastConfigFromPreferences()
        logInfo("Service onCreate: lastConfig ${if (lastConfig != null) "loaded" else "not found"}")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Add Android build details if log is empty
                if (_logs.value.isEmpty() && logsEnabled) {
                    val deviceInfo = buildString {
                        appendLine("=== Android Device Information ===")
                        appendLine("Manufacturer: ${Build.MANUFACTURER}")
                        appendLine("Model: ${Build.MODEL}")
                        appendLine("Device: ${Build.DEVICE}")
                        appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("Build ID: ${Build.ID}")
                        append("=================================")
                    }
                    addLogBatch(deviceInfo)
                }
                logInfo("onStartCommand: ACTION_START received")
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONFIG, YggstackConfigParcelable::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<YggstackConfigParcelable>(EXTRA_CONFIG)
                }
                config?.let { startYggstack(it.toYggstackConfig()) }
            }
            ACTION_STOP -> {
                logInfo("onStartCommand: ACTION_STOP received")
                stopYggstack()
            }
            ACTION_WAKE_NOW -> {
                logInfo("onStartCommand: ACTION_WAKE_NOW received")
                wakeNow("manual")
            }
            null -> {
                // Service was restarted by system after being killed
                logWarn("=== WARNING: Service restarted by system (intent=null) ===")
                logWarn("This indicates the app/service was killed by the system")
                if (lastConfig != null && !_isRunning.value) {
                    logInfo("Attempting automatic restart with last config after system kill")
                    startYggstack(lastConfig!!)
                } else if (_isRunning.value) {
                    logInfo("Service claims to be running - checking state consistency")
                } else {
                    logInfo("No config available - service will remain stopped")
                    logInfo("User must manually restart the service")
                }
            }
        }
        // Restart service if killed by system, preserving lastConfig
        return START_STICKY
    }

    override fun onDestroy() {
        logInfo("=== YggstackService onDestroy - service being destroyed ===")
        super.onDestroy()
        serviceAlive = false
        unregisterScreenStateReceiver()
        unregisterNetworkCallback()
        // Stop the Go layer synchronously BEFORE cancelling serviceScope.
        // stopYggstack() is coroutine-based: if we called it here, the immediately
        // following serviceScope.cancel() would cancel that coroutine before it even
        // executes, leaving Go goroutines running and UDP/TCP ports bound.
        // That causes "address already in use" on the next START_STICKY restart.
        val instanceToStop = yggstack
        yggstack = null
        _isRunning.value = false
        _isSessionActive.value = false
        // Service scope is about to be cancelled - persist synchronously
        try {
            kotlinx.coroutines.runBlocking {
                ConfigRepository(applicationContext).saveServiceWasRunning(false)
            }
        } catch (_: Exception) {}
        clearSessionPortCounters()
        if (instanceToStop != null) {
            try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(4500L) {
                        instanceToStop.stop()
                    }
                }
            } catch (_: Exception) {
                logWarn("onDestroy: stop timed out or errored - process will clean up remaining resources")
            }
        }
        releaseMulticastLock()
        releaseWakeLock()
        stopPlaceholderListeners()
        abortSplices()
        idlePowerSaveMonitorJob?.cancel()
        serviceScope.cancel()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        logInfo("=== onTaskRemoved called - app task removed from recent apps ===")
        logInfo("Reason: User swiped app away from recents or system cleared task")
        logInfo("Current state: isRunning=${_isRunning.value}, hasConfig=${lastConfig != null}")
        
        super.onTaskRemoved(rootIntent)
        
        // If service was running, restart it with the saved configuration
        if (_isRunning.value && lastConfig != null) {
            logInfo("Service was running - scheduling restart with saved config")
            logInfo("Config has ${lastConfig!!.peers.size} peer(s), beacon=${lastConfig!!.multicastBeacon}, listen=${lastConfig!!.multicastListen}")
            
            // Save running state to SharedPreferences
            sharedPreferences.edit().putBoolean(PREF_WAS_RUNNING, true).apply()
            
            // Create restart intent
            val restartIntent = Intent(applicationContext, YggstackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, YggstackConfigParcelable.fromYggstackConfig(lastConfig!!))
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
            
            logInfo("Restart intent sent - service will be recreated by system")
        } else if (!_isRunning.value && lastConfig != null) {
            logInfo("Service was stopped - will not restart (config preserved for manual restart)")
            sharedPreferences.edit().putBoolean(PREF_WAS_RUNNING, false).apply()
        } else {
            logInfo("No configuration available - service will remain stopped")
            sharedPreferences.edit().putBoolean(PREF_WAS_RUNNING, false).apply()
        }
    }

    fun startYggstack(config: YggstackConfig) {
        serviceScope.launch {
            // Use mutex to prevent concurrent start/stop operations
            if (!operationMutex.tryLock()) {
                logInfo("Operation already in progress - ignoring start request")
                wakeInProgress = false
                return@launch
            }
            
            try {
                if (_isRunning.value) {
                    logInfo("Yggstack is already running")
                    return@launch
                }
                
                _isTransitioning.value = true
                
                // Store config for crash recovery and persistence
                lastConfig = config
                saveLastConfigToPreferences(config)
                logDebug("Config saved to persistent storage")
                crashRestartAttempts = 0
                
                // Force cleanup any zombie instance before starting
                if (yggstack != null) {
                    logInfo("Cleaning up existing Yggstack instance...")
                    try {
                        yggstack?.stop()
                    } catch (e: Exception) {
                        logError("Error cleaning up old instance: ${e.message}")
                    }
                    yggstack = null
                    kotlinx.coroutines.delay(500) // Give it time to fully stop
                }
                
                logInfo("Starting Yggstack...")
                logInfo("App version: ${link.yggdrasil.yggstack.android.BuildConfig.VERSION_NAME}")
                logInfo("Commit: ${link.yggdrasil.yggstack.android.BuildConfig.COMMIT_HASH}")
                startForeground(NOTIFICATION_ID, createNotification("Starting...", 0, 0))
                invalidateNotificationDedupe()

                // Create Yggstack instance
                yggstack = Mobile.newYggstack()
                
                // Only set log callback if logging is enabled
                if (logsEnabled) {
                    yggstack?.setLogCallback(object : LogCallback {
                        override fun onLog(message: String) {
                            addLog(message.trim())
                        }
                    })
                }
                
                // Use log level from config
                val logLevel = config.logLevel
                currentLogLevel = logLevel
                yggstack?.setLogLevel(logLevel)
                logInfo("Log level: $logLevel")

                // Build config JSON (handles both new and existing private keys)
                logDebug("Loading configuration...")
                logInfo("Config summary: ${config.peers.size} peer(s), multicast=${config.multicastBeacon || config.multicastListen}, proxy=${config.proxyEnabled}")
                val configJson = buildConfigJson(config)
                
                // Store SANITIZED config JSON for diagnostics display (private key truncated)
                _fullConfigJSON.value = sanitizeConfigJson(configJson)

                logDebug("Calling loadConfigJSON...")
                yggstack?.loadConfigJSON(configJson)
                logInfo("Config loaded successfully")

                // Start with optional SOCKS proxy and DNS server
                val socksAddress = if (config.proxyEnabled && config.socksProxy.isNotBlank()) {
                    config.socksProxy
                } else {
                    ""
                }

                val dnsServer = if (config.proxyEnabled && config.dnsServer.isNotBlank()) {
                    ConfigRepository.normalizeDnsServer(config.dnsServer)
                } else {
                    ""
                }

                // Clear any existing mappings from previous runs to avoid duplicates
                yggstack?.clearLocalMappings()
                yggstack?.clearRemoteMappings()

                // Drop stale listener stats from a previous run so the Ports
                // page never flashes old numbers while the first tick is pending
                _portStatsJSON.resetReplayCache()

                // Setup port mappings BEFORE starting
                // This ensures mappings are in place when start() runs
                setupPortMappings(config)

                // Acquire MulticastLock if multicast is enabled and we're on WiFi
                if ((config.multicastBeacon || config.multicastListen) && checkNetworkType()) {
                    logInfo("Multicast enabled (beacon=${config.multicastBeacon}, listen=${config.multicastListen}) and on WiFi - acquiring MulticastLock")
                    isOnWifi = true
                    acquireMulticastLock()
                } else if (config.multicastBeacon || config.multicastListen) {
                    logInfo("Multicast enabled but not on WiFi - MulticastLock not acquired")
                    isOnWifi = false
                } else {
                    logInfo("Multicast disabled - skipping MulticastLock")
                    isOnWifi = false
                }

                // Acquire WiFi lock if on WiFi to prevent power-save
                if (checkNetworkType()) {
                    acquireWifiLock()
                }

                // Partial wake lock is now scoped to the running node's lifetime
                // (released on stop/idle so Power Save can fully sleep the CPU)
                acquireWakeLock()

                logDebug("Calling start() with SOCKS='$socksAddress', DNS='$dnsServer'...")
                yggstack?.start(socksAddress, dnsServer)
                logInfo("Start() completed successfully")

                // Get and store the Yggdrasil IP AFTER starting (with timeout to prevent hangs)
                logDebug("Getting Yggdrasil IP address...")
                try {
                    val address = kotlinx.coroutines.withTimeout(5000L) {
                        yggstack?.address
                    }
                    _yggdrasilIp.value = address
                    logInfo("Yggdrasil IP: $address")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    logWarn("WARNING: Timeout getting Yggdrasil IP (continuing anyway)")
                    _yggdrasilIp.value = null
                } catch (e: Exception) {
                    logError("WARNING: Failed to get Yggdrasil IP: ${e.message} (continuing anyway)")
                    _yggdrasilIp.value = null
                }

                logDebug("Setting service running state...")
                val wasIdle = _isPowerSaveIdle.value
                _isRunning.value = true
                _isSessionActive.value = true
                persistServiceWasRunning(true)
                _peerCount.value = 0
                stopPlaceholderListeners()
                _isPowerSaveIdle.value = false
                _powerSaveIdleSince.value = null
                // Power Save accounting: waking from idle continues the current
                // session, a fresh start begins a new one
                val stateChangedAt = System.currentTimeMillis()
                if (wasIdle) {
                    _powerSaveIdleMillis.value += (stateChangedAt - _powerSaveStateSince.value).coerceAtLeast(0)
                } else {
                    _powerSaveUpMillis.value = 0
                    _powerSaveIdleMillis.value = 0
                    clearSessionPortCounters()
                }
                _powerSaveStateSince.value = stateChangedAt
                logInfo("Service state updated: isRunning=true")

                // Register network callback to monitor WiFi/Cellular changes
                registerNetworkCallback()

                // Start peer cache updater if multicast listen is enabled (beacon only creates inbound connections)
                if (config.multicastListen) {
                    logInfo("Multicast listen enabled - starting peer cache updater")
                    startPeerCacheUpdater()
                }

                // Clean up stale cached peers on startup
                cleanupPeerCache()

                logInfo("Yggstack started successfully")
                updateNotification("Connected", 0, 0)

                // Start monitoring for peer details / port stats subscriptions (lazy-load, independent cadences)
                startPeerDetailsSubscriptionMonitor()
                startPortStatsSubscriptionMonitor()

                // (Re)start the Power Save idle monitor if eligible; harmless no-op otherwise
                syncPowerSaveMonitor(config)

            } catch (e: Exception) {
                logError("ERROR starting Yggstack: ${e.message}")
                logError("Stack trace: ${e.stackTraceToString().take(500)}")

                // Clean up properly on error
                try {
                    yggstack?.stop()
                } catch (stopError: Exception) {
                    logError("Error during cleanup: ${stopError.message}")
                }
                yggstack = null
                abortSplices()
                _isRunning.value = false
                _isSessionActive.value = false
                persistServiceWasRunning(false)
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _totalPeerCount.value = 0
                _powerSaveUpMillis.value = 0
                _powerSaveIdleMillis.value = 0
                _powerSaveStateSince.value = 0
                clearSessionPortCounters()

                // Unregister network callback on error
                unregisterNetworkCallback()
                
                // Release MulticastLock on error
                releaseMulticastLock()

                // Cancel notification
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)

                // Stop foreground and service to force UI sync
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                
                // Update notification with error state
                val errorNotification = createNotification("Failed to start - check logs", 0, 0, showStopButton = false)
                notificationManager.notify(NOTIFICATION_ID, errorNotification)
                
                logError("Service stopped due to error. Please check configuration and try again.")
            } finally {
                logDebug("Cleanup: Releasing operation mutex and resetting transitioning state")
                wakeInProgress = false
                _isTransitioning.value = false
                operationMutex.unlock()
                logInfo("Operation mutex released, transitioning state reset")
            }
        }
    }

    fun stopYggstack(enterPowerSaveIdle: Boolean = false) {
        serviceScope.launch {
            // Wait up to 2 s for any concurrent start operation to release the mutex.
            // Using tryLock() and silently dropping the stop leaves the service in a
            // half-alive state (broken peer connections, no recovery) when a network
            // switch triggers stop while start is still initialising.
            val lockAcquired = try {
                kotlinx.coroutines.withTimeout(2000L) {
                    operationMutex.lock()
                    true
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                false
            }
            if (!lockAcquired) {
                logWarn("Stop: could not acquire operation lock within 2s - forcing minimal cleanup")
                val stale = yggstack
                yggstack = null
                _isRunning.value = false
                _isSessionActive.value = false
                persistServiceWasRunning(false)
                clearSessionPortCounters()
                _isTransitioning.value = false
                if (stale != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        try { stale.stop() } catch (_: Exception) {}
                    }
                }
                return@launch
            }
            
            try {
                // Force cleanup even if _isRunning is false (handles desync state),
                // but not while intentionally idle - a full stop must still tear
                // down placeholder listeners and the foreground service in that case
                if (!_isRunning.value && yggstack == null && !_isPowerSaveIdle.value) {
                    logInfo("Service already stopped")
                    // The service may have been (re)started just to receive this
                    // stop request (e.g. the "Keep stopped" app-start policy);
                    // shut it down so no idle instance lingers
                    stopSelf()
                    return@launch
                }
                
                if (!_isRunning.value && yggstack != null) {
                    logWarn("WARNING: State desync detected - forcing cleanup of zombie instance")
                }
                
                _isTransitioning.value = true

                logInfo(if (enterPowerSaveIdle) "Power Save: powering down node..." else "Stopping Yggstack...")
                // NOTE: Keep _isRunning = true during stop so UI shows correct state
                // It will be set to false in the finally block after everything completes
                
                // Wrap entire stop operation with safety timeout
                try {
                    kotlinx.coroutines.withTimeout(3000L) {
                        // Cancel peer/port stats jobs and the idle monitor
                        peerDetailsJob?.cancel()
                        peerDetailsJob = null
                        portStatsJob?.cancel()
                        portStatsJob = null
                        peerDetailsSubscriptionJob?.cancel()
                        peerDetailsSubscriptionJob = null
                        portStatsSubscriptionJob?.cancel()
                        portStatsSubscriptionJob = null
                        idlePowerSaveMonitorJob?.cancel()
                        idlePowerSaveMonitorJob = null
                        _idleCountdownSeconds.value = null
                        
                        // Unregister network callback
                        unregisterNetworkCallback()
                        
                        // Release WiFi lock if held
                        releaseWifiLock()
                        
                        // Release MulticastLock if held
                        releaseMulticastLock()

                        // Release the partial wake lock - only the foreground
                        // service itself remains held during Power Save idle
                        releaseWakeLock()
                        
                        // Stop yggstack. A throwing stop must not divert to the
                        // outer error handler: that path skips the Power Save
                        // re-arm below and would leave the ports unable to wake
                        // the node. The placeholder bind retries until the
                        // ports are actually released even if this stop is slow.
                        try {
                            yggstack?.stop()
                        } catch (e: Exception) {
                            logError("Error stopping Yggstack during power-down: ${e.message}")
                        }
                        yggstack = null
                        
                        _yggdrasilIp.value = null
                        _peerCount.value = 0
                        _totalPeerCount.value = 0
                        _generatedPrivateKey.value = null
                        _portStatsJSON.resetReplayCache()

                        logInfo("Yggstack stopped")
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    logWarn("WARNING: Stop operation timed out after 3 seconds - forcing cleanup")
                    // Force cleanup on timeout
                    releaseWakeLock()
                    yggstack = null
                    _yggdrasilIp.value = null
                    _peerCount.value = 0
                    _totalPeerCount.value = 0
                    _generatedPrivateKey.value = null
                    _portStatsJSON.resetReplayCache()
                }
                
                if (enterPowerSaveIdle) {
                    // Real listeners are guaranteed closed by now (yggstack.stop() above),
                    // so it's safe to bind placeholders on the same host:port.
                    lastConfig?.let { startPlaceholderListeners(it) }
                    // Session accounting: the up period ends here; idle accrues
                    // until the node wakes. isSessionActive stays true so the
                    // Ports screen keeps its cards and counters.
                    val poweredDownAt = System.currentTimeMillis()
                    _powerSaveUpMillis.value += (poweredDownAt - _powerSaveStateSince.value).coerceAtLeast(0)
                    _powerSaveStateSince.value = poweredDownAt
                    _isPowerSaveIdle.value = true
                    if (_powerSaveIdleSince.value == null) {
                        _powerSaveIdleSince.value = System.currentTimeMillis()
                    }
                    updateIdlePowerSaveNotification()
                    logInfo("Power Save: node powered down, listening for wake triggers")
                } else {
                    stopPlaceholderListeners()
                    abortSplices()
                    _isPowerSaveIdle.value = false
                    _powerSaveIdleSince.value = null
                    _isSessionActive.value = false
                    persistServiceWasRunning(false)
                    _powerSaveUpMillis.value = 0
                    _powerSaveIdleMillis.value = 0
                    _powerSaveStateSince.value = 0
                    clearSessionPortCounters()

                    // Cancel the notification
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(NOTIFICATION_ID)
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
            } catch (e: Exception) {
                logError("Error stopping Yggstack: ${e.message}")
                // Force cleanup even on error
                releaseWakeLock()
                stopPlaceholderListeners()
                _isPowerSaveIdle.value = false
                _powerSaveIdleSince.value = null
                _isSessionActive.value = false
                persistServiceWasRunning(false)
                _powerSaveUpMillis.value = 0
                _powerSaveIdleMillis.value = 0
                _powerSaveStateSince.value = 0
                clearSessionPortCounters()
                yggstack = null
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _totalPeerCount.value = 0
                _generatedPrivateKey.value = null
                hasNoNetwork = false
                
                // Unregister network callback on error too
                unregisterNetworkCallback()
                
                // Stop peer cache updater on error
                stopPeerCacheUpdater()
                
                // Release MulticastLock on error too
                releaseMulticastLock()
                
                // Cancel notification on error too
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
            } finally {
                // IMPORTANT: Set _isRunning = false here, AFTER everything is truly stopped
                // This ensures UI doesn't show "Start" button until stop is complete
                _isRunning.value = false
                _isTransitioning.value = false
                hasNoNetwork = false  // Reset network state
                logInfo("Cleanup: Releasing stop operation mutex and resetting transitioning state")
                operationMutex.unlock()
                logInfo("Stop operation complete - state set to stopped")
            }
        }
    }

    /**
     * Updates the in-service config snapshot and refreshes the diagnostics config display.
     * Call this whenever a live peer change is made so the Config card stays in sync.
     */
    fun updateLiveConfig(config: YggstackConfig) {
        lastConfig = config
        saveLastConfigToPreferences(config)
        val updatedJson = buildConfigJson(config)
        _fullConfigJSON.value = sanitizeConfigJson(updatedJson)
    }

    /**
     * Appends the ?maxbackoff=Xs query parameter to a raw peer URI, matching
     * the same logic used in buildConfigJson. Used when adding/removing live peers.
     */
    private fun withMaxBackoff(rawUri: String): String {
        if (lastConfig?.maxBackoffEnabled == false) {
            return rawUri
        }
        val maxBackoffValue = "${lastConfig?.maxBackoff ?: 5}s"
        return when {
            rawUri.contains("maxbackoff=") -> rawUri
            rawUri.contains("?") -> "$rawUri&maxbackoff=$maxBackoffValue"
            else -> "$rawUri?maxbackoff=$maxBackoffValue"
        }
    }

    /**
     * Adds a peer to the running yggdrasil core on the fly (does not modify stored config).
     */
    fun addLivePeer(rawUri: String) {
        serviceScope.launch {
            try {
                val fullUri = withMaxBackoff(rawUri)
                yggstack?.addLivePeer(fullUri)
                logInfo("Live peer added: $rawUri")
            } catch (e: Exception) {
                logError("Error adding live peer $rawUri: ${e.message}")
            }
        }
    }

    /**
     * Removes a peer from the running yggdrasil core on the fly (does not modify stored config).
     */
    fun removeLivePeer(rawUri: String) {
        serviceScope.launch {
            try {
                val fullUri = withMaxBackoff(rawUri)
                yggstack?.removeLivePeer(fullUri)
                if (fullUri != rawUri) {
                    yggstack?.removeLivePeer(rawUri)
                }
                logInfo("Live peer removed: $rawUri")
            } catch (e: Exception) {
                logError("Error removing live peer $rawUri: ${e.message}")
            }
        }
    }

    private fun buildConfigJson(config: YggstackConfig): String {
        // If no private key is provided, generate a complete new config
        if (config.privateKey.isBlank()) {
            logInfo("No private key found - generating new configuration...")
            val newConfigJson = Mobile.generateConfig()
            logInfo("Generated config length: ${newConfigJson.length} chars")

            // Add Certificate field if missing (required by core.New)
            val configWithCert = if (!newConfigJson.contains("\"Certificate\"")) {
                // Insert Certificate field after PrivateKey
                newConfigJson.replace(
                    Regex("(\"PrivateKey\":\\s*\"[^\"]+\",)"),
                    "$1\n  \"Certificate\": null,"
                )
            } else {
                newConfigJson
            }

            // Extract the private key to save it back to the repository
            val keyMatch = Regex("\"PrivateKey\":\\s*\"([^\"]+)\"").find(newConfigJson)
            val extractedKey = keyMatch?.groupValues?.get(1) ?: ""

            if (extractedKey.isNotBlank()) {
                logDebug("Private key extracted (length: ${extractedKey.length}, key: ${truncatePrivateKey(extractedKey)})")
                _generatedPrivateKey.value = extractedKey
                
                // CRITICAL FIX: Update lastConfig with the generated key and re-save to SharedPreferences
                // This ensures the key persists across service restarts
                lastConfig = lastConfig?.copy(privateKey = extractedKey)
                lastConfig?.let { saveLastConfigToPreferences(it) }
                logDebug("Generated key saved to persistent storage")
            } else {
                logError("ERROR: Failed to extract generated private key from config!")
            }

            // Apply peers and multicast configuration
            var finalConfig = configWithCert
            
            // Combine static peers with cached peers for fast reconnection (only if multicast listen is enabled)
            val allPeers = config.peers.filter { it !in config.disabledPeers }.toMutableList()
            val now = System.currentTimeMillis()
            val recentCutoff = now - PEER_CACHE_STALE_TIME_MS
            
            // Only use cached peers if multicast listen is enabled (beacon creates inbound connections we can't reconnect to)
            val recentCachedPeers = if (config.multicastListen) {
                config.cachedPeers.filter { cached ->
                    cached.lastSeen > recentCutoff && 
                    cached.successCount > cached.failureCount &&
                    !allPeers.contains(cached.uri) &&
                    !config.disabledPeers.contains(cached.uri)
                }
            } else {
                emptyList()
            }
            
            if (recentCachedPeers.isNotEmpty()) {
                allPeers.addAll(recentCachedPeers.map { it.uri })
                logInfo("Added ${recentCachedPeers.size} cached peer(s) for fast reconnection:")
                recentCachedPeers.forEachIndexed { index, cached ->
                    logInfo("  Cached ${index + 1}: ${cached.uri} (last seen ${(now - cached.lastSeen) / 1000}s ago)")
                }
            }
            
            if (allPeers.isNotEmpty()) {
                val staticActive = config.peers.count { it !in config.disabledPeers }
                logInfo("Configuring $staticActive static + ${recentCachedPeers.size} cached = ${allPeers.size} total peer(s):")
                config.peers.forEachIndexed { index, peer ->
                    val state = if (peer in config.disabledPeers) " [disabled]" else ""
                    logInfo("  Static ${index + 1}: ${peer}${state}")
                }
                
                val peersWithBackoff = allPeers.map { peer ->
                    if (!config.maxBackoffEnabled) {
                        peer
                    } else {
                        val maxBackoffValue = "${config.maxBackoff}s"
                        if (peer.contains("?")) {
                            if (!peer.contains("maxbackoff=")) {
                                "$peer&maxbackoff=$maxBackoffValue"
                            } else {
                                peer // Already has maxbackoff
                            }
                        } else {
                            "$peer?maxbackoff=$maxBackoffValue"
                        }
                    }
                }
                val peersJson = peersWithBackoff.joinToString("\",\"", "[\"", "\"]")
                finalConfig = finalConfig.replace(
                    Regex("\"Peers\":\\s*\\[\\s*\\]"),
                    "\"Peers\": $peersJson"
                )
            }
            
            // Handle multicast discovery switch
            if (!config.multicastBeacon && !config.multicastListen) {
                logInfo("Multicast discovery disabled - removing MulticastInterfaces")
                finalConfig = finalConfig.replace(
                    Regex("\"MulticastInterfaces\":\\s*\\[[^\\]]*\\]"),
                    "\"MulticastInterfaces\": []"
                )
            } else {
                logInfo("Multicast discovery enabled (beacon=${config.multicastBeacon}, listen=${config.multicastListen}) - using configured settings")
                // Update the generated config with specific beacon/listen values
                val beaconValue = config.multicastBeacon.toString().lowercase()
                val listenValue = config.multicastListen.toString().lowercase()
                finalConfig = finalConfig.replace(
                    Regex("\"Beacon\":\\s*(true|false)"),
                    "\"Beacon\": $beaconValue"
                )
                finalConfig = finalConfig.replace(
                    Regex("\"Listen\":\\s*(true|false)"),
                    "\"Listen\": $listenValue"
                )
            }

            finalConfig = applyGroupPasswordToConfigJson(finalConfig, config)

            return finalConfig
        }

        // Build config with existing private key
        // IMPORTANT: Must match the structure from Mobile.generateConfig()
        logInfo("Using existing private key (length: ${config.privateKey.length}, key: ${truncatePrivateKey(config.privateKey)})")
        
        // Log peer configuration
        val activePeers = config.peers.filter { it !in config.disabledPeers }
        if (config.peers.isEmpty()) {
            logWarn("No peers configured - node will be isolated without multicast discovery")
        } else {
            val disabledCount = config.disabledPeers.size
            logInfo("Configuring ${activePeers.size}/${config.peers.size} peer(s) (${disabledCount} disabled):")
            config.peers.forEachIndexed { index, peer ->
                val state = if (peer in config.disabledPeers) " [disabled]" else ""
                logInfo("  Peer ${index + 1}: ${peer}${state}")
            }
        }
        
        val peers = if (activePeers.isEmpty()) {
            "[]"
        } else {
            val peersWithBackoff = activePeers.map { peer ->
                if (!config.maxBackoffEnabled) {
                    peer
                } else {
                    val maxBackoffValue = "${config.maxBackoff}s"
                    if (peer.contains("?")) {
                        if (!peer.contains("maxbackoff=")) {
                            "$peer&maxbackoff=$maxBackoffValue"
                        } else {
                            peer // Already has maxbackoff
                        }
                    } else {
                        "$peer?maxbackoff=$maxBackoffValue"
                    }
                }
            }
            peersWithBackoff.joinToString("\", \"", "[\"", "\"]")
        }

        val multicastInterfaces = if (config.multicastBeacon || config.multicastListen) {
            logInfo("Multicast discovery enabled (beacon=${config.multicastBeacon}, listen=${config.multicastListen})")
            """[
    {
      "Regex": ".*",
      "Beacon": ${config.multicastBeacon.toString().lowercase()},
      "Listen": ${config.multicastListen.toString().lowercase()},
      "Password": ""
    }
  ]"""
        } else {
            logInfo("Multicast discovery disabled - using empty configuration")
            "[]"
        }

        val groupPassword = if (config.groupPasswordEnabled && config.groupPassword.isNotBlank()) {
            config.groupPassword
        } else {
            ""
        }
        val escapedGroupPassword = jsonEscape(groupPassword)

        // Use the same structure as generated config
        val manualConfig = """{
  "PrivateKey": "${config.privateKey}",
  "Certificate": null,
  "Peers": $peers,
  "InterfacePeers": {},
  "Listen": [],
  "AdminListen": "none",
  "MulticastInterfaces": $multicastInterfaces,
  "AllowedPublicKeys": [],
    "GroupPassword": "$escapedGroupPassword",
  "IfName": "auto",
  "IfMTU": 65535,
  "NodeInfoPrivacy": false,
  "NodeInfo": null
}"""

        logDebug("Built manual config matching generated structure")
        return manualConfig
    }

    private fun setupPortMappings(config: YggstackConfig) {
        try {
            // Note: Mappings should be set up BEFORE calling start()
            // so the handlers are started properly in the Start() function
            
            // Setup Forward Remote Port (local mappings - forward from local to remote Yggdrasil)
            if (config.forwardEnabled && config.forwardMappings.isNotEmpty()) {
                logDebug("Setting up ${config.forwardMappings.size} forward port mapping(s)...")
                config.forwardMappings.forEach { mapping ->
                    if (!mapping.enabled) {
                        logInfo("↷ Skipping disabled forward mapping: ${mapping.protocol} ${mapping.localIp}:${mapping.localPort} -> [${mapping.remoteIp}]:${mapping.remotePort}")
                        return@forEach
                    }
                    try {
                        val localAddr = "${mapping.localIp}:${mapping.localPort}"
                        val remoteAddr = "[${mapping.remoteIp}]:${mapping.remotePort}"
                        
                        logDebug("Configuring ${mapping.protocol} forward mapping: $localAddr -> $remoteAddr")
                        
                        when (mapping.protocol) {
                            link.yggdrasil.yggstack.android.data.Protocol.TCP -> {
                                yggstack?.addLocalTCPMapping(localAddr, remoteAddr)
                                logInfo("✓ Added TCP forward: $localAddr -> $remoteAddr")
                            }
                            link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                                yggstack?.addLocalUDPMapping(localAddr, remoteAddr)
                                logInfo("✓ Added UDP forward: $localAddr -> $remoteAddr")
                            }
                        }
                    } catch (e: Exception) {
                        logError("✗ Error adding forward mapping: ${e.message}")
                        logError("Stack trace: ${e.stackTraceToString().take(300)}")
                    }
                }
            } else {
                logInfo("No forward mappings configured (enabled=${config.forwardEnabled}, count=${config.forwardMappings.size})")
            }

            // Setup Expose Local Port (remote mappings - expose local port on Yggdrasil)
            if (config.exposeEnabled && config.exposeMappings.isNotEmpty()) {
                logDebug("Setting up ${config.exposeMappings.size} expose port mapping(s)...")
                config.exposeMappings.forEach { mapping ->
                    if (!mapping.enabled) {
                        logInfo("↷ Skipping disabled expose mapping: ${mapping.protocol} port ${mapping.yggPort} -> ${mapping.localIp}:${mapping.localPort}")
                        return@forEach
                    }
                    try {
                        val localAddr = "${mapping.localIp}:${mapping.localPort}"
                        
                        logDebug("Configuring ${mapping.protocol} expose mapping: Ygg port ${mapping.yggPort} -> $localAddr")
                        
                        when (mapping.protocol) {
                            link.yggdrasil.yggstack.android.data.Protocol.TCP -> {
                                yggstack?.addRemoteTCPMapping(mapping.yggPort.toLong(), localAddr)
                                logInfo("✓ Exposed TCP port ${mapping.yggPort} -> $localAddr")
                            }
                            link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                                yggstack?.addRemoteUDPMapping(mapping.yggPort.toLong(), localAddr)
                                logInfo("✓ Exposed UDP port ${mapping.yggPort} -> $localAddr")
                            }
                        }
                    } catch (e: Exception) {
                        logError("✗ Error adding expose mapping: ${e.message}")
                        logError("Stack trace: ${e.stackTraceToString().take(300)}")
                    }
                }
            } else {
                logInfo("No expose mappings configured (enabled=${config.exposeEnabled}, count=${config.exposeMappings.size})")
            }

            if (!config.forwardEnabled && !config.exposeEnabled) {
                logInfo("Port forwarding disabled - no mappings will be configured")
            }
        } catch (e: Exception) {
            logError("✗ Error setting up port mappings: ${e.message}")
            logError("Stack trace: ${e.stackTraceToString().take(300)}")
        }
    }

    /**
     * Enable or disable a single expose (remote) mapping while the service is running.
     * Calls the corresponding Add/Remove binding on the Go layer and logs the change.
     */
    fun enableExposeMapping(mapping: link.yggdrasil.yggstack.android.data.ExposeMapping, enable: Boolean) {
        val localAddr = "${mapping.localIp}:${mapping.localPort}"
        val action = if (enable) "Enabling" else "Disabling"
        logInfo("$action expose rule: ${mapping.protocol.name} port ${mapping.yggPort} -> $localAddr")
        try {
            when (mapping.protocol) {
                link.yggdrasil.yggstack.android.data.Protocol.TCP -> {
                    if (enable) {
                        yggstack?.addRemoteTCPMapping(mapping.yggPort.toLong(), localAddr)
                        logInfo("✓ Enabled TCP expose: port ${mapping.yggPort} -> $localAddr")
                    } else {
                        yggstack?.removeRemoteTCPMapping(mapping.yggPort.toLong(), localAddr)
                        logInfo("✓ Disabled TCP expose: port ${mapping.yggPort} -> $localAddr")
                    }
                }
                link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                    if (enable) {
                        yggstack?.addRemoteUDPMapping(mapping.yggPort.toLong(), localAddr)
                        logInfo("✓ Enabled UDP expose: port ${mapping.yggPort} -> $localAddr")
                    } else {
                        yggstack?.removeRemoteUDPMapping(mapping.yggPort.toLong(), localAddr)
                        logInfo("✓ Disabled UDP expose: port ${mapping.yggPort} -> $localAddr")
                    }
                }
            }
        } catch (e: Exception) {
            logError("✗ Error ${action.lowercase()} expose rule: ${e.message}")
        }
    }

    /**
     * Enable or disable a single forward (local) mapping while the service is running.
     */
    fun enableForwardMapping(mapping: link.yggdrasil.yggstack.android.data.ForwardMapping, enable: Boolean) {
        val localAddr = "${mapping.localIp}:${mapping.localPort}"
        val remoteAddr = "[${mapping.remoteIp}]:${mapping.remotePort}"
        val action = if (enable) "Enabling" else "Disabling"
        logInfo("$action forward rule: ${mapping.protocol.name} $localAddr -> $remoteAddr")
        try {
            when (mapping.protocol) {
                link.yggdrasil.yggstack.android.data.Protocol.TCP -> {
                    if (enable) {
                        yggstack?.addLocalTCPMapping(localAddr, remoteAddr)
                        logInfo("✓ Enabled TCP forward: $localAddr -> $remoteAddr")
                    } else {
                        yggstack?.removeLocalTCPMapping(localAddr, remoteAddr)
                        logInfo("✓ Disabled TCP forward: $localAddr -> $remoteAddr")
                    }
                }
                link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                    if (enable) {
                        yggstack?.addLocalUDPMapping(localAddr, remoteAddr)
                        logInfo("✓ Enabled UDP forward: $localAddr -> $remoteAddr")
                    } else {
                        yggstack?.removeLocalUDPMapping(localAddr, remoteAddr)
                        logInfo("✓ Disabled UDP forward: $localAddr -> $remoteAddr")
                    }
                }
            }
        } catch (e: Exception) {
            logError("✗ Error ${action.lowercase()} forward rule: ${e.message}")
        }
    }

    // Log level helper functions
    private fun logError(message: String) {
        // Always write errors to logcat (even in release builds)
        android.util.Log.e(LOG_TAG, message)
        if (logsEnabled && shouldLog("error")) addLog("[E] $message")
    }
    
    private fun logWarn(message: String) {
        // Always write warnings to logcat (even in release builds)
        android.util.Log.w(LOG_TAG, message)
        if (logsEnabled && shouldLog("warn")) addLog("[W] $message")
    }
    
    private fun logInfo(message: String) {
        // Only write info to logcat in debug builds
        if (BuildConfig.DEBUG) {
            android.util.Log.i(LOG_TAG, message)
        }
        if (logsEnabled && shouldLog("info")) addLog("[I] $message")
    }
    
    private fun logDebug(message: String) {
        // Only write debug to logcat in debug builds
        if (BuildConfig.DEBUG) {
            android.util.Log.d(LOG_TAG, message)
        }
        if (logsEnabled && shouldLog("debug")) addLog("[D] $message")
    }
    
    private fun shouldLog(level: String): Boolean {
        val levels = listOf("error", "warn", "info", "debug")
        val currentIndex = levels.indexOf(currentLogLevel)
        val requestedIndex = levels.indexOf(level)
        return currentIndex >= requestedIndex
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"

        _logs.value = (_logs.value + logEntry).takeLast(MAX_LOG_ENTRIES)
        
        // Also persist to file
        serviceScope.launch {
            persistentLogger.appendLog(message)
        }
    }

    private fun addLogBatch(messages: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        
        val lines = messages.lines()
        val logEntries = lines.map { "[$timestamp] $it" }
        
        _logs.value = (_logs.value + logEntries).takeLast(MAX_LOG_ENTRIES)
        
        // Persist to file in one go
        serviceScope.launch {
            lines.forEach { line ->
                persistentLogger.appendLog(line)
            }
        }
    }

    private fun startPeerDetailsSubscriptionMonitor() {
        peerDetailsSubscriptionJob?.cancel()
        peerDetailsSubscriptionJob = serviceScope.launch {
            // Peers screen only: fixed 1s cadence, driven solely by whether
            // the Peers tab is currently collecting peerDetailsJSON
            _peerDetailsJSON.subscriptionCount.collect { count ->
                if (!_isRunning.value) {
                    logDebug("Service not running, ignoring peer details subscription changes")
                    return@collect
                }
                if (count > 0) {
                    logDebug("Peer details subscriber active, starting updater")
                    startPeerDetailsUpdater()
                } else {
                    logDebug("No peer details subscribers, stopping updater")
                    stopPeerDetailsUpdater()
                }
            }
        }
    }

    private fun stopPeerDetailsUpdater() {
        synchronized(this) {
            peerDetailsJob?.cancel()
            peerDetailsJob = null
        }
    }

    private fun startPeerDetailsUpdater() {
        // Guard against double-start: rapid tab switches can fire overlapping
        // subscription events, and two concurrent pollers would race on
        // peerDetailsJob and double the JNI traffic into the Go runtime.
        synchronized(this) {
            if (peerDetailsJob?.isActive == true) {
                logDebug("Peer details updater already running, skipping start")
                return
            }
            peerDetailsJob = serviceScope.launch {
                while (_isRunning.value) {
                    try {
                    // Double-check service is still running before updating
                    if (!_isRunning.value) break
                    
                    // Update Yggdrasil IP address and public key
                    try {
                        val address = yggstack?.address
                        _yggdrasilIp.value = address
                    } catch (e: Exception) {
                        logError("Error fetching Yggdrasil IP: ${e.message}")
                    }
                    
                    try {
                        val publicKey = yggstack?.publicKey
                        _yggdrasilPublicKey.value = publicKey
                    } catch (e: Exception) {
                        logError("Error fetching Yggdrasil public key: ${e.message}")
                    }
                    
                    val peersJson = yggstack?.getPeersJSON()
                    if (peersJson != null) {
                        _peerDetailsJSON.emit(peersJson)
                        // Update peer count from actual connected peers
                        try {
                            val jsonArray = JSONArray(peersJson)
                            val totalCount = jsonArray.length()
                            // Count only peers that are Up (connected)
                            var connectedCount = 0
                            for (i in 0 until jsonArray.length()) {
                                val peerObj = jsonArray.getJSONObject(i)
                                if (peerObj.optBoolean("Up", false)) {
                                    connectedCount++
                                }
                            }
                            _peerCount.value = connectedCount
                            _totalPeerCount.value = totalCount
                            
                            // Only update notification if still running
                            if (_isRunning.value) {
                                updateNotification("Connected", connectedCount, totalCount)
                            }
                        } catch (e: Exception) {
                            logError("Error parsing peer JSON: ${e.message}")
                            _peerCount.value = 0
                            _totalPeerCount.value = 0
                            if (_isRunning.value) {
                                updateNotification("Connected", 0, 0)
                            }
                        }
                    } else {
                        // Yggstack returned null - instance crashed/corrupted
                        logError("ERROR: getPeersJSON returned null - Yggstack instance is corrupted")
                        if (_isRunning.value) {
                            logError("Detected Yggstack crash - attempting automatic restart...")
                            _isRunning.value = false
                            _peerCount.value = 0
                            _totalPeerCount.value = 0
                            
                            // Attempt automatic restart if we have the config
                            if (lastConfig != null && crashRestartAttempts < MAX_CRASH_RESTART_ATTEMPTS) {
                                crashRestartAttempts++
                                val backoffDelay = (crashRestartAttempts * 2000L).coerceAtMost(10000L)
                                logError("Crash restart attempt $crashRestartAttempts/$MAX_CRASH_RESTART_ATTEMPTS (waiting ${backoffDelay}ms)...")
                                updateNotification("Restarting after crash...", 0, 0)
                                
                                kotlinx.coroutines.delay(backoffDelay)
                                
                                // Force cleanup of corrupted instance
                                try {
                                    yggstack?.stop()
                                } catch (e: Exception) {
                                    logError("Error stopping corrupted instance: ${e.message}")
                                }
                                yggstack = null
                                kotlinx.coroutines.delay(1000)
                                
                                // Restart with same config
                                logError("Restarting Yggstack after crash...")
                                startYggstack(lastConfig!!)
                            } else {
                                val reason = if (lastConfig == null) "no config available" else "max restart attempts reached"
                                logError("ERROR: Cannot auto-restart - $reason")
                                updateNotification("Crashed - manual restart required", 0, 0)
                            }
                        }
                        break
                    }
                } catch (e: Exception) {
                    logError("Error fetching peer stats: ${e.message}")
                    // Don't break on transient errors, but log them
                }
                kotlinx.coroutines.delay(1000) // Update every 1 second
            }
            if (_isRunning.value) {
                logInfo("Peer details updater stopped")
            }
            }
        }
    }

    private fun startPortStatsSubscriptionMonitor() {
        portStatsSubscriptionJob?.cancel()
        portStatsSubscriptionJob = serviceScope.launch {
            // Ports screen only: fixed 1s cadence, driven solely by whether
            // the Ports tab is currently collecting portStatsJSON. Power
            // Save's own idle detection uses an entirely separate poll
            // (idlePowerSaveMonitorJob) so this cadence is never affected by it.
            _portStatsJSON.subscriptionCount.collect { count ->
                if (!_isRunning.value) {
                    logDebug("Service not running, ignoring port stats subscription changes")
                    return@collect
                }
                if (count > 0) {
                    logDebug("Port stats subscriber active, starting updater")
                    startPortStatsUpdater()
                } else {
                    logDebug("No port stats subscribers, stopping updater")
                    stopPortStatsUpdater()
                }
            }
        }
    }

    private fun stopPortStatsUpdater() {
        synchronized(this) {
            portStatsJob?.cancel()
            portStatsJob = null
        }
    }

    private fun startPortStatsUpdater() {
        synchronized(this) {
            if (portStatsJob?.isActive == true) {
                logDebug("Port stats updater already running, skipping start")
                return
            }
            portStatsJob = serviceScope.launch {
                while (_isRunning.value) {
                    try {
                        val listenersJson = yggstack?.getListenersJSON()
                        if (listenersJson != null) {
                            lastRawListenersJSON = listenersJson
                            val accumulated = accumulatePortStats(listenersJson)
                            // Skip empty polls: after a Power Save wake the first
                            // tick can race listener registration, and emitting an
                            // empty list would flash away the frozen cards
                            if (accumulated != "[]") {
                                _portStatsJSON.emit(accumulated)
                            }
                        }
                    } catch (e: Exception) {
                        logError("Error fetching listener stats: ${e.message}")
                    }
                    kotlinx.coroutines.delay(1000) // Ports screen always refreshes every 1 second
                }
                if (_isRunning.value) {
                    logInfo("Port stats updater stopped")
                }
            }
        }
    }

    /**
     * Rewrites a raw GetListenersJSON payload into session-cumulative totals.
     * While a node instance is up its counters are monotonically increasing,
     * so the delta against the last raw snapshot is added to the running
     * total; a counter that drops below its snapshot means the node was
     * restarted (Power Save wake), and the new baseline counts from that
     * point on top of the totals accumulated before the restart.
     */
    private fun accumulatePortStats(json: String): String {
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.optString("Key", "")
                if (key.isEmpty()) continue
                val raw = ListenerCounters(
                    totalConns = obj.optLong("TotalConns", 0),
                    rxBytes = obj.optLong("RXBytes", 0),
                    txBytes = obj.optLong("TXBytes", 0)
                )
                val prevRaw = rawPortCounters[key]
                val delta = if (prevRaw != null &&
                    raw.totalConns >= prevRaw.totalConns &&
                    raw.rxBytes >= prevRaw.rxBytes &&
                    raw.txBytes >= prevRaw.txBytes
                ) {
                    ListenerCounters(
                        totalConns = raw.totalConns - prevRaw.totalConns,
                        rxBytes = raw.rxBytes - prevRaw.rxBytes,
                        txBytes = raw.txBytes - prevRaw.txBytes
                    )
                } else {
                    raw
                }
                rawPortCounters[key] = raw
                val prevCum = cumulativePortCounters[key] ?: ListenerCounters(0, 0, 0)
                val cum = ListenerCounters(
                    totalConns = prevCum.totalConns + delta.totalConns,
                    rxBytes = prevCum.rxBytes + delta.rxBytes,
                    txBytes = prevCum.txBytes + delta.txBytes
                )
                cumulativePortCounters[key] = cum
                // ActiveConns is a gauge, not a counter - pass through as-is
                obj.put("TotalConns", cum.totalConns)
                obj.put("RXBytes", cum.rxBytes)
                obj.put("TXBytes", cum.txBytes)
            }
            arr.toString()
        } catch (e: Exception) {
            logError("Error accumulating port stats: ${e.message}")
            json
        }
    }

    private fun clearSessionPortCounters() {
        rawPortCounters.clear()
        cumulativePortCounters.clear()
    }

    /**
     * Sums active connections across the SOCKS proxy and forward-mapping
     * listeners only (ignores exposed/"remote-*" listeners), from a raw
     * GetListenersJSON payload.
     */
    private fun sumActiveTransitConnections(json: String): Long {
        var sum = 0L
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val kind = obj.optString("Kind", "")
                if (kind == "remote-tcp" || kind == "remote-udp") continue
                sum += obj.optLong("ActiveConns", 0)
            }
        } catch (e: Exception) {
            logError("Power Save: error parsing listener stats: ${e.message}")
        }
        return sum
    }

    /**
     * Starts or stops the Power Save idle monitor to match current eligibility
     * (running + enabled + no active exposed ports). Safe to call any time the
     * live config changes.
     */
    private fun syncPowerSaveMonitor(config: YggstackConfig) {
        val eligible = _isRunning.value && config.powerSaveEnabled && !config.hasActiveExposedPorts()
        if (eligible) {
            if (idlePowerSaveMonitorJob?.isActive != true) {
                startIdlePowerSaveMonitor()
            }
        } else {
            stopIdlePowerSaveMonitor()
        }
    }

    private fun stopIdlePowerSaveMonitor() {
        idlePowerSaveMonitorJob?.cancel()
        idlePowerSaveMonitorJob = null
        _idleCountdownSeconds.value = null
    }

    /**
     * Power Save's own dedicated poll loop (§4.2/§4.7 of the design doc):
     * independent from the Ports screen's portStatsJob. Fixed at a 1s cadence -
     * there's nothing to poll once the node is idle, so a configurable interval
     * only added complexity without a real benefit.
     */
    private fun startIdlePowerSaveMonitor() {
        idlePowerSaveMonitorJob?.cancel()
        idlePowerSaveMonitorJob = serviceScope.launch {
            var remainingSeconds = (lastConfig?.powerSaveIdleTimeoutSeconds ?: 15).toLong()
            _idleCountdownSeconds.value = remainingSeconds
            while (_isRunning.value) {
                val cfg = lastConfig
                if (cfg == null || !cfg.powerSaveEnabled || cfg.hasActiveExposedPorts()) {
                    _idleCountdownSeconds.value = null
                    break
                }
                val pollSeconds = 1
                kotlinx.coroutines.delay(pollSeconds * 1000L)
                if (!_isRunning.value) break

                val activeConnections = try {
                    // Reuse the port stats poller's fresh raw payload while it
                    // is running (Ports tab open) instead of making a second
                    // identical JNI call every second; ActiveConns is a gauge
                    // that accumulatePortStats passes through untouched
                    val json = if (_portStatsJSON.subscriptionCount.value > 0) {
                        lastRawListenersJSON ?: yggstack?.getListenersJSON()
                    } else {
                        yggstack?.getListenersJSON()
                    }
                    json?.let { sumActiveTransitConnections(it) } ?: 0L
                } catch (e: Exception) {
                    logError("Power Save: error polling listener stats: ${e.message}")
                    0L
                }

                if (activeConnections > 0) {
                    remainingSeconds = cfg.powerSaveIdleTimeoutSeconds.toLong()
                    _idleCountdownSeconds.value = remainingSeconds
                } else {
                    remainingSeconds -= pollSeconds
                    if (remainingSeconds <= 0) {
                        _idleCountdownSeconds.value = 0
                        triggerIdlePowerDown()
                        break
                    }
                    _idleCountdownSeconds.value = remainingSeconds
                }
            }
        }
    }

    private fun triggerIdlePowerDown() {
        val cfg = lastConfig ?: return
        logInfo("Power Save: no active connections for ${cfg.powerSaveIdleTimeoutSeconds}s - powering down node")
        // Placeholder listeners are started inside stopYggstack(), after the real
        // Yggstack listeners have actually released their ports - starting them here
        // would race the async stop and lose the bind (port left unreachable).
        stopYggstack(enterPowerSaveIdle = true)
    }

    /**
     * Wakes the node from Power Save idle: tears down placeholder listeners
     * and restarts Yggstack with the last known config. Safe to call multiple
     * times concurrently (e.g. several placeholders firing at once) - only
     * the first call proceeds.
     */
    fun wakeNow(reason: String = "manual") {
        if (!_isPowerSaveIdle.value) return
        synchronized(wakeTriggerLock) {
            if (wakeInProgress) return
            wakeInProgress = true
        }
        logInfo("Power Save: waking node ($reason)")
        stopPlaceholderListeners()
        val cfg = lastConfig
        if (cfg != null) {
            startYggstack(cfg)
        } else {
            logWarn("Power Save: cannot wake, no saved config")
            wakeInProgress = false
        }
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val trimmed = value.trim()
        val idx = trimmed.lastIndexOf(':')
        if (idx <= 0 || idx == trimmed.length - 1) return null
        val host = trimmed.substring(0, idx).removePrefix("[").removeSuffix("]")
        val port = trimmed.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun startPlaceholderListeners(config: YggstackConfig) {
        stopPlaceholderListeners()
        wakeInProgress = false
        if (config.forwardEnabled) {
            config.forwardMappings.filter { it.enabled }.forEach { mapping ->
                val listener = PlaceholderListener(mapping.protocol, mapping.localIp, mapping.localPort)
                placeholderListeners.add(listener)
                listener.start { wakeNow("${mapping.protocol.name.lowercase()} forward ${mapping.localIp}:${mapping.localPort}") }
            }
        }
        if (config.proxyEnabled && config.socksProxy.isNotBlank()) {
            parseHostPort(config.socksProxy)?.let { (host, port) ->
                val listener = PlaceholderListener(Protocol.TCP, host, port)
                placeholderListeners.add(listener)
                listener.start { wakeNow("socks $host:$port") }
            }
        }
    }

    private fun stopPlaceholderListeners() {
        placeholderListeners.forEach { it.stop() }
        placeholderListeners.clear()
    }

    /**
     * Starts relaying a connection held from a placeholder wake trigger into
     * the node's real listener for the same port. Runs on the service scope
     * (not the placeholder's own job, which wakeNow cancels).
     */
    private fun beginSplice(client: java.net.Socket, host: String, port: Int) {
        heldSpliceSockets.add(client)
        val job = serviceScope.launch(Dispatchers.IO) {
            spliceHeldConnection(client, host, port)
        }
        heldSpliceJobs.add(job)
        job.invokeOnCompletion { heldSpliceJobs.remove(job) }
    }

    /**
     * Holds a wake-triggering TCP connection open while the node starts, then
     * splices it into the real listener once that listener is up. The client
     * never sees a dropped connection: during the hold its early bytes are
     * absorbed by the kernel socket buffer with natural TCP backpressure, and
     * after the splice it talks to the real listener through a loopback relay.
     */
    private suspend fun spliceHeldConnection(client: java.net.Socket, host: String, port: Int) {
        try {
            client.tcpNoDelay = true

            // Wait for the node to finish starting. _isRunning flips true only
            // after start() returns, and the real listeners are bound even
            // before that (mappings are registered in setupPortMappings, prior
            // to start()). Watching the state flow instead of polling
            // getListenersJSON keeps this loop off the JNI boundary: every
            // crossing multiplies exposure to a known arm64 cgo bug
            // (golang/go#46893, "bulkBarrierPreWrite: unaligned arguments")
            // that crashes the process probabilistically per call.
            var ready = false
            val deadline = System.currentTimeMillis() + SPLICE_HOLD_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (_isRunning.value) {
                    ready = true
                    break
                }
                kotlinx.coroutines.delay(SPLICE_READY_POLL_MS)
            }
            if (!ready) {
                logWarn("Power Save: node listener on $host:$port not ready in time - releasing held connection")
                return
            }

            val upstream = java.net.Socket()
            try {
                upstream.tcpNoDelay = true
                upstream.connect(
                    java.net.InetSocketAddress(host, port),
                    SPLICE_UPSTREAM_CONNECT_TIMEOUT_MS.toInt()
                )
                logInfo("Power Save: splicing held connection into $host:$port")
                pumpBothDirections(client, upstream)
            } finally {
                try { upstream.close() } catch (_: Exception) {}
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarn("Power Save: held connection to $host:$port ended: ${e.message}")
        } finally {
            heldSpliceSockets.remove(client)
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Bidirectional byte pump with half-close propagation: EOF on one side
     * shuts down the peer's output; both sockets close once both directions
     * finish (or on the first error, which force-closes and unblocks the
     * other direction).
     */
    private suspend fun pumpBothDirections(a: java.net.Socket, b: java.net.Socket) {
        kotlinx.coroutines.coroutineScope {
            val finished = java.util.concurrent.atomic.AtomicInteger(0)
            launch(Dispatchers.IO) { pump(a, b, finished) }
            launch(Dispatchers.IO) { pump(b, a, finished) }
        }
    }

    private suspend fun pump(
        from: java.net.Socket,
        to: java.net.Socket,
        finished: java.util.concurrent.atomic.AtomicInteger
    ) {
        val buffer = ByteArray(8192)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    output.write(buffer, 0, n)
                    output.flush()
                }
            }
            try { to.shutdownOutput() } catch (_: Exception) {}
        } catch (_: Exception) {
            // Abort: force both directions down so the sibling pump unblocks
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        } finally {
            if (finished.incrementAndGet() >= 2) {
                try { from.close() } catch (_: Exception) {}
                try { to.close() } catch (_: Exception) {}
            }
        }
    }

    /** Closes every connection held by the splice proxy (full stop/teardown). */
    private fun abortSplices() {
        if (heldSpliceJobs.isNotEmpty() || heldSpliceSockets.isNotEmpty()) {
            logInfo("Power Save: aborting ${heldSpliceSockets.size} held connection(s)")
        }
        heldSpliceJobs.forEach { it.cancel() }
        heldSpliceJobs.clear()
        heldSpliceSockets.forEach { try { it.close() } catch (_: Exception) {} }
        heldSpliceSockets.clear()
    }

    /**
     * Binds a single local address/port while the node is powered down, so an
     * incoming connection (or first UDP packet) can wake the node back up.
     * TCP callers are held and relayed into the real listener once the node is
     * up (beginSplice); UDP triggering packets are dropped - UDP clients
     * retransmit by design.
     */
    private inner class PlaceholderListener(
        private val protocol: Protocol,
        private val host: String,
        private val port: Int
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        private var datagramSocket: java.net.DatagramSocket? = null
        private var job: kotlinx.coroutines.Job? = null

        fun start(onTriggered: () -> Unit) {
            job = serviceScope.launch(Dispatchers.IO) {
                try {
                    when (protocol) {
                        Protocol.TCP -> {
                            val socket = java.net.ServerSocket()
                            socket.reuseAddress = true
                            if (!bindWithRetry("TCP") { socket.bind(java.net.InetSocketAddress(host, port)) }) {
                                return@launch
                            }
                            serverSocket = socket
                            logInfo("Power Save: placeholder listening on TCP $host:$port")
                            val client = socket.accept()
                            // Release the listening port so the waking node can
                            // bind it, but KEEP the client connection and relay
                            // it into the real listener once the node is up
                            try { socket.close() } catch (_: Exception) {}
                            serverSocket = null
                            beginSplice(client, host, port)
                            onTriggered()
                        }
                        Protocol.UDP -> {
                            val socket = java.net.DatagramSocket(null)
                            socket.reuseAddress = true
                            if (!bindWithRetry("UDP") { socket.bind(java.net.InetSocketAddress(host, port)) }) {
                                return@launch
                            }
                            datagramSocket = socket
                            logInfo("Power Save: placeholder listening on UDP $host:$port")
                            val buffer = ByteArray(1)
                            val packet = java.net.DatagramPacket(buffer, buffer.size)
                            socket.receive(packet) // blocks; packet is intentionally dropped
                            onTriggered()
                        }
                    }
                } catch (e: java.net.SocketException) {
                    // Expected when stop() closes the socket to cancel listening
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // stop() cancelled the job (e.g. mid bind-retry)
                    throw e
                } catch (e: Exception) {
                    logError("Power Save: placeholder listener error on $host:$port: ${e.message}")
                }
            }
        }

        /**
         * Binds this listener's socket, retrying while the real listener of a
         * powering-down node still holds the port (BindException). Any other
         * SocketException (e.g. stop() closed the socket mid-retry) aborts via
         * the outer catch. Returns false if the port never became available.
         */
        private suspend fun bindWithRetry(proto: String, bind: () -> Unit): Boolean {
            var attempt = 1
            while (true) {
                try {
                    bind()
                    return true
                } catch (e: java.net.BindException) {
                    if (attempt == 1) {
                        logWarn("Power Save: $proto $host:$port still in use - waiting for the node to release it")
                    }
                    if (attempt >= PLACEHOLDER_BIND_ATTEMPTS) {
                        logError(
                            "Power Save: could not bind $proto $host:$port after $attempt attempts - " +
                                "wake trigger unavailable for this port, use Wake Now"
                        )
                        return false
                    }
                    attempt++
                    kotlinx.coroutines.delay(PLACEHOLDER_BIND_RETRY_DELAY_MS)
                }
            }
        }

        fun stop() {
            job?.cancel()
            try { serverSocket?.close() } catch (_: Exception) {}
            try { datagramSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            datagramSocket = null
        }
    }

    /**
     * Start periodic peer cache updater
     */
    private fun startPeerCacheUpdater() {
        peerCacheUpdateJob?.cancel()
        
        peerCacheUpdateJob = serviceScope.launch {
            logInfo("Peer cache updater started")
            kotlinx.coroutines.delay(10000) // Initial delay - let peers stabilize
            
            while (_isRunning.value) {
                try {
                    updatePeerCache()
                } catch (e: Exception) {
                    logError("Error updating peer cache: ${e.message}")
                }
                kotlinx.coroutines.delay(PEER_CACHE_UPDATE_INTERVAL_MS)
            }
            logInfo("Peer cache updater stopped")
        }
    }

    /**
     * Stop peer cache updater
     */
    private fun stopPeerCacheUpdater() {
        peerCacheUpdateJob?.cancel()
        peerCacheUpdateJob = null
    }

    /**
     * Update peer cache with currently connected peers
     */
    private suspend fun updatePeerCache() {
        val peersJson = yggstack?.getPeersJSON() ?: return
        val currentConfig = lastConfig ?: return
        
        try {
            val peers = JSONArray(peersJson)
            val discoveredPeers = mutableListOf<CachedPeer>()
            
            // Find all connected non-static outbound peers (multicast discoveries we connected to)
            for (i in 0 until peers.length()) {
                val peer = peers.getJSONObject(i)
                val uri = peer.optString("remote", "")
                val isUp = peer.optBoolean("Up", false)
                val isInbound = peer.optBoolean("Inbound", false)
                
                if (uri.isNotEmpty() && isUp && !isInbound) {
                    // Check if this is a static peer (user-configured) or disabled
                    val isStatic = currentConfig.peers.contains(uri)
                    val isDisabled = currentConfig.disabledPeers.contains(uri)
                    
                    // Only cache non-static, non-disabled peers
                    if (!isStatic && !isDisabled) {
                        // This is a dynamically discovered outbound peer (multicast listen)
                        discoveredPeers.add(CachedPeer(
                            uri = uri,
                            discoverySource = "multicast",
                            lastSeen = System.currentTimeMillis(),
                            successCount = 1
                        ))
                        logDebug("Discovered active multicast peer: $uri")
                    }
                }
            }
            
            if (discoveredPeers.isNotEmpty()) {
                // Merge with existing cache
                val updatedCache = mergePeerCache(currentConfig.cachedPeers, discoveredPeers)
                
                // Save updated config with new cache
                val updatedConfig = currentConfig.copy(cachedPeers = updatedCache)
                lastConfig = updatedConfig
                
                // Persist to preferences
                val repository = ConfigRepository(applicationContext)
                repository.saveConfig(updatedConfig)
                
                logInfo("Peer cache updated: ${updatedCache.size} cached peer(s)")
            }
        } catch (e: Exception) {
            logError("Error parsing peers for cache update: ${e.message}")
        }
    }

    /**
     * Merge new discovered peers with existing cache
     */
    private fun mergePeerCache(
        existingCache: List<CachedPeer>,
        newPeers: List<CachedPeer>
    ): List<CachedPeer> {
        val cacheMap = existingCache.associateBy { it.uri }.toMutableMap()
        val now = System.currentTimeMillis()
        
        // Update or add new peers
        newPeers.forEach { newPeer ->
            val existing = cacheMap[newPeer.uri]
            if (existing != null) {
                // Update existing peer - increment success count
                cacheMap[newPeer.uri] = existing.copy(
                    lastSeen = now,
                    successCount = existing.successCount + 1
                )
            } else {
                // Add new peer
                cacheMap[newPeer.uri] = newPeer
            }
        }
        
        // Remove stale peers (not seen in PEER_CACHE_STALE_TIME_MS)
        val staleCutoff = now - PEER_CACHE_STALE_TIME_MS
        val validPeers = cacheMap.values.filter { it.lastSeen > staleCutoff }
        
        // Sort by success count (most successful first), then by last seen (most recent first)
        val sortedPeers = validPeers.sortedWith(
            compareByDescending<CachedPeer> { it.successCount - it.failureCount }
                .thenByDescending { it.lastSeen }
        )
        
        // Limit to max cache size
        val limitedPeers = sortedPeers.take(PEER_CACHE_MAX_SIZE)
        
        logDebug("Peer cache merge: ${existingCache.size} existing, ${newPeers.size} new, ${validPeers.size} valid, ${limitedPeers.size} after limit")
        
        return limitedPeers
    }

    /**
     * Clean up peer cache - remove stale and failed peers
     */
    private suspend fun cleanupPeerCache() {
        val currentConfig = lastConfig ?: return
        val now = System.currentTimeMillis()
        val staleCutoff = now - PEER_CACHE_STALE_TIME_MS
        
        // Remove stale peers and those with more failures than successes
        val cleanedCache = currentConfig.cachedPeers.filter { peer ->
            peer.lastSeen > staleCutoff && peer.successCount >= peer.failureCount
        }.take(PEER_CACHE_MAX_SIZE)
        
        if (cleanedCache.size != currentConfig.cachedPeers.size) {
            val removed = currentConfig.cachedPeers.size - cleanedCache.size
            logInfo("Cleaned peer cache: removed $removed stale/failed peer(s), ${cleanedCache.size} remaining")
            
            val updatedConfig = currentConfig.copy(cachedPeers = cleanedCache)
            lastConfig = updatedConfig
            
            val repository = ConfigRepository(applicationContext)
            repository.saveConfig(updatedConfig)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Yggstack Service",
                NotificationManager.IMPORTANCE_LOW  // LOW = no sound, no vibration, no heads-up
            ).apply {
                description = "Yggstack background service notification"
                setShowBadge(true)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)  // Explicitly disable sound
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String, peerCount: Int, totalPeerCount: Int, showStopButton: Boolean = true): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        val contentText = buildString {
            append(status)
            if (_yggdrasilIp.value != null) {
                append("\n${_yggdrasilIp.value}")
            }
            if (totalPeerCount > 0) {
                append("\nPeers: $peerCount/$totalPeerCount")
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Yggstack")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setOngoing(showStopButton)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)  // Prevent sound/vibration on updates
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (showStopButton) {
            val stopIntent = Intent(this, YggstackService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                0,
                stopIntent,
                pendingIntentFlags
            )
            builder.addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopPendingIntent
            )
        }

        return builder.build()
    }

    /**
     * Forces the next updateNotification to post. Called whenever the
     * notification is replaced through another path (startForeground,
     * idle Power Save) so the dedupe cache can't suppress it.
     */
    private fun invalidateNotificationDedupe() {
        lastNotificationStatus = null
        lastNotificationPeerCount = -1
        lastNotificationTotalPeerCount = -1
        lastNotificationYggdrasilIp = null
    }

    private fun updateNotification(status: String, peerCount: Int, totalPeerCount: Int) {
        // The peer poller calls this every second; the notification content is
        // fully determined by these four values, so skip identical re-posts
        // instead of waking SystemUI to redraw the same notification
        val ip = _yggdrasilIp.value
        if (status == lastNotificationStatus &&
            peerCount == lastNotificationPeerCount &&
            totalPeerCount == lastNotificationTotalPeerCount &&
            ip == lastNotificationYggdrasilIp
        ) {
            return
        }
        lastNotificationStatus = status
        lastNotificationPeerCount = peerCount
        lastNotificationTotalPeerCount = totalPeerCount
        lastNotificationYggdrasilIp = ip
        val notification = createNotification(status, peerCount, totalPeerCount)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Notification shown while the node is powered down in Power Save idle
     * mode: swaps the small icon and offers "Wake Now" alongside "Stop".
     */
    private fun createIdlePowerSaveNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Yggstack")
            .setContentText(getString(R.string.power_save_notification_text))
            .setSmallIcon(R.drawable.ic_power_save_idle)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val wakeIntent = Intent(this, YggstackService::class.java).apply {
            action = ACTION_WAKE_NOW
        }
        val wakePendingIntent = PendingIntent.getService(this, 1, wakeIntent, pendingIntentFlags)
        builder.addAction(R.drawable.ic_power_save_idle, getString(R.string.power_save_wake_now), wakePendingIntent)

        val stopIntent = Intent(this, YggstackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags)
        builder.addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)

        return builder.build()
    }

    private fun updateIdlePowerSaveNotification() {
        invalidateNotificationDedupe()
        val notification = createIdlePowerSaveNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "YggstackService::WakeLock"
        ).apply {
            acquire() // Acquire indefinitely while service is running
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("YggstackService::MulticastLock")
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
                logInfo("MulticastLock acquired")
            }
        } catch (e: Exception) {
            logError("Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    logInfo("MulticastLock released")
                }
            }
            multicastLock = null
        } catch (e: Exception) {
            logError("Failed to release MulticastLock: ${e.message}")
        }
    }

    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                // Use high-performance mode if multicast is enabled (beacon or listen)
                // to prevent WiFi power-save from dropping multicast packets
                val lockMode = if (lastConfig?.multicastBeacon == true || lastConfig?.multicastListen == true) {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    WifiManager.WIFI_MODE_FULL
                }
                
                wifiLock = wifiManager.createWifiLock(
                    lockMode,
                    "YggstackService::WifiLock"
                )
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                val mode = if (lastConfig?.multicastBeacon == true || lastConfig?.multicastListen == true) {
                    "high-performance mode for multicast"
                } else {
                    "standard mode"
                }
                logInfo("WiFi lock acquired ($mode) - preventing WiFi sleep")
            }
        } catch (e: Exception) {
            logError("Failed to acquire WiFi lock: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    logInfo("WiFi lock released")
                }
            }
            wifiLock = null
        } catch (e: Exception) {
            logError("Failed to release WiFi lock: ${e.message}")
        }
    }

    private fun checkNetworkType(): Boolean {
        try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            logError("Failed to check network type: ${e.message}")
            return false
        }
    }

    private fun handleMulticastForNetwork(isWifi: Boolean) {
        serviceScope.launch {
            try {
                if (!_isRunning.value || (lastConfig?.multicastBeacon != true && lastConfig?.multicastListen != true)) {
                    return@launch
                }

                if (isWifi && !isOnWifi) {
                    // Switched to WiFi - enable multicast
                    logInfo("Switched to WiFi - enabling multicast discovery")
                    isOnWifi = true
                    acquireWifiLock()
                    acquireMulticastLock()
                    // Trigger peer retry to pick up multicast peers
                    logInfo("Restarting multicast discovery...")
                    retryPeersNow()
                } else if (!isWifi && isOnWifi) {
                    // Switched to Cellular - disable multicast
                    logInfo("Switched to Cellular - disabling multicast discovery")
                    isOnWifi = false
                    releaseWifiLock()
                    releaseMulticastLock()
                    // Note: Multicast will be automatically stopped as it requires WiFi
                    // The Go layer should handle this gracefully
                }
            } catch (e: Exception) {
                logError("Error handling multicast for network change: ${e.message}")
            }
        }
    }

    private fun getNetworkTypeName(network: Network?): String {
        if (network == null) return "None"
        
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "Unknown"
        
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }
    
    private fun getNetworkType(network: Network?): NetworkType {
        if (network == null) return NetworkType.NONE
        
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
        }
    }
    
    private fun registerNetworkCallback() {
        try {
            // Initialize network state
            isOnWifi = checkNetworkType()
            isInitialNetworkCallback = true // Mark first callback as initial
            val initialNetwork = connectivityManager.activeNetwork
            currentNetworkType = getNetworkType(initialNetwork)
            hasNoNetwork = (initialNetwork == null)
            
            // Initialize network type map with current network
            if (initialNetwork != null) {
                networkTypeMap[initialNetwork.networkHandle] = currentNetworkType
            }
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val newNetworkType = getNetworkType(network)
                    val newNetworkTypeName = getNetworkTypeName(network)
                    val previousType = currentNetworkType
                    val previousTypeName = when (previousType) {
                        NetworkType.WIFI -> "WiFi"
                        NetworkType.CELLULAR -> "Cellular"
                        NetworkType.ETHERNET -> "Ethernet"
                        NetworkType.VPN -> "VPN"
                        NetworkType.NONE -> "None"
                        NetworkType.OTHER -> "Other"
                    }
                    
                    // Store network type in map for later lookup in onLost
                    networkTypeMap[network.networkHandle] = newNetworkType
                    
                    logInfo("Network available: ${network} (type: $newNetworkTypeName, previous: $previousTypeName)")
                    
                    // Skip initial callback (fired immediately upon registration)
                    if (isInitialNetworkCallback) {
                        isInitialNetworkCallback = false
                        logInfo("Initial network callback - skipping peer retry")
                        currentNetworkType = newNetworkType
                        hasNoNetwork = false
                        return
                    }
                    
                    // Handle different scenarios based on network transition
                    when {
                        // Scenario 3: Network restored from outage
                        hasNoNetwork && _isRunning.value -> {
                            logInfo("Scenario: Network restored from outage ($previousTypeName → $newNetworkTypeName)")
                            logInfo("Triggering immediate reconnection after network outage")
                            currentNetworkType = newNetworkType
                            hasNoNetwork = false
                            scheduleNetworkRetry("Network restored", immediate = true)
                        }
                        
                        // Scenario 1: WiFi → Cellular (immediate retry after stabilization)
                        previousType == NetworkType.WIFI && newNetworkType == NetworkType.CELLULAR && _isRunning.value -> {
                            logInfo("Scenario: WiFi → Cellular transition")
                            logInfo("Scheduling immediate retry after cellular stabilization")
                            currentNetworkType = newNetworkType
                            hasNoNetwork = false
                            scheduleNetworkRetry("WiFi to Cellular switch", immediate = false)
                        }
                        
                        // Scenario 2: Cellular → WiFi (wait for onLost)
                        previousType == NetworkType.CELLULAR && newNetworkType == NetworkType.WIFI -> {
                            logInfo("Scenario: Cellular → WiFi transition")
                            logInfo("WiFi available - waiting for Cellular lost event before retry")
                            currentNetworkType = newNetworkType
                            hasNoNetwork = false
                            // Don't retry here - wait for onLost(Cellular)
                        }
                        
                        // Other network type changes
                        previousType != newNetworkType -> {
                            logInfo("Network type transition: $previousTypeName → $newNetworkTypeName")
                            currentNetworkType = newNetworkType
                            hasNoNetwork = false
                        }
                        
                        else -> {
                            logInfo("Network available but type unchanged: $newNetworkTypeName")
                            hasNoNetwork = false
                        }
                    }
                }
                
                override fun onLost(network: Network) {
                    // Look up the lost network type from our tracking map
                    // Don't query the network object - it may already be disconnected
                    val lostNetworkType = networkTypeMap.remove(network.networkHandle) ?: NetworkType.NONE
                    val lostNetworkTypeName = when (lostNetworkType) {
                        NetworkType.WIFI -> "WiFi"
                        NetworkType.CELLULAR -> "Cellular"
                        NetworkType.ETHERNET -> "Ethernet"
                        NetworkType.VPN -> "VPN"
                        NetworkType.NONE -> "None"
                        NetworkType.OTHER -> "Other"
                    }
                    
                    val activeNetwork = connectivityManager.activeNetwork
                    val activeNetworkType = getNetworkType(activeNetwork)
                    val activeNetworkTypeName = getNetworkTypeName(activeNetwork)
                    
                    logInfo("Network lost: ${network} (type: $lostNetworkTypeName, active: $activeNetworkTypeName)")
                    
                    // Cancel any pending retry jobs - network state is changing
                    networkRetryJob?.cancel()
                    
                    when {
                        // Scenario 2 completion: Lost Cellular while WiFi is active
                        lostNetworkType == NetworkType.CELLULAR && 
                        activeNetworkType == NetworkType.WIFI && 
                        _isRunning.value -> {
                            logInfo("Scenario: Cellular lost, WiFi active - triggering immediate reconnection")
                            hasNoNetwork = false
                            currentNetworkType = activeNetworkType  // Update to WiFi
                            scheduleNetworkRetry("Cellular to WiFi completion", immediate = true)
                        }
                        
                        // Lost WiFi, check if alternative exists
                        lostNetworkType == NetworkType.WIFI && activeNetwork != null -> {
                            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                                logInfo("Lost WiFi but alternative network available ($activeNetworkTypeName) - onAvailable will handle retry")
                                hasNoNetwork = false
                                currentNetworkType = activeNetworkType
                            } else {
                                logInfo("Lost WiFi, alternative network $activeNetworkTypeName has no internet capability")
                                hasNoNetwork = true
                                currentNetworkType = NetworkType.NONE
                            }
                        }
                        
                        // No alternative network available
                        activeNetwork == null -> {
                            logInfo("No alternative network available after losing $lostNetworkTypeName")
                            hasNoNetwork = true
                            currentNetworkType = NetworkType.NONE
                        }
                        
                        // Other scenarios - alternative network exists
                        else -> {
                            logInfo("Lost $lostNetworkTypeName but alternative network available ($activeNetworkTypeName)")
                            hasNoNetwork = false
                            currentNetworkType = activeNetworkType
                        }
                    }
                }
                
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    
                    // Prioritize WiFi when both are available during transitions
                    val transportType = when {
                        isWifi -> "WiFi"
                        isCellular -> "Cellular"
                        else -> "Unknown"
                    }
                    
                    // Debounce: prevent flip-flop during network transitions
                    // Only process if network type changed AND enough time passed since last change
                    val now = System.currentTimeMillis()
                    val timeSinceLastChange = now - lastNetworkChangeTime
                    
                    if (lastNetworkType != transportType && timeSinceLastChange > NETWORK_CHANGE_DEBOUNCE_MS) {
                        if (lastNetworkType != null) {
                            logInfo("Network switched: $lastNetworkType -> $transportType")
                            // Handle multicast based on network type
                            handleMulticastForNetwork(isWifi)
                        } else {
                            logInfo("Initial network: $transportType")
                        }
                        lastNetworkType = transportType
                        lastNetworkChangeTime = now
                    }
                }
            }
            
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            logInfo("Network monitoring registered")
        } catch (e: Exception) {
            logError("Failed to register network callback: ${e.message}")
        }
    }

    private fun scheduleNetworkRetry(reason: String, immediate: Boolean) {
        val now = System.currentTimeMillis()
        val timeSinceLastRetry = now - lastNetworkRetryTime
        
        // Cancel any pending retry
        networkRetryJob?.cancel()
        
        // Immediate retry for restoration from outage or cellular lost with wifi active
        if (immediate) {
            logInfo("$reason - triggering immediate reconnection")
            retryPeersNow()
            lastNetworkRetryTime = now
            return
        }
        
        // Enforce cooldown for network switches (Scenario 4: flapping protection)
        if (timeSinceLastRetry < FLAP_PROTECTION_COOLDOWN_MS) {
            logDebug("Retry blocked - cooldown active (${timeSinceLastRetry}ms since last, need ${FLAP_PROTECTION_COOLDOWN_MS}ms)")
            logDebug("This prevents reconnection spam during rapid network flapping")
            return
        }
        
        // Schedule retry with stabilization delay
        logDebug("Retry scheduled in ${NETWORK_STABILIZATION_DELAY_MS}ms - allowing network to stabilize")
        networkRetryJob = serviceScope.launch {
            delay(NETWORK_STABILIZATION_DELAY_MS)
            
            // Double-check network is still available before retrying
            val activeNet = connectivityManager.activeNetwork
            if (activeNet != null && _isRunning.value) {
                logInfo("$reason - triggering reconnection after stabilization period")
                retryPeersNow()
                lastNetworkRetryTime = System.currentTimeMillis()
            } else {
                logWarn("Retry cancelled - network no longer available or service stopped")
            }
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                logInfo("Network monitoring unregistered")
            }
            networkCallback = null
            networkTypeMap.clear() // Clear network type tracking
            isInitialNetworkCallback = true // Reset for next registration
        } catch (e: Exception) {
            logError("Failed to unregister network callback: ${e.message}")
        }
    }

    private fun retryPeersNow() {
        serviceScope.launch {
            if (_isRunning.value) {
                try {
                    val now = System.currentTimeMillis()
                    val timeSinceLastRetry = now - lastNetworkChangeTime
                    
                    logInfo("Forcing peer reconnection due to network change...")
                    logDebug("Time since last network event: ${timeSinceLastRetry}ms")
                    
                    yggstack?.retryPeersNow()
                    
                    logInfo("Peer retry triggered successfully")
                    lastNetworkChangeTime = now
                } catch (e: Exception) {
                    logError("Error triggering peer retry: ${e.message}")
                }
            } else {
                logWarn("Retry skipped - service not running")
            }
        }
    }

    /**
     * Save lastConfig to SharedPreferences for persistence across restarts
     */
    private fun saveLastConfigToPreferences(config: YggstackConfig) {
        try {
            val json = JSONObject().apply {
                put("privateKey", config.privateKey)
                put("peers", JSONArray(config.peers))
                put("socksProxy", config.socksProxy)
                put("dnsServer", config.dnsServer)
                put("proxyEnabled", config.proxyEnabled)
                put("multicastBeacon", config.multicastBeacon)
                put("multicastListen", config.multicastListen)
                put("groupPasswordEnabled", config.groupPasswordEnabled)
                put("groupPassword", config.groupPassword)
                put("logLevel", config.logLevel)
                put("maxBackoff", config.maxBackoff)
                put("exposeEnabled", config.exposeEnabled)
                put("forwardEnabled", config.forwardEnabled)
                put("powerSaveEnabled", config.powerSaveEnabled)
                put("powerSaveIdleTimeoutSeconds", config.powerSaveIdleTimeoutSeconds)
                
                // Save expose mappings
                val exposeMappingsArray = JSONArray()
                config.exposeMappings.forEach { mapping ->
                    exposeMappingsArray.put(JSONObject().apply {
                        put("protocol", mapping.protocol.name)
                        put("localPort", mapping.localPort)
                        put("localIp", mapping.localIp)
                        put("yggPort", mapping.yggPort)
                    })
                }
                put("exposeMappings", exposeMappingsArray)
                
                // Save forward mappings
                val forwardMappingsArray = JSONArray()
                config.forwardMappings.forEach { mapping ->
                    forwardMappingsArray.put(JSONObject().apply {
                        put("protocol", mapping.protocol.name)
                        put("localIp", mapping.localIp)
                        put("localPort", mapping.localPort)
                        put("remoteIp", mapping.remoteIp)
                        put("remotePort", mapping.remotePort)
                    })
                }
                put("forwardMappings", forwardMappingsArray)
                
                // Save disabledPeers
                put("disabledPeers", JSONArray(config.disabledPeers))
                
                // Save cachedPeers
                val cachedPeersArray = JSONArray()
                config.cachedPeers.forEach { cached ->
                    cachedPeersArray.put(JSONObject().apply {
                        put("uri", cached.uri)
                        put("discoverySource", cached.discoverySource)
                        put("lastSeen", cached.lastSeen)
                        put("successCount", cached.successCount)
                        put("failureCount", cached.failureCount)
                    })
                }
                put("cachedPeers", cachedPeersArray)
            }
            
            sharedPreferences.edit()
                .putString(PREF_LAST_CONFIG, json.toString())
                .apply()
        } catch (e: Exception) {
            logError("ERROR saving config to SharedPreferences: ${e.message}")
        }
    }
    
    /**
     * Load lastConfig from SharedPreferences on service startup
     */
    private fun loadLastConfigFromPreferences() {
        try {
            val configJson = sharedPreferences.getString(PREF_LAST_CONFIG, null)
            if (configJson != null) {
                val json = JSONObject(configJson)
                
                // Parse expose mappings
                val exposeMappings = mutableListOf<ExposeMapping>()
                val exposeMappingsArray = json.optJSONArray("exposeMappings")
                if (exposeMappingsArray != null) {
                    for (i in 0 until exposeMappingsArray.length()) {
                        val mappingJson = exposeMappingsArray.getJSONObject(i)
                        exposeMappings.add(
                            ExposeMapping(
                                protocol = Protocol.valueOf(mappingJson.getString("protocol")),
                                localPort = mappingJson.getInt("localPort"),
                                localIp = mappingJson.getString("localIp"),
                                yggPort = mappingJson.getInt("yggPort")
                            )
                        )
                    }
                }
                
                // Parse forward mappings
                val forwardMappings = mutableListOf<ForwardMapping>()
                val forwardMappingsArray = json.optJSONArray("forwardMappings")
                if (forwardMappingsArray != null) {
                    for (i in 0 until forwardMappingsArray.length()) {
                        val mappingJson = forwardMappingsArray.getJSONObject(i)
                        forwardMappings.add(
                            ForwardMapping(
                                protocol = Protocol.valueOf(mappingJson.getString("protocol")),
                                localIp = mappingJson.getString("localIp"),
                                localPort = mappingJson.getInt("localPort"),
                                remoteIp = mappingJson.getString("remoteIp"),
                                remotePort = mappingJson.getInt("remotePort")
                            )
                        )
                    }
                }
                
                // Parse peers array
                val peers = mutableListOf<String>()
                val peersArray = json.optJSONArray("peers")
                if (peersArray != null) {
                    for (i in 0 until peersArray.length()) {
                        peers.add(peersArray.getString(i))
                    }
                }
                
                // Parse disabledPeers array
                val disabledPeers = mutableListOf<String>()
                val disabledPeersArray = json.optJSONArray("disabledPeers")
                if (disabledPeersArray != null) {
                    for (i in 0 until disabledPeersArray.length()) {
                        disabledPeers.add(disabledPeersArray.getString(i))
                    }
                }
                
                // Parse cachedPeers array
                val cachedPeers = mutableListOf<CachedPeer>()
                val cachedPeersArray = json.optJSONArray("cachedPeers")
                if (cachedPeersArray != null) {
                    for (i in 0 until cachedPeersArray.length()) {
                        val cached = cachedPeersArray.getJSONObject(i)
                        cachedPeers.add(CachedPeer(
                            uri = cached.getString("uri"),
                            discoverySource = cached.getString("discoverySource"),
                            lastSeen = cached.getLong("lastSeen"),
                            successCount = cached.optInt("successCount", 0),
                            failureCount = cached.optInt("failureCount", 0)
                        ))
                    }
                }
                
                lastConfig = YggstackConfig(
                    privateKey = json.optString("privateKey", ""),
                    peers = peers,
                    socksProxy = json.optString("socksProxy", ""),
                    dnsServer = json.optString("dnsServer", ""),
                    proxyEnabled = json.optBoolean("proxyEnabled", false),
                    multicastBeacon = json.optBoolean("multicastBeacon", true),
                    multicastListen = json.optBoolean("multicastListen", true),
                    groupPasswordEnabled = json.optBoolean("groupPasswordEnabled", false),
                    groupPassword = json.optString("groupPassword", ""),
                    logLevel = json.optString("logLevel", "info"),
                    maxBackoff = json.optInt("maxBackoff", 5),
                    exposeEnabled = json.optBoolean("exposeEnabled", false),
                    forwardEnabled = json.optBoolean("forwardEnabled", false),
                    powerSaveEnabled = json.optBoolean("powerSaveEnabled", false),
                    powerSaveIdleTimeoutSeconds = json.optInt("powerSaveIdleTimeoutSeconds", 15),
                    exposeMappings = exposeMappings,
                    forwardMappings = forwardMappings,
                    disabledPeers = disabledPeers,
                    cachedPeers = cachedPeers
                )
                
                logInfo("Loaded config from SharedPreferences: ${peers.size} peer(s), key present=${lastConfig!!.privateKey.isNotBlank()}")
            } else {
                logInfo("No saved config found in SharedPreferences")
            }
        } catch (e: Exception) {
            logError("ERROR loading config from SharedPreferences: ${e.message}")
            lastConfig = null
        }
    }

    private fun registerScreenStateReceiver() {
        try {
            screenStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            logDebug("Screen off - device screen locked")
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            logDebug("Screen on - device screen unlocked")
                        }
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            
            registerReceiver(screenStateReceiver, filter)
            logInfo("Screen state receiver registered")
        } catch (e: Exception) {
            logError("Failed to register screen state receiver: ${e.message}")
        }
    }

    private fun unregisterScreenStateReceiver() {
        try {
            screenStateReceiver?.let {
                unregisterReceiver(it)
                screenStateReceiver = null
                logInfo("Screen state receiver unregistered")
            }
        } catch (e: Exception) {
            logError("Error unregistering screen state receiver: ${e.message}")
        }
    }

    private fun verifyPermissions() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Check battery optimization
        val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            !powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            false
        }
        
        // Check notification permission
        val notificationsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notificationManager.areNotificationsEnabled()
        } else {
            true // Assume enabled on older versions
        }
        
        // Check MIUI autostart permission
        val miuiAutostart = try {
            xyz.kumaraswamy.autostart.Autostart.getSafeState(this)
        } catch (e: Exception) {
            null // Not MIUI or error checking
        }
        
        // Log permission status
        logInfo("=== Permission Verification ===")
        if (batteryOptimized) {
            logWarn("⚠️ Battery optimization is ENABLED - service may be killed when screen is off")
            logWarn("   Please disable battery optimization for this app in Settings")
        } else {
            logInfo("✓ Battery optimization is disabled - service can run unrestricted")
        }
        
        if (!notificationsEnabled) {
            logWarn("⚠️ Notifications are DISABLED - user won't see service status")
            logWarn("   Please enable notifications for this app in Settings")
        } else {
            logInfo("✓ Notifications are enabled")
        }
        
        if (miuiAutostart != null) {
            if (miuiAutostart) {
                logInfo("✓ MIUI Autostart is ENABLED")
            } else {
                logWarn("⚠️ MIUI Autostart is DISABLED - service may not restart after reboot")
                logWarn("   Please enable autostart for this app in MIUI Security settings")
            }
        }
        logInfo("================================")
    }

    companion object {
        private const val LOG_TAG = "YggstackService"
        const val CHANNEL_ID = "yggstack_service_channel"
        const val NOTIFICATION_ID = 1

        // Whether a service instance is alive in this process (running OR in
        // Power Save idle). Read by the "Keep last state" app-start policy to
        // distinguish "service already up, leave it alone" from "process was
        // killed, restore the last state".
        @Volatile var serviceAlive = false
            private set

        const val ACTION_START = "link.yggdrasil.yggstack.android.action.START"
        const val ACTION_STOP = "link.yggdrasil.yggstack.android.action.STOP"
        const val ACTION_WAKE_NOW = "link.yggdrasil.yggstack.android.action.WAKE_NOW"
        const val EXTRA_CONFIG = "config"
        private const val MAX_LOG_ENTRIES = 500
        private const val MAX_CRASH_RESTART_ATTEMPTS = 3
        private const val PREFS_NAME = "yggstack_service_prefs"
        private const val PREF_LAST_CONFIG = "last_config"
        private const val PREF_WAS_RUNNING = "was_running"
    }
}

