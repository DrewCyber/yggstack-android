package link.yggdrasil.yggstack.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import link.yggdrasil.yggstack.mobile.LogCallback
import link.yggdrasil.yggstack.mobile.Mobile
import link.yggdrasil.yggstack.mobile.Yggstack
import org.json.JSONArray
import org.json.JSONObject
import android.content.SharedPreferences

/**
 * Foreground service for running Yggstack
 */
class YggstackService : Service() {

    private val binder = YggstackBinder()
    private var yggstack: Yggstack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var persistentLogger: PersistentLogger
    private var peerStatsJob: kotlinx.coroutines.Job? = null
    private lateinit var sharedPreferences: SharedPreferences
    
    // Subscription monitoring for peer stats
    private var peerStatsSubscriptionJob: kotlinx.coroutines.Job? = null
    
    // Operation state management
    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()
    private val operationMutex = kotlinx.coroutines.sync.Mutex()
    
    // Network connectivity monitoring
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager by lazy { 
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager 
    }
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private var lastNetworkType: String? = null
    private var lastNetworkChangeTime: Long = 0
    private val NETWORK_CHANGE_DEBOUNCE_MS = 5000L // 5 seconds to avoid flip-flop during transitions
    private var isOnWifi: Boolean = false
    private var isInitialNetworkCallback: Boolean = true // Skip retry on first callback after registration
    private var hasNoNetwork: Boolean = false // Track if we're in no-network state
    
    // Store last config for automatic restart after crash
    private var lastConfig: YggstackConfig? = null
    private var crashRestartAttempts = 0
    
    // Logs enabled setting and current log level
    private var logsEnabled: Boolean = true
    private var currentLogLevel: String = "error"

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _totalPeerCount = MutableStateFlow(0)
    val totalPeerCount: StateFlow<Int> = _totalPeerCount.asStateFlow()

    private val _peerDetailsJSON = MutableSharedFlow<String>(replay = 1)
    val peerDetailsJSON: SharedFlow<String> = _peerDetailsJSON.asSharedFlow()

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

    override fun onCreate() {
        super.onCreate()
        persistentLogger = PersistentLogger(this)
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        acquireWakeLock()
        
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
        stopYggstack()
        releaseMulticastLock()
        releaseWakeLock()
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
                    config.dnsServer
                } else {
                    ""
                }

                // Clear any existing mappings from previous runs to avoid duplicates
                yggstack?.clearLocalMappings()
                yggstack?.clearRemoteMappings()

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
                _isRunning.value = true
                _peerCount.value = 0
                logInfo("Service state updated: isRunning=true")

                // Register network callback to monitor WiFi/Cellular changes
                registerNetworkCallback()

                logInfo("Yggstack started successfully")
                updateNotification("Connected", 0, 0)

                // Start monitoring for peer details subscriptions (lazy-load)
                startPeerStatsSubscriptionMonitor()

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
                _isRunning.value = false
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _totalPeerCount.value = 0
                
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
                _isTransitioning.value = false
                operationMutex.unlock()
                logInfo("Operation mutex released, transitioning state reset")
            }
        }
    }

    fun stopYggstack() {
        serviceScope.launch {
            // Use mutex to prevent concurrent start/stop operations
            if (!operationMutex.tryLock()) {
                logInfo("Operation already in progress - ignoring stop request")
                return@launch
            }
            
            try {
                // Force cleanup even if _isRunning is false (handles desync state)
                if (!_isRunning.value && yggstack == null) {
                    logInfo("Service already stopped")
                    return@launch
                }
                
                if (!_isRunning.value && yggstack != null) {
                    logWarn("WARNING: State desync detected - forcing cleanup of zombie instance")
                }
                
                _isTransitioning.value = true

                logInfo("Stopping Yggstack...")
                _isRunning.value = false  // Set this first to stop the peer updater
                
                // Cancel peer stats jobs
                peerStatsJob?.cancel()
                peerStatsJob = null
                peerStatsSubscriptionJob?.cancel()
                peerStatsSubscriptionJob = null
                
                // Unregister network callback
                unregisterNetworkCallback()
                
                // Release WiFi lock if held
                releaseWifiLock()
                
                // Release MulticastLock if held
                releaseMulticastLock()
                
                // Stop yggstack with 5-second timeout
                try {
                    kotlinx.coroutines.withTimeout(5000L) {
                        yggstack?.stop()
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    logWarn("WARNING: Yggstack stop timed out after 5 seconds - forcing cleanup")
                }
                yggstack = null
                
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _totalPeerCount.value = 0
                _generatedPrivateKey.value = null
                
                logInfo("Yggstack stopped")
                
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
            } catch (e: Exception) {
                logError("Error stopping Yggstack: ${e.message}")
                // Force cleanup even on error
                yggstack = null
                _isRunning.value = false
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _totalPeerCount.value = 0
                _generatedPrivateKey.value = null
                hasNoNetwork = false
                
                // Unregister network callback on error too
                unregisterNetworkCallback()
                
                // Release MulticastLock on error too
                releaseMulticastLock()
                
                // Cancel notification on error too
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
            } finally {
                logInfo("Cleanup: Releasing stop operation mutex and resetting transitioning state")
                _isTransitioning.value = false
                hasNoNetwork = false  // Reset network state
                operationMutex.unlock()
                logInfo("Stop operation mutex released, transitioning state reset")
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
            
            if (config.peers.isNotEmpty()) {
                logInfo("Adding ${config.peers.size} peer(s) to config")
                // Add ?maxbackoff=30s to each peer URI for mobile-friendly timeouts
                val peersWithBackoff = config.peers.map { peer ->
                    if (peer.contains("?")) {
                        if (!peer.contains("maxbackoff=")) {
                            "$peer&maxbackoff=30s"
                        } else {
                            peer // Already has maxbackoff
                        }
                    } else {
                        "$peer?maxbackoff=30s"
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

            return finalConfig
        }

        // Build config with existing private key
        // IMPORTANT: Must match the structure from Mobile.generateConfig()
        logInfo("Using existing private key (length: ${config.privateKey.length}, key: ${truncatePrivateKey(config.privateKey)})")
        val peers = if (config.peers.isEmpty()) {
            "[]"
        } else {
            // Add ?maxbackoff=30s to each peer URI for mobile-friendly timeouts
            val peersWithBackoff = config.peers.map { peer ->
                if (peer.contains("?")) {
                    if (!peer.contains("maxbackoff=")) {
                        "$peer&maxbackoff=30s"
                    } else {
                        peer // Already has maxbackoff
                    }
                } else {
                    "$peer?maxbackoff=30s"
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

    private fun startPeerStatsSubscriptionMonitor() {
        peerStatsSubscriptionJob?.cancel()
        peerStatsSubscriptionJob = serviceScope.launch {
            _peerDetailsJSON.subscriptionCount.collect { count ->
                // Only react to subscriptions if service is running
                if (!_isRunning.value) {
                    logDebug("Service not running, ignoring subscription changes")
                    return@collect
                }
                
                if (count > 0) {
                    logDebug("Peer details subscriber active, starting stats updater")
                    startPeerStatsUpdater()
                } else {
                    logDebug("No peer details subscribers, stopping stats updater")
                    stopPeerStatsUpdater()
                }
            }
        }
    }

    private fun stopPeerStatsUpdater() {
        peerStatsJob?.cancel()
        peerStatsJob = null
    }

    private fun startPeerStatsUpdater() {
        // Cancel any existing updater first
        peerStatsJob?.cancel()
        peerStatsJob = serviceScope.launch {
            while (_isRunning.value) {
                try {
                    // Double-check service is still running before updating
                    if (!_isRunning.value) break
                    
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
                kotlinx.coroutines.delay(5000) // Update every 5 seconds
            }
            if (_isRunning.value) {
                logInfo("Peer stats updater stopped")
            }
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

    private fun updateNotification(status: String, peerCount: Int, totalPeerCount: Int) {
        val notification = createNotification(status, peerCount, totalPeerCount)
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

    private fun registerNetworkCallback() {
        try {
            // Initialize network state
            isOnWifi = checkNetworkType()
            isInitialNetworkCallback = true // Mark first callback as initial
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logInfo("Network available: ${network}")
                    
                    // Skip initial callback (fired immediately upon registration)
                    if (isInitialNetworkCallback) {
                        isInitialNetworkCallback = false
                        logInfo("Initial network callback - skipping peer retry")
                        hasNoNetwork = false
                        return
                    }
                    
                    // If returning from no-network state, trigger reconnection immediately
                    if (hasNoNetwork && _isRunning.value && yggstack != null) {
                        logInfo("Network restored after outage - triggering immediate reconnection")
                        hasNoNetwork = false
                        retryPeersNow()
                    } else {
                        hasNoNetwork = false
                    }
                }
                
                override fun onLost(network: Network) {
                    logInfo("Network lost: ${network}")
                    
                    // Check if a new network is already available before triggering retry
                    // This handles network switching (WiFi↔Cellular) correctly
                    val activeNetwork = connectivityManager.activeNetwork
                    if (activeNetwork != null && _isRunning.value) {
                        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                            logInfo("Switched to new network - forcing reconnection")
                            hasNoNetwork = false
                            retryPeersNow()
                        } else {
                            logInfo("New network available but no internet capability")
                            hasNoNetwork = true
                        }
                    } else {
                        logInfo("No alternative network available - waiting for connection")
                        hasNoNetwork = true
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

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                logInfo("Network monitoring unregistered")
            }
            networkCallback = null
            isInitialNetworkCallback = true // Reset for next registration
        } catch (e: Exception) {
            logError("Failed to unregister network callback: ${e.message}")
        }
    }

    private fun retryPeersNow() {
        serviceScope.launch {
            if (_isRunning.value) {
                try {
                    logInfo("Forcing peer reconnection due to network change...")
                    yggstack?.retryPeersNow()
                    logInfo("Peer retry triggered successfully")
                } catch (e: Exception) {
                    logError("Error triggering peer retry: ${e.message}")
                }
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
                put("logLevel", config.logLevel)
                put("exposeEnabled", config.exposeEnabled)
                put("forwardEnabled", config.forwardEnabled)
                
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
                
                lastConfig = YggstackConfig(
                    privateKey = json.optString("privateKey", ""),
                    peers = peers,
                    socksProxy = json.optString("socksProxy", ""),
                    dnsServer = json.optString("dnsServer", ""),
                    proxyEnabled = json.optBoolean("proxyEnabled", false),
                    multicastBeacon = json.optBoolean("multicastBeacon", true),
                    multicastListen = json.optBoolean("multicastListen", true),
                    logLevel = json.optString("logLevel", "info"),
                    exposeEnabled = json.optBoolean("exposeEnabled", false),
                    forwardEnabled = json.optBoolean("forwardEnabled", false),
                    exposeMappings = exposeMappings,
                    forwardMappings = forwardMappings
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

    companion object {
        private const val LOG_TAG = "YggstackService"
        const val CHANNEL_ID = "yggstack_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "link.yggdrasil.yggstack.android.action.START"
        const val ACTION_STOP = "link.yggdrasil.yggstack.android.action.STOP"
        const val EXTRA_CONFIG = "config"
        private const val MAX_LOG_ENTRIES = 500
        private const val MAX_CRASH_RESTART_ATTEMPTS = 3
        private const val PREFS_NAME = "yggstack_service_prefs"
        private const val PREF_LAST_CONFIG = "last_config"
        private const val PREF_WAS_RUNNING = "was_running"
    }
}

