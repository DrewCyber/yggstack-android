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

    inner class YggstackBinder : Binder() {
        fun getService(): YggstackService = this@YggstackService
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

                // Generate or load config
                val configJson = if (config.privateKey.isBlank()) {
                    addLog("Generating new configuration...")
                    Mobile.generateConfig()
                } else {
                    addLog("Using provided configuration...")
                    buildConfigJson(config)
                }

                yggstack?.loadConfigJSON(configJson)

                // Get and store the Yggdrasil IP
                val address = yggstack?.address
                _yggdrasilIp.value = address
                addLog("Yggdrasil IP: $address")

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

                yggstack?.start(socksAddress, dnsServer)

                _isRunning.value = true
                _peerCount.value = config.peers.size

                addLog("Yggstack started successfully")
                updateNotification("Connected", config.peers.size)

            } catch (e: Exception) {
                addLog("Error starting Yggstack: ${e.message}")
                _isRunning.value = false
                stopSelf()
            }
        }
    }

    fun stopYggstack() {
        if (!_isRunning.value) {
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
            }
        }
    }

    private fun buildConfigJson(config: YggstackConfig): String {
        // Build a minimal JSON config from the YggstackConfig
        val peers = config.peers.joinToString("\",\"", "[\"", "\"]")
        val privateKey = if (config.privateKey.isNotBlank()) {
            config.privateKey
        } else {
            // Generate a new key if not provided
            val newConfig = Mobile.generateConfig()
            // Extract the private key from the generated config
            val keyMatch = Regex("\"PrivateKey\":\\s*\"([^\"]+)\"").find(newConfig)
            keyMatch?.groupValues?.get(1) ?: ""
        }

        return """
        {
          "PrivateKey": "$privateKey",
          "Peers": $peers,
          "Listen": [],
          "AdminListen": "none",
          "MulticastInterfaces": [],
          "InterfacePeers": {},
          "AllowedPublicKeys": [],
          "NodeInfo": {},
          "NodeInfoPrivacy": false
        }
        """.trimIndent()
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
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Yggstack background service notification"
                setShowBadge(false)
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopPendingIntent
            )
            .setOngoing(true)
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

