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
import androidx.annotation.RequiresApi
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
 * Requires Android 7.0+ (API 24) for Quick Settings Tile support
 */
@RequiresApi(Build.VERSION_CODES.N)
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
        
        // Immediately update tile to show transitioning
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = "Transitioning..."
            }
            updateTile()
        }
        
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
        
        // Observe both isRunning and isTransitioning states
        stateObserverJob = tileScope.launch {
            launch {
                yggstackService?.isRunning?.collect { isRunning ->
                    Log.d(TAG, "Service isRunning changed: $isRunning")
                    updateTileState()
                }
            }
            launch {
                yggstackService?.isTransitioning?.collect { isTransitioning ->
                    Log.d(TAG, "Service isTransitioning changed: $isTransitioning")
                    updateTileState()
                }
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
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                
                // State will be updated automatically via observers
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
                // Reset tile on error
                launch(Dispatchers.Main) {
                    updateTileState()
                }
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
                
                // State will be updated automatically via observers
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
                // Reset tile on error
                launch(Dispatchers.Main) {
                    updateTileState()
                }
            }
        }
    }

    companion object {
        private const val TAG = "YggstackTileService"
    }
}
