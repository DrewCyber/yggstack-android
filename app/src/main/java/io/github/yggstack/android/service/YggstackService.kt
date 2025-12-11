package io.github.yggstack.android.service

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
import io.github.yggstack.android.MainActivity
import io.github.yggstack.android.R
import io.github.yggstack.android.data.YggstackConfig
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

/**
 * Foreground service for running Yggstack
 */
class YggstackService : Service() {

    private val binder = YggstackBinder()
    private var yggstack: Yggstack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _yggdrasilIp = MutableStateFlow<String?>(null)
    val yggdrasilIp: StateFlow<String?> = _yggdrasilIp.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _generatedPrivateKey = MutableStateFlow<String?>(null)
    val generatedPrivateKey: StateFlow<String?> = _generatedPrivateKey.asStateFlow()

    inner class YggstackBinder : Binder() {
        fun getService(): YggstackService = this@YggstackService
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
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

                // Log first 200 chars of config for debugging (without exposing full key)
                addLog("Config preview: ${configJson.take(200)}...")

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

                addLog("Calling start() with SOCKS='$socksAddress', DNS='$dnsServer'...")
                yggstack?.start(socksAddress, dnsServer)
                addLog("Start() completed successfully")

                // Setup port mappings
                setupPortMappings(config)

                // Get and store the Yggdrasil IP AFTER starting
                addLog("Getting Yggdrasil IP address...")
                val address = yggstack?.address
                _yggdrasilIp.value = address
                addLog("Yggdrasil IP: $address")

                _isRunning.value = true
                _peerCount.value = config.peers.size

                addLog("Yggstack started successfully")
                updateNotification("Connected", config.peers.size)

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

            // Log FULL config for debugging
            addLog("FULL GENERATED CONFIG: $newConfigJson")

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

            addLog("Config after adding Certificate: ${configWithCert.take(300)}...")

            // Extract the private key to save it back to the repository
            val keyMatch = Regex("\"PrivateKey\":\\s*\"([^\"]+)\"").find(newConfigJson)
            val extractedKey = keyMatch?.groupValues?.get(1) ?: ""

            if (extractedKey.isNotBlank()) {
                addLog("Private key extracted (length: ${extractedKey.length}, first 10 chars: ${extractedKey.take(10)}...)")
                _generatedPrivateKey.value = extractedKey
                addLog("Generated key will be saved to configuration")
            } else {
                addLog("ERROR: Failed to extract generated private key from config!")
            }

            // If we have peers, we need to merge them into the generated config
            if (config.peers.isNotEmpty()) {
                addLog("Adding ${config.peers.size} peer(s) to config")
                val peersJson = config.peers.joinToString("\",\"", "[\"", "\"]")
                val finalConfig = configWithCert.replace(
                    Regex("\"Peers\":\\s*\\[\\s*\\]"),
                    "\"Peers\": $peersJson"
                )
                return finalConfig
            }

            return configWithCert
        }

        // Build config with existing private key
        // IMPORTANT: Must match the structure from Mobile.generateConfig()
        addLog("Using existing private key (length: ${config.privateKey.length}, first 10 chars: ${config.privateKey.take(10)}...)")
        val peers = if (config.peers.isEmpty()) {
            "[]"
        } else {
            config.peers.joinToString("\",\"", "[\"", "\"]")
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
  "MulticastInterfaces": [
    {
      "Regex": ".*",
      "Beacon": true,
      "Listen": true,
      "Password": ""
    }
  ],
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
            // Clear any existing mappings
            yggstack?.clearLocalMappings()
            yggstack?.clearRemoteMappings()

            // Setup Forward Remote Port (local mappings - forward from local to remote Yggdrasil)
            if (config.forwardEnabled && config.forwardMappings.isNotEmpty()) {
                addLog("Setting up ${config.forwardMappings.size} forward port mapping(s)...")
                config.forwardMappings.forEach { mapping ->
                    try {
                        val localAddr = "${mapping.localIp}:${mapping.localPort}"
                        val remoteAddr = "[${mapping.remoteIp}]:${mapping.remotePort}"
                        
                        when (mapping.protocol) {
                            io.github.yggstack.android.data.Protocol.TCP -> {
                                yggstack?.addLocalTCPMapping(localAddr, remoteAddr)
                                addLog("Added TCP forward: $localAddr -> $remoteAddr")
                            }
                            io.github.yggstack.android.data.Protocol.UDP -> {
                                yggstack?.addLocalUDPMapping(localAddr, remoteAddr)
                                addLog("Added UDP forward: $localAddr -> $remoteAddr")
                            }
                        }
                    } catch (e: Exception) {
                        addLog("Error adding forward mapping: ${e.message}")
                    }
                }
            }

            // Setup Expose Local Port (remote mappings - expose local port on Yggdrasil)
            if (config.exposeEnabled && config.exposeMappings.isNotEmpty()) {
                addLog("Setting up ${config.exposeMappings.size} expose port mapping(s)...")
                config.exposeMappings.forEach { mapping ->
                    try {
                        val localAddr = "${mapping.localIp}:${mapping.localPort}"
                        
                        when (mapping.protocol) {
                            io.github.yggstack.android.data.Protocol.TCP -> {
                                yggstack?.addRemoteTCPMapping(mapping.yggPort.toLong(), localAddr)
                                addLog("Exposed TCP port ${mapping.yggPort} -> $localAddr")
                            }
                            io.github.yggstack.android.data.Protocol.UDP -> {
                                yggstack?.addRemoteUDPMapping(mapping.yggPort.toLong(), localAddr)
                                addLog("Exposed UDP port ${mapping.yggPort} -> $localAddr")
                            }
                        }
                    } catch (e: Exception) {
                        addLog("Error adding expose mapping: ${e.message}")
                    }
                }
            }

            if (!config.forwardEnabled && !config.exposeEnabled) {
                addLog("No port mappings configured")
            }
        } catch (e: Exception) {
            addLog("Error setting up port mappings: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"

        _logs.value = (_logs.value + logEntry).takeLast(MAX_LOG_ENTRIES)
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
            acquire(10 * 60 * 1000L) // 10 minutes timeout
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
        const val ACTION_START = "io.github.yggstack.android.action.START"
        const val ACTION_STOP = "io.github.yggstack.android.action.STOP"
        const val EXTRA_CONFIG = "config"
        private const val MAX_LOG_ENTRIES = 500
    }
}

