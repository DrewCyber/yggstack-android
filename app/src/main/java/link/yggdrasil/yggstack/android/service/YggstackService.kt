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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import link.yggdrasil.yggstack.android.data.ConfigSerializer
import link.yggdrasil.yggstack.android.engine.EngineFactory
import link.yggdrasil.yggstack.android.engine.NativeEngine
import link.yggdrasil.yggstack.android.engine.NativeLogCallback
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
    @Volatile private var yggstack: NativeEngine? = null
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
    private val lifecycle = lifecycleQueue.session(
        cleanup = {
            try {
                stopNode(enterPowerSaveIdle = false, destroying = true)
            } finally {
                serviceScope.cancel()
            }
        },
        failure = { error -> logError("Lifecycle operation failed: ${error.message}") }
    )
    
    // Screen state monitoring - the receiver is registered only while Power
    // Save's screen events ("Sleep during screen off" / "Wake on screen on")
    // need it, never for the whole service lifetime.
    private var screenStateReceiver: BroadcastReceiver? = null
    @Volatile private var screenOn: Boolean = true
    private val mainHandler = Handler(Looper.getMainLooper())
    
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
     * Sanitize native config text by replacing secrets with masked values
     */
    private fun sanitizeConfigJson(json: String): String =
        yggstack?.sanitizeNativeConfig(json) ?: ""

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
    private suspend fun persistServiceWasRunning(running: Boolean) {
        try {
            ConfigRepository(applicationContext).saveServiceWasRunning(running)
        } catch (e: Exception) {
            logWarn("Could not persist service running state: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        persistentLogger = PersistentLogger(this)
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
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
        serviceAlive = false
        unregisterScreenStateReceiver()
        lifecycle.destroy()
        super.onDestroy()
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
        lifecycle.publish {
            if (!_isSessionActive.value) {
                startForeground(NOTIFICATION_ID, createNotification("Starting...", 0, 0))
                invalidateNotificationDedupe()
            }
        }
        lifecycle.submit(acquire = true) { startNode(config) }
    }

    private suspend fun startNode(config: YggstackConfig, recovering: Boolean = false) {
        if (lifecycle.isDestroyed || _isRunning.value) return
        _isTransitioning.value = true
        try {
                stopPlaceholderListeners()
                // Store config for crash recovery and persistence
                lastConfig = config
                saveLastConfigToPreferences(config)
                logDebug("Config saved to persistent storage")
                if (!recovering) crashRestartAttempts = 0
                
                stopNativeNode()
                if (lifecycle.isDestroyed) return
                logInfo("Starting Yggstack...")
                // Create Yggstack instance
                yggstack = EngineFactory.create()
                
                // Only set log callback if logging is enabled
                if (logsEnabled) {
                    yggstack?.setLogCallback(NativeLogCallback { message ->
                        addLog(message.trim())
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
                yggstack?.loadConfig(configJson)
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

                if (lifecycle.isDestroyed) return
                _yggdrasilIp.value = yggstack?.getAddress()

                logDebug("Setting service running state...")
                val wasIdle = _isPowerSaveIdle.value
                lifecycle.publish {
                    _isRunning.value = true
                    _isSessionActive.value = true
                }
                if (lifecycle.isDestroyed) return
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
                // Keep the screen-event receiver in sync with the live config
                syncScreenStateReceiver()

        } catch (e: Exception) {
            logError("ERROR starting Yggstack: ${e.message}")
            stopNode(enterPowerSaveIdle = false, destroying = lifecycle.isDestroyed)
            lifecycle.publish {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(
                    NOTIFICATION_ID,
                    createNotification("Failed to start - check logs", 0, 0, showStopButton = false)
                )
            }
        } finally {
            wakeInProgress = false
            _isTransitioning.value = false
        }
    }

    fun stopYggstack(enterPowerSaveIdle: Boolean = false) {
        lifecycle.submit { stopNode(enterPowerSaveIdle) }
    }

    private suspend fun stopNode(enterPowerSaveIdle: Boolean, destroying: Boolean = false) {
        if (enterPowerSaveIdle && (!_isRunning.value || !_isSessionActive.value)) return
        _isTransitioning.value = true
        try {
            stopNodeObservers()
            stopPlaceholderListeners()
            if (!enterPowerSaveIdle) abortSplices()
            try {
                stopNativeNode()
            } finally {
                releaseWifiLock()
                releaseMulticastLock()
                releaseWakeLock()
            }
            _isRunning.value = false
            _yggdrasilIp.value = null
            _yggdrasilPublicKey.value = null
            _peerCount.value = 0
            _totalPeerCount.value = 0
            _generatedPrivateKey.value = null
            hasNoNetwork = false

            if (enterPowerSaveIdle && !lifecycle.isDestroyed) {
                lastRawListenersJSON?.let { raw ->
                    val accumulated = accumulatePortStats(raw)
                    if (accumulated != "[]") _portStatsJSON.emit(accumulated)
                }
                val poweredDownAt = System.currentTimeMillis()
                _powerSaveUpMillis.value += (poweredDownAt - _powerSaveStateSince.value).coerceAtLeast(0)
                _powerSaveStateSince.value = poweredDownAt
                lifecycle.publish {
                    _isPowerSaveIdle.value = true
                    _powerSaveIdleSince.value = poweredDownAt
                    updateIdlePowerSaveNotification()
                    lastConfig?.let { startPlaceholderListeners(it) }
                }
                logInfo("Power Save: node powered down, listening for wake triggers")
            } else {
                abortSplices()
                _isPowerSaveIdle.value = false
                _powerSaveIdleSince.value = null
                _isSessionActive.value = false
                // Session over - no screen events to react to anymore
                syncScreenStateReceiver()
                _powerSaveUpMillis.value = 0
                _powerSaveIdleMillis.value = 0
                _powerSaveStateSince.value = 0
                clearSessionPortCounters()
                lastRawListenersJSON = null
                _portStatsJSON.resetReplayCache()
                persistServiceWasRunning(false)
                if (!destroying && !lifecycle.isDestroyed) {
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
            }
        } finally {
            _isTransitioning.value = false
        }
    }

    private fun stopNativeNode() {
        // A failed teardown must retain the instance; a replacement cannot safely bind its ports.
        // A rolled-back failed start already stopped the node; its stop() is a no-op error,
        // so tolerate it rather than crashing the cleanup path.
        yggstack?.let { instance ->
            try {
                instance.stop()
            } catch (e: Exception) {
                logWarn("Native stop returned: ${e.message}")
            }
        }
        yggstack = null
    }

    private suspend fun stopNodeObservers() {
        unregisterNetworkCallback()
        val jobs = listOfNotNull(
            peerDetailsSubscriptionJob, portStatsSubscriptionJob,
            peerDetailsJob, portStatsJob, idlePowerSaveMonitorJob,
            peerCacheUpdateJob, networkRetryJob
        )
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        peerDetailsSubscriptionJob = null
        portStatsSubscriptionJob = null
        peerDetailsJob = null
        portStatsJob = null
        idlePowerSaveMonitorJob = null
        peerCacheUpdateJob = null
        networkRetryJob = null
        _idleCountdownSeconds.value = null
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
        val engine = yggstack ?: throw IllegalStateException("engine not created")
        val generated = if (config.privateKey.isBlank()) engine.generateConfigText() else null
        val result = engine.buildNativeConfig(config, generated)
        if (generated != null) {
            val key = engine.privateKeyOf(result)
            _generatedPrivateKey.value = key
            lastConfig = (lastConfig ?: config).copy(privateKey = key)
            lastConfig?.let { saveLastConfigToPreferences(it) }
        }
        return result
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
                                yggstack?.addLocalTcpMapping(localAddr, remoteAddr)
                                logInfo("✓ Added TCP forward: $localAddr -> $remoteAddr")
                            }
                            link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                                yggstack?.addLocalUdpMapping(localAddr, remoteAddr)
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
                                yggstack?.addRemoteTcpMapping(mapping.yggPort.toLong(), localAddr)
                                logInfo("✓ Exposed TCP port ${mapping.yggPort} -> $localAddr")
                            }
                            link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                                yggstack?.addRemoteUdpMapping(mapping.yggPort.toLong(), localAddr)
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
                        yggstack?.addRemoteTcpMapping(mapping.yggPort.toLong(), localAddr)
                        logInfo("✓ Enabled TCP expose: port ${mapping.yggPort} -> $localAddr")
                    } else {
                        yggstack?.removeRemoteTcpMapping(mapping.yggPort.toLong(), localAddr)
                        logInfo("✓ Disabled TCP expose: port ${mapping.yggPort} -> $localAddr")
                    }
                }
                link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                    if (enable) {
                        yggstack?.addRemoteUdpMapping(mapping.yggPort.toLong(), localAddr)
                        logInfo("✓ Enabled UDP expose: port ${mapping.yggPort} -> $localAddr")
                    } else {
                        yggstack?.removeRemoteUdpMapping(mapping.yggPort.toLong(), localAddr)
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
                        yggstack?.addLocalTcpMapping(localAddr, remoteAddr)
                        logInfo("✓ Enabled TCP forward: $localAddr -> $remoteAddr")
                    } else {
                        yggstack?.removeLocalTcpMapping(localAddr, remoteAddr)
                        logInfo("✓ Disabled TCP forward: $localAddr -> $remoteAddr")
                    }
                }
                link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                    if (enable) {
                        yggstack?.addLocalUdpMapping(localAddr, remoteAddr)
                        logInfo("✓ Enabled UDP forward: $localAddr -> $remoteAddr")
                    } else {
                        yggstack?.removeLocalUdpMapping(localAddr, remoteAddr)
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
                        val address = yggstack?.getAddress()
                        _yggdrasilIp.value = address
                    } catch (e: Exception) {
                        logError("Error fetching Yggdrasil IP: ${e.message}")
                    }
                    
                    try {
                        val publicKey = yggstack?.getPublicKey()
                        _yggdrasilPublicKey.value = publicKey
                    } catch (e: Exception) {
                        logError("Error fetching Yggdrasil public key: ${e.message}")
                    }
                    
                    val peersJson = yggstack?.getPeersJson()
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
                        val failedInstance = yggstack
                        lifecycle.submit {
                            if (yggstack !== failedInstance || !_isRunning.value) return@submit
                            val config = lastConfig
                            stopNodeObservers()
                            stopNativeNode()
                            _isRunning.value = false
                            if (config != null && crashRestartAttempts < MAX_CRASH_RESTART_ATTEMPTS) {
                                crashRestartAttempts++
                                logWarn("Restarting after node failure (attempt $crashRestartAttempts)")
                                startNode(config, recovering = true)
                            } else {
                                stopNode(enterPowerSaveIdle = false)
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
                        val listenersJson = yggstack?.getListenersJson()
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
     * (running + enabled + "Sleep on ports idle" + no active exposed ports).
     * Safe to call any time the live config changes.
     */
    private fun syncPowerSaveMonitor(config: YggstackConfig) {
        val eligible = _isRunning.value && config.powerSaveEnabled &&
            config.powerSaveSleepOnPortsIdle && !config.hasActiveExposedPorts()
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
            var remainingSeconds = (lastConfig?.powerSaveIdleTimeoutSeconds ?: 60).toLong()
            _idleCountdownSeconds.value = remainingSeconds
            while (_isRunning.value) {
                val cfg = lastConfig
                if (cfg == null || !cfg.powerSaveEnabled || !cfg.powerSaveSleepOnPortsIdle || cfg.hasActiveExposedPorts()) {
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
                    // that accumulatePortStats passes through untouched.
                    // Either way the poll becomes the freshest snapshot, so
                    // idle entry can freeze up-to-date counters into the
                    // stats flow even when the Ports tab was never open.
                    val json = if (_portStatsJSON.subscriptionCount.value > 0) {
                        lastRawListenersJSON ?: yggstack?.getListenersJson()?.also { lastRawListenersJSON = it }
                    } else {
                        yggstack?.getListenersJson()?.also { lastRawListenersJSON = it }
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
                        triggerIdlePowerDown("no traffic for ${cfg.powerSaveIdleTimeoutSeconds}s")
                        break
                    }
                    _idleCountdownSeconds.value = remainingSeconds
                }
            }
        }
    }

    private fun triggerIdlePowerDown(reason: String) {
        if (lastConfig == null) return
        logInfo("Power Save: powering down node ($reason)")
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
        if (lifecycle.isDestroyed || !_isPowerSaveIdle.value) return
        synchronized(wakeTriggerLock) {
            if (wakeInProgress) return
            wakeInProgress = true
        }
        logInfo("Power Save: waking node ($reason)")
        lifecycle.submit {
            if (!_isPowerSaveIdle.value || !_isSessionActive.value) {
                wakeInProgress = false
                return@submit
            }
            val cfg = lastConfig
            if (cfg != null) startNode(cfg) else {
                wakeInProgress = false
                logWarn("Power Save: cannot wake, no saved config")
            }
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
        // "Wake on ports active" off: ports stay closed while idle - the node
        // only wakes via screen on or manually.
        if (!config.powerSaveWakeOnPortsActive) return
        // "Sleep during screen off" suspends port-knock wake for as long as
        // the screen stays off, so placeholders must not hold the ports then.
        if (config.powerSaveSleepDuringScreenOff && !screenOn) return
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
            heldSpliceSockets.add(upstream)
            try {
                upstream.tcpNoDelay = true
                upstream.connect(
                    java.net.InetSocketAddress(host, port),
                    SPLICE_UPSTREAM_CONNECT_TIMEOUT_MS.toInt()
                )
                logInfo("Power Save: splicing held connection into $host:$port")
                pumpBothDirections(client, upstream)
            } finally {
                heldSpliceSockets.remove(upstream)
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
        private val socketLock = Any()
        private var stopped = false

        fun start(onTriggered: () -> Unit) {
            job = serviceScope.launch(Dispatchers.IO) {
                try {
                    when (protocol) {
                        Protocol.TCP -> {
                            val socket = java.net.ServerSocket()
                            synchronized(socketLock) {
                                if (stopped) { socket.close(); return@launch }
                                serverSocket = socket
                            }
                            socket.reuseAddress = true
                            if (!bindWithRetry("TCP") { socket.bind(java.net.InetSocketAddress(host, port)) }) {
                                return@launch
                            }
                            logInfo("Power Save: placeholder listening on TCP $host:$port")
                            val client = socket.accept()
                            // Release the listening port so the waking node can
                            // bind it, but KEEP the client connection and relay
                            // it into the real listener once the node is up
                            try { socket.close() } catch (_: Exception) {}
                            synchronized(socketLock) {
                                if (stopped) client.close() else {
                                    beginSplice(client, host, port)
                                    onTriggered()
                                }
                            }
                        }
                        Protocol.UDP -> {
                            val socket = java.net.DatagramSocket(null)
                            synchronized(socketLock) {
                                if (stopped) { socket.close(); return@launch }
                                datagramSocket = socket
                            }
                            socket.reuseAddress = true
                            if (!bindWithRetry("UDP") { socket.bind(java.net.InetSocketAddress(host, port)) }) {
                                return@launch
                            }
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
                } finally {
                    closeSockets()
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

        private fun closeSockets() = synchronized(socketLock) {
            try { serverSocket?.close() } catch (_: Exception) {}
            try { datagramSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            datagramSocket = null
        }

        fun stop() {
            synchronized(socketLock) {
                stopped = true
                closeSockets()
            }
            job?.cancel()
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
        val peersJson = yggstack?.getPeersJson() ?: return
        val currentConfig = lastConfig ?: return
        
        try {
            val peers = JSONArray(peersJson)
            val discoveredPeers = mutableListOf<CachedPeer>()
            
            // Find all connected non-static outbound peers (multicast discoveries we connected to)
            for (i in 0 until peers.length()) {
                val peer = peers.getJSONObject(i)
                val uri = peer.optString("URI", "")
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
                getString(R.string.app_name) + " Service",
                NotificationManager.IMPORTANCE_LOW  // LOW = no sound, no vibration, no heads-up
            ).apply {
                description = getString(R.string.app_name) + " background service notification"
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
            .setContentTitle(getString(R.string.app_name))
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
            .setContentTitle(getString(R.string.app_name))
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
        if (wakeLock?.isHeld == true) return
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
        lifecycle.submit {
            try {
                if (!_isRunning.value || (lastConfig?.multicastBeacon != true && lastConfig?.multicastListen != true)) {
                    return@submit
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
            sharedPreferences.edit()
                .putString(PREF_LAST_CONFIG, ConfigSerializer.encode(config))
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
                lastConfig = ConfigSerializer.decode(configJson)
                logInfo("Loaded saved configuration: ${lastConfig!!.peers.size} peer(s)")
            } else {
                logInfo("No saved config found in SharedPreferences")
            }
        } catch (e: Exception) {
            logError("ERROR loading config from SharedPreferences: ${e.message}")
            lastConfig = null
        }
    }

    /**
     * Keeps the screen-state receiver registered only while an active Power
     * Save session actually needs screen events ("Sleep during screen off" or
     * "Wake on screen on"). Safe to call whenever the session state or live
     * config changes; register/unregister hop to the main thread, where
     * onReceive is dispatched.
     */
    private fun syncScreenStateReceiver() {
        mainHandler.post {
            val cfg = lastConfig
            val needed = _isSessionActive.value && cfg != null && cfg.powerSaveEnabled &&
                (cfg.powerSaveSleepDuringScreenOff || cfg.powerSaveWakeOnScreenOn)
            if (needed) registerScreenStateReceiver() else unregisterScreenStateReceiver()
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        try {
            screenStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                        Intent.ACTION_SCREEN_ON -> handleScreenOn()
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }

            registerReceiver(screenStateReceiver, filter)
            // Seed the state from the display, not from the event stream: the
            // service can (re)start while the screen is already off (system
            // restart), and "Sleep during screen off" must apply right away.
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenOn = powerManager.isInteractive
            logInfo("Screen state receiver registered (screenOn=$screenOn)")
            if (!screenOn) handleScreenOff()
        } catch (e: Exception) {
            logError("Failed to register screen state receiver: ${e.message}")
        }
    }

    /**
     * ACTION_SCREEN_OFF: with "Sleep during screen off" enabled, power the
     * node down immediately; if it is already idle (ports-idle timeout fired
     * while the screen was on), suspend port-knock wake by dropping the
     * placeholder listeners until the screen comes back on.
     */
    private fun handleScreenOff() {
        logDebug("Screen off - device screen locked")
        screenOn = false
        val cfg = lastConfig ?: return
        if (!cfg.powerSaveEnabled || !cfg.powerSaveSleepDuringScreenOff || cfg.hasActiveExposedPorts()) return
        if (_isRunning.value) {
            logInfo("Power Save: screen off - powering down node immediately")
            triggerIdlePowerDown("screen off")
        } else if (_isPowerSaveIdle.value) {
            logInfo("Power Save: screen off - suspending wake on ports until screen on")
            lifecycle.submit { if (_isPowerSaveIdle.value) stopPlaceholderListeners() }
        }
    }

    /**
     * ACTION_SCREEN_ON: with "Wake on screen on" enabled, wake an idle node;
     * otherwise, if "Sleep during screen off" had suspended the placeholder
     * listeners, bring them back so "Wake on ports active" works again.
     */
    private fun handleScreenOn() {
        logDebug("Screen on - device screen unlocked")
        screenOn = true
        val cfg = lastConfig ?: return
        if (!cfg.powerSaveEnabled) return
        if (cfg.powerSaveWakeOnScreenOn && _isPowerSaveIdle.value) {
            wakeNow("screen on")
        } else if (_isPowerSaveIdle.value && cfg.powerSaveSleepDuringScreenOff && cfg.powerSaveWakeOnPortsActive) {
            logInfo("Power Save: screen on - restoring wake-on-ports listeners")
            lifecycle.submit { if (_isPowerSaveIdle.value) startPlaceholderListeners(cfg) }
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
        private val lifecycleQueue = LifecycleQueue()
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

