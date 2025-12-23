package link.yggdrasil.yggstack.android.tile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.service.YggstackService

/**
 * Quick Settings Tile for Yggstack
 * Allows users to quickly start/stop the service from the notification shade
 */
class YggstackTileService : TileService() {

    private var yggstackService: YggstackService? = null
    private var isBound = false
    private val tileScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateObserverJob: Job? = null
    private lateinit var configRepository: ConfigRepository

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "Service connected")
            val localBinder = binder as? YggstackService.YggstackBinder
            yggstackService = localBinder?.getService()
            isBound = true
            
            // Observe service state changes
            observeServiceState()
            updateTileState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            yggstackService = null
            isBound = false
            stateObserverJob?.cancel()
            updateTileState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        configRepository = ConfigRepository(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "onStartListening")
        
        // Bind to service to observe state
        val intent = Intent(this, YggstackService::class.java)
        try {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind service", e)
            updateTileState()
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "onStopListening")
        
        // Unbind from service
        if (isBound) {
            try {
                unbindService(serviceConnection)
                isBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind service", e)
            }
        }
        stateObserverJob?.cancel()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "onClick - current state: ${qsTile?.state}")
        
        val isCurrentlyRunning = yggstackService?.isRunning?.value ?: false
        
        if (isCurrentlyRunning) {
            // Stop the service
            stopYggstackService()
        } else {
            // Start the service with saved config
            startYggstackService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind service in onDestroy", e)
            }
        }
        tileScope.cancel()
    }

    private fun observeServiceState() {
        stateObserverJob?.cancel()
        stateObserverJob = tileScope.launch {
            yggstackService?.isRunning?.collect { isRunning ->
                Log.d(TAG, "Service state changed: isRunning=$isRunning")
                updateTileState()
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        
        // If service is not bound, check if it's actually running by trying to bind
        val isRunning = yggstackService?.isRunning?.value ?: false
        val isTransitioning = yggstackService?.isTransitioning?.value ?: false
        
        Log.d(TAG, "updateTileState: isRunning=$isRunning, isTransitioning=$isTransitioning, isBound=$isBound")
        
        // Set tile state
        tile.state = when {
            isTransitioning -> Tile.STATE_UNAVAILABLE
            isRunning -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        
        // Set tile label
        tile.label = getString(R.string.app_name)
        
        // Set subtitle based on state (only available in Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                isTransitioning -> "Transitioning..."
                isRunning -> "Active"
                else -> "Inactive"
            }
        }
        
        // Set icon
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        
        tile.updateTile()
    }

    private fun startYggstackService() {
        tileScope.launch(Dispatchers.IO) {
            try {
                // Load saved configuration
                val savedConfig = configRepository.configFlow.first()
                Log.d(TAG, "Starting service with saved config")
                
                val intent = Intent(this@YggstackTileService, YggstackService::class.java).apply {
                    action = YggstackService.ACTION_START
                    putExtra(
                        YggstackService.EXTRA_CONFIG,
                        link.yggdrasil.yggstack.android.service.YggstackConfigParcelable.fromYggstackConfig(savedConfig)
                    )
                }
                
                startForegroundService(intent)
                
                // Give service time to start and bind, then update tile
                kotlinx.coroutines.delay(500)
                
                launch(Dispatchers.Main) {
                    if (isBound && yggstackService?.isRunning?.value == true) {
                        val tile = qsTile
                        tile?.state = Tile.STATE_ACTIVE
                        tile?.updateTile()
                    } else {
                        // Fallback if not bound yet - assume it's running
                        val tile = qsTile
                        tile?.state = Tile.STATE_ACTIVE
                        tile?.updateTile()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }
    }

    private fun stopYggstackService() {
        tileScope.launch {
            try {
                Log.d(TAG, "Stopping service")
                
                val intent = Intent(this@YggstackTileService, YggstackService::class.java).apply {
                    action = YggstackService.ACTION_STOP
                }
                
                startService(intent)
                
                // Give service time to process stop command, then force update
                kotlinx.coroutines.delay(500)
                
                // If service is stopped, it will unbind, so we need to handle that
                // Update tile to inactive state after a brief delay
                launch(Dispatchers.Main) {
                    if (!isBound || yggstackService?.isRunning?.value == false) {
                        val tile = qsTile
                        tile?.state = Tile.STATE_INACTIVE
                        tile?.updateTile()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }

    companion object {
        private const val TAG = "YggstackTileService"
    }
}
