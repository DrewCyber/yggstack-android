package link.yggdrasil.yggstack.android.tile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import link.yggdrasil.yggstack.android.R
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.service.YggstackService
import link.yggdrasil.yggstack.android.service.YggstackConfigParcelable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class YggstackTileService : TileService() {
    
    companion object {
        private const val TAG = "YggstackTileService"
        const val ACTION_UPDATE_TILE = "link.yggdrasil.yggstack.android.UPDATE_TILE"
        const val EXTRA_IS_RUNNING = "is_running"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_TILE) {
                val isRunning = intent.getBooleanExtra(EXTRA_IS_RUNNING, false)
                updateTileState(isRunning)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        // Register receiver for service state updates
        val filter = IntentFilter(ACTION_UPDATE_TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceStateReceiver, filter)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceStateReceiver)
        scope.cancel()
    }
    
    override fun onStartListening() {
        super.onStartListening()
        // Update tile when it becomes visible
        val isRunning = YggstackService.isRunning()
        updateTileState(isRunning)
    }
    
    override fun onClick() {
        super.onClick()
        
        val isRunning = YggstackService.isRunning()
        
        if (isRunning) {
            // Stop service
            val intent = Intent(this, YggstackService::class.java).apply {
                action = YggstackService.ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateTileState(false)
        } else {
            // Start service - need to load config first
            scope.launch {
                try {
                    val repository = ConfigRepository(applicationContext)
                    val config = repository.configFlow.first()
                    
                    val intent = Intent(this@YggstackTileService, YggstackService::class.java).apply {
                        action = YggstackService.ACTION_START
                        putExtra(YggstackService.EXTRA_CONFIG,
                            YggstackConfigParcelable.fromYggstackConfig(config))
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    updateTileState(true)
                    Log.d(TAG, "Started Yggstack service from tile")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting service from tile", e)
                    updateTileState(false)
                }
            }
        }
    }
    
    private fun updateTileState(isRunning: Boolean) {
        qsTile?.apply {
            state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Yggstack"
            subtitle = if (isRunning) "Connected" else "Disconnected"
            icon = Icon.createWithResource(applicationContext, R.drawable.ic_tile)
            updateTile()
        }
    }
}
