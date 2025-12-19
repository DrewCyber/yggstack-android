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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import link.yggdrasil.yggstack.android.MainActivity
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.YggstackConfig
import link.yggdrasil.yggstack.android.data.PersistentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import link.yggdrasil.yggstack.mobile.LogCallback
import link.yggdrasil.yggstack.mobile.Mobile
import link.yggdrasil.yggstack.mobile.Yggstack
import org.json.JSONArray

/**
 * Foreground service for running Yggstack
 */
class YggstackService : Service() {

    private val binder = YggstackBinder()
    private var yggstack: Yggstack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var persistentLogger: PersistentLogger
    private var peerStatsJob: kotlinx.coroutines.Job? = null
    
    // Operation state management
    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()
    private val operationMutex = kotlinx.coroutines.sync.Mutex()
    
    // Network connectivity monitoring
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager by lazy { 
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager 
    }
    private var lastNetworkType: String? = null
    private var lastNetworkChangeTime: Long = 0
    private val NETWORK_CHANGE_DEBOUNCE_MS = 5000L // 5 seconds to avoid flip-flop during transitions
    
    // Store last config for automatic restart after crash
    private var lastConfig: YggstackConfig? = null
    private var crashRestartAttempts = 0

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

    private val _peerDetailsJSON = MutableStateFlow<String>("[]")
    val peerDetailsJSON: StateFlow<String> = _peerDetailsJSON.asStateFlow()

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
        createNotificationChannel()
        acquireWakeLock()
        registerNetworkCallback()
        
        // Load existing logs on startup
        serviceScope.launch {
            _logs.value = persistentLogger.readLogs()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                addLog("onStartCommand: ACTION_START received")
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONFIG, YggstackConfigParcelable::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<YggstackConfigParcelable>(EXTRA_CONFIG)
                }
                config?.let { startYggstack(it.toYggstackConfig()) }
            }
            ACTION_STOP -> {
                addLog("onStartCommand: ACTION_STOP received")
                stopYggstack()
            }
            null -> {
                // Service was restarted by system after being killed
                addLog("=== WARNING: Service restarted by system (intent=null) ===")
                addLog("This indicates the app/service was killed by the system")
                if (lastConfig != null && !_isRunning.value) {
                    addLog("Attempting automatic restart with last config after system kill")
                    startYggstack(lastConfig!!)
                } else if (_isRunning.value) {
                    addLog("Service claims to be running - checking state consistency")
                } else {
                    addLog("No config available - service will remain stopped")
                    addLog("User must manually restart the service")
                }
            }
        }
        // Restart service if killed by system, preserving lastConfig
        return START_STICKY
    }

    override fun onDestroy() {
        addLog("=== YggstackService onDestroy - service being destroyed ===")
        super.onDestroy()
        stopYggstack()
        unregisterNetworkCallback()
        releaseWakeLock()
        serviceScope.cancel()
    }

    fun startYggstack(config: YggstackConfig) {
        serviceScope.launch {
            // Use mutex to prevent concurrent start/stop operations
            if (!operationMutex.tryLock()) {
                addLog("Operation already in progress - ignoring start request")
                return@launch
            }
            
            try {
                if (_isRunning.value) {
                    addLog("Yggstack is already running")
                    return@launch
                }
                
                _isTransitioning.value = true
                
                // Store config for crash recovery
                lastConfig = config
                crashRestartAttempts = 0
                
                // Force cleanup any zombie instance before starting
                if (yggstack != null) {
                    addLog("Cleaning up existing Yggstack instance...")
                    try {
                        yggstack?.stop()
                    } catch (e: Exception) {
                        addLog("Error cleaning up old instance: ${e.message}")
                    }
                    yggstack = null
                    kotlinx.coroutines.delay(500) // Give it time to fully stop
                }
                
                addLog("Starting Yggstack...")
                addLog("App version: ${link.yggdrasil.yggstack.android.BuildConfig.VERSION_NAME}")
                addLog("Commit: ${link.yggdrasil.yggstack.android.BuildConfig.COMMIT_HASH}")
                startForeground(NOTIFICATION_ID, createNotification("Starting...", 0, 0))

                // Create Yggstack instance
                yggstack = Mobile.newYggstack()
                yggstack?.setLogCallback(object : LogCallback {
                    override fun onLog(message: String) {
                        addLog(message.trim())
                    }
                })
                yggstack?.setLogLevel("info")

                // Build config JSON (handles both new and existing private keys)
                addLog("Loading configuration...")
                val configJson = buildConfigJson(config)
                
                // Store SANITIZED config JSON for diagnostics display (private key truncated)
                _fullConfigJSON.value = sanitizeConfigJson(configJson)

                addLog("Calling loadConfigJSON...")
                yggstack?.loadConfigJSON(configJson)
                addLog("Config loaded successfully")

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

                addLog("Calling start() with SOCKS='$socksAddress', DNS='$dnsServer'...")
                yggstack?.start(socksAddress, dnsServer)
                addLog("Start() completed successfully")

                // Get and store the Yggdrasil IP AFTER starting
                addLog("Getting Yggdrasil IP address...")
                val address = yggstack?.address
                _yggdrasilIp.value = address
                addLog("Yggdrasil IP: $address")

                _isRunning.value = true
                _peerCount.value = 0
                _isTransitioning.value = false

                addLog("Yggstack started successfully")
                updateNotification("Connected", 0, 0)

                // Start periodic peer stats update
                startPeerStatsUpdater()

            } catch (e: Exception) {
                addLog("ERROR starting Yggstack: ${e.message}")
                addLog("Stack trace: ${e.stackTraceToString().take(500)}")

                // Clean up properly on error
                try {
                    yggstack?.stop()
                } catch (stopError: Exception) {
                    addLog("Error during cleanup: ${stopError.message}")
                }
                yggstack = null
                _isRunning.value = false
                _isTransitioning.value = false
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _totalPeerCount.value = 0

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
                
                addLog("Service stopped due to error. Please check configuration and try again.")
            } finally {
                operationMutex.unlock()
            }
        }
    }

    fun stopYggstack() {
        serviceScope.launch {
            // Use mutex to prevent concurrent start/stop operations
            if (!operationMutex.tryLock()) {
                addLog("Operation already in progress - ignoring stop request")
                return@launch
            }
            
            try {
                // Force cleanup even if _isRunning is false (handles desync state)
                if (!_isRunning.value && yggstack == null) {
                    addLog("Service already stopped")
                    return@launch
                }
                
                if (!_isRunning.value && yggstack != null) {
                    addLog("WARNING: State desync detected - forcing cleanup of zombie instance")
                }
                
                _isTransitioning.value = true

                addLog("Stopping Yggstack...")
                _isRunning.value = false  // Set this first to stop the peer updater
                
                // Cancel peer stats updater
                peerStatsJob?.cancel()
                peerStatsJob = null
                
                // Stop yggstack and wait for it to complete
                yggstack?.stop()
                yggstack = null
                
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _totalPeerCount.value = 0
                _generatedPrivateKey.value = null  // Reset generated key state
                _isTransitioning.value = false
                
                addLog("Yggstack stopped")
                
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
                addLog("Error stopping Yggstack: ${e.message}")
                // Force cleanup even on error
                yggstack = null
                _isRunning.value = false
                _isTransitioning.value = false
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _totalPeerCount.value = 0
                _generatedPrivateKey.value = null
                
                // Cancel notification on error too
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
            } finally {
                operationMutex.unlock()
            }
        }
    }

    private fun buildConfigJson(config: YggstackConfig): String {
        // If no private key is provided, generate a complete new config
        if (config.privateKey.isBlank()) {
            addLog("No private key found - generating new configuration...")
            val newConfigJson = Mobile.generateConfig()
            addLog("Generated config length: ${newConfigJson.length} chars")

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
                addLog("Private key extracted (length: ${extractedKey.length}, key: ${truncatePrivateKey(extractedKey)})")
                _generatedPrivateKey.value = extractedKey
                addLog("Generated key will be saved to configuration")
            } else {
                addLog("ERROR: Failed to extract generated private key from config!")
            }

            // Apply peers and multicast configuration
            var finalConfig = configWithCert
            
            if (config.peers.isNotEmpty()) {
                addLog("Adding ${config.peers.size} peer(s) to config")
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
            if (!config.multicastEnabled) {
                addLog("Multicast discovery disabled - removing MulticastInterfaces")
                finalConfig = finalConfig.replace(
                    Regex("\"MulticastInterfaces\":\\s*\\[[^\\]]*\\]"),
                    "\"MulticastInterfaces\": []"
                )
            } else {
                addLog("Multicast discovery enabled - using default configuration")
            }

            return finalConfig
        }

        // Build config with existing private key
        // IMPORTANT: Must match the structure from Mobile.generateConfig()
        addLog("Using existing private key (length: ${config.privateKey.length}, key: ${truncatePrivateKey(config.privateKey)})")
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

        val multicastInterfaces = if (config.multicastEnabled) {
            addLog("Multicast discovery enabled - using default configuration")
            """[
    {
      "Regex": ".*",
      "Beacon": true,
      "Listen": true,
      "Password": ""
    }
  ]"""
        } else {
            addLog("Multicast discovery disabled - using empty configuration")
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

        addLog("Built manual config matching generated structure")
        return manualConfig
    }

    private fun setupPortMappings(config: YggstackConfig) {
        try {
            // Note: Mappings should be set up BEFORE calling start()
            // so the handlers are started properly in the Start() function
            
            // Setup Forward Remote Port (local mappings - forward from local to remote Yggdrasil)
            if (config.forwardEnabled && config.forwardMappings.isNotEmpty()) {
                addLog("Setting up ${config.forwardMappings.size} forward port mapping(s)...")
                config.forwardMappings.forEach { mapping ->
                    try {
                        val localAddr = "${mapping.localIp}:${mapping.localPort}"
                        val remoteAddr = "[${mapping.remoteIp}]:${mapping.remotePort}"
                        
                        addLog("Configuring ${mapping.protocol} forward mapping: $localAddr -> $remoteAddr")
                        
                        when (mapping.protocol) {
                            link.yggdrasil.yggstack.android.data.Protocol.TCP -> {
                                yggstack?.addLocalTCPMapping(localAddr, remoteAddr)
                                addLog("✓ Added TCP forward: $localAddr -> $remoteAddr")
                            }
                            link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                                yggstack?.addLocalUDPMapping(localAddr, remoteAddr)
                                addLog("✓ Added UDP forward: $localAddr -> $remoteAddr")
                            }
                        }
                    } catch (e: Exception) {
                        addLog("✗ Error adding forward mapping: ${e.message}")
                        addLog("Stack trace: ${e.stackTraceToString().take(300)}")
                    }
                }
            } else {
                addLog("No forward mappings configured (enabled=${config.forwardEnabled}, count=${config.forwardMappings.size})")
            }

            // Setup Expose Local Port (remote mappings - expose local port on Yggdrasil)
            if (config.exposeEnabled && config.exposeMappings.isNotEmpty()) {
                addLog("Setting up ${config.exposeMappings.size} expose port mapping(s)...")
                config.exposeMappings.forEach { mapping ->
                    try {
                        val localAddr = "${mapping.localIp}:${mapping.localPort}"
                        
                        addLog("Configuring ${mapping.protocol} expose mapping: Ygg port ${mapping.yggPort} -> $localAddr")
                        
                        when (mapping.protocol) {
                            link.yggdrasil.yggstack.android.data.Protocol.TCP -> {
                                yggstack?.addRemoteTCPMapping(mapping.yggPort.toLong(), localAddr)
                                addLog("✓ Exposed TCP port ${mapping.yggPort} -> $localAddr")
                            }
                            link.yggdrasil.yggstack.android.data.Protocol.UDP -> {
                                yggstack?.addRemoteUDPMapping(mapping.yggPort.toLong(), localAddr)
                                addLog("✓ Exposed UDP port ${mapping.yggPort} -> $localAddr")
                            }
                        }
                    } catch (e: Exception) {
                        addLog("✗ Error adding expose mapping: ${e.message}")
                        addLog("Stack trace: ${e.stackTraceToString().take(300)}")
                    }
                }
            } else {
                addLog("No expose mappings configured (enabled=${config.exposeEnabled}, count=${config.exposeMappings.size})")
            }

            if (!config.forwardEnabled && !config.exposeEnabled) {
                addLog("Port forwarding disabled - no mappings will be configured")
            }
        } catch (e: Exception) {
            addLog("✗ Error setting up port mappings: ${e.message}")
            addLog("Stack trace: ${e.stackTraceToString().take(300)}")
        }
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
                        _peerDetailsJSON.value = peersJson
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
                            addLog("Error parsing peer JSON: ${e.message}")
                            _peerCount.value = 0
                            _totalPeerCount.value = 0
                            if (_isRunning.value) {
                                updateNotification("Connected", 0, 0)
                            }
                        }
                    } else {
                        // Yggstack returned null - instance crashed/corrupted
                        addLog("ERROR: getPeersJSON returned null - Yggstack instance is corrupted")
                        if (_isRunning.value) {
                            addLog("Detected Yggstack crash - attempting automatic restart...")
                            _isRunning.value = false
                            _peerCount.value = 0
                            _totalPeerCount.value = 0
                            
                            // Attempt automatic restart if we have the config
                            if (lastConfig != null && crashRestartAttempts < MAX_CRASH_RESTART_ATTEMPTS) {
                                crashRestartAttempts++
                                val backoffDelay = (crashRestartAttempts * 2000L).coerceAtMost(10000L)
                                addLog("Crash restart attempt $crashRestartAttempts/$MAX_CRASH_RESTART_ATTEMPTS (waiting ${backoffDelay}ms)...")
                                updateNotification("Restarting after crash...", 0, 0)
                                
                                kotlinx.coroutines.delay(backoffDelay)
                                
                                // Force cleanup of corrupted instance
                                try {
                                    yggstack?.stop()
                                } catch (e: Exception) {
                                    addLog("Error stopping corrupted instance: ${e.message}")
                                }
                                yggstack = null
                                kotlinx.coroutines.delay(1000)
                                
                                // Restart with same config
                                addLog("Restarting Yggstack after crash...")
                                startYggstack(lastConfig!!)
                            } else {
                                val reason = if (lastConfig == null) "no config available" else "max restart attempts reached"
                                addLog("ERROR: Cannot auto-restart - $reason")
                                updateNotification("Crashed - manual restart required", 0, 0)
                            }
                        }
                        break
                    }
                } catch (e: Exception) {
                    addLog("Error fetching peer stats: ${e.message}")
                    // Don't break on transient errors, but log them
                }
                kotlinx.coroutines.delay(5000) // Update every 5 seconds
            }
            if (_isRunning.value) {
                addLog("Peer stats updater stopped")
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
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
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
            .setSmallIcon(R.drawable.ic_notification)
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
                PendingIntent.FLAG_IMMUTABLE
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

    private fun registerNetworkCallback() {
        try {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    addLog("Network available: ${network}")
                    // Trigger reconnection when network comes back (handles WiFi drop/restore)
                    // Only if peers are actually disconnected (peerCount = 0)
                    if (_isRunning.value && _peerCount.value == 0) {
                        addLog("Network restored while disconnected - forcing reconnection")
                        retryPeersNow()
                    }
                }
                
                override fun onLost(network: Network) {
                    addLog("Network lost: ${network}")
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
                            addLog("Network switched: $lastNetworkType -> $transportType")
                            // Force reconnection when transport type actually changes
                            retryPeersNow()
                        } else {
                            addLog("Initial network: $transportType")
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
            addLog("Network monitoring registered")
        } catch (e: Exception) {
            addLog("Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                addLog("Network monitoring unregistered")
            }
            networkCallback = null
        } catch (e: Exception) {
            addLog("Failed to unregister network callback: ${e.message}")
        }
    }

    private fun retryPeersNow() {
        serviceScope.launch {
            if (_isRunning.value) {
                try {
                    addLog("Forcing peer reconnection due to network change...")
                    yggstack?.retryPeersNow()
                    addLog("Peer retry triggered successfully")
                } catch (e: Exception) {
                    addLog("Error triggering peer retry: ${e.message}")
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "yggstack_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "link.yggdrasil.yggstack.android.action.START"
        const val ACTION_STOP = "link.yggdrasil.yggstack.android.action.STOP"
        const val EXTRA_CONFIG = "config"
        private const val MAX_LOG_ENTRIES = 500
        private const val MAX_CRASH_RESTART_ATTEMPTS = 3
    }
}

