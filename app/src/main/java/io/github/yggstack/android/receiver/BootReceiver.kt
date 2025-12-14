package io.github.yggstack.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.yggstack.android.data.ConfigRepository
import io.github.yggstack.android.service.YggstackService
import io.github.yggstack.android.service.YggstackConfigParcelable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "BOOT_COMPLETED received")
            
            // Use goAsync() to allow asynchronous work in broadcast receiver
            val pendingResult = goAsync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            
            scope.launch {
                try {
                    val repository = ConfigRepository(context)
                    val autostartEnabled = repository.autostartFlow.first()
                    
                    Log.d(TAG, "Autostart enabled: $autostartEnabled")
                    
                    if (autostartEnabled) {
                        // Load config and start service
                        val config = repository.configFlow.first()
                        
                        val serviceIntent = Intent(context, YggstackService::class.java).apply {
                            action = YggstackService.ACTION_START
                            putExtra(YggstackService.EXTRA_CONFIG, 
                                YggstackConfigParcelable.fromYggstackConfig(config))
                        }
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                            Log.d(TAG, "Started foreground service")
                        } else {
                            context.startService(serviceIntent)
                            Log.d(TAG, "Started service")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in boot receiver", e)
                } finally {
                    // Must call finish() on the pendingResult
                    pendingResult.finish()
                }
            }
        }
    }
}
