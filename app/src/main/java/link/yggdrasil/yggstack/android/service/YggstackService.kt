package link.yggdrasil.yggstack.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

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
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONFIG, YggstackConfigParcelable::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<YggstackConfigParcelable>(EXTRA_CONFIG)
                }
                config?.let { startYggstack(it.toYggstackConfig()) }
            }
            ACTION_STOP -> stopYggstack()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopYggstack()
        releaseWakeLock()
        serviceScope.cancel()
    }

    fun startYggstack(config: YggstackConfig) {
        if (_isRunning.value) {
            addLog("Yggstack is already running")
            return
        }

        serviceScope.launch {
            try {
                addLog("Starting Yggstack...")
                addLog("App version: ${link.yggdrasil.yggstack.android.BuildConfig.VERSION_NAME}")
                addLog("Commit: ${link.yggdrasil.yggstack.android.BuildConfig.COMMIT_HASH}")
                startForeground(NOTIFICATION_ID, createNotification("Starting...", 0))

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

                addLog("Yggstack started successfully")
                updateNotification("Connected", 0)

                // Start periodic peer stats update
                startPeerStatsUpdater()

            } catch (e: Exception) {
                addLog("Error starting Yggstack: ${e.message}")
                addLog("Stack trace: ${e.stackTraceToString().take(500)}")

                // Clean up properly on error
                try {
                    yggstack?.stop()
                } catch (stopError: Exception) {
                    addLog("Error during cleanup: ${stopError.message}")
                }
                yggstack = null
                _isRunning.value = false
                _yggdrasilIp.value = null
                _peerCount.value = 0


                // Don't call stopSelf() here - let the service stay alive for retry
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
        }
    }

    fun stopYggstack() {
        if (!_isRunning.value) {
            addLog("Service already stopped")
            return
        }

        serviceScope.launch {
            try {
                addLog("Stopping Yggstack...")
                yggstack?.stop()
                yggstack = null
                _isRunning.value = false
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _generatedPrivateKey.value = null  // Reset generated key state
                addLog("Yggstack stopped")
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
                _yggdrasilIp.value = null
                _peerCount.value = 0
                _generatedPrivateKey.value = null
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
                val peersJson = config.peers.joinToString("\",\"", "[\"", "\"]")
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
            config.peers.joinToString("\",\"", "[\"", "\"]")
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
        val manualConfig = """
{
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
}
        """.trimIndent()

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
        serviceScope.launch {
            while (_isRunning.value) {
                try {
                    val peersJson = yggstack?.getPeersJSON()
                    if (peersJson != null) {
                        _peerDetailsJSON.value = peersJson
                        // Update peer count from actual connected peers
                        try {
                            val jsonArray = JSONArray(peersJson)
                            val count = jsonArray.length()
                            _peerCount.value = count
                            // Update notification with actual peer count
                            updateNotification("Connected", count)
                        } catch (e: Exception) {
                            addLog("Error parsing peer JSON: ${e.message}")
                            _peerCount.value = 0
                            updateNotification("Connected", 0)
                        }
                    }
                } catch (e: Exception) {
                    addLog("Error fetching peer stats: ${e.message}")
                }
                kotlinx.coroutines.delay(5000) // Update every 5 seconds
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Yggstack Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Yggstack background service notification"
                setShowBadge(true)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String, peerCount: Int): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, YggstackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = buildString {
            append(status)
            if (_yggdrasilIp.value != null) {
                append("\n${_yggdrasilIp.value}")
            }
            if (peerCount > 0) {
                append("\nPeers: $peerCount")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Yggstack")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopPendingIntent
            )
            .setOngoing(true)
            .setShowWhen(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String, peerCount: Int) {
        val notification = createNotification(status, peerCount)
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

    companion object {
        const val CHANNEL_ID = "yggstack_service_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "link.yggdrasil.yggstack.android.action.START"
        const val ACTION_STOP = "link.yggdrasil.yggstack.android.action.STOP"
        const val EXTRA_CONFIG = "config"
        private const val MAX_LOG_ENTRIES = 500
    }
}

