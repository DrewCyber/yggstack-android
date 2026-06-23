package link.yggdrasil.yggstack.android.automation

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.service.YggstackConfigParcelable
import link.yggdrasil.yggstack.android.service.YggstackService

/**
 * Transparent activity that starts the Yggstack service.
 * Uses a detached coroutine scope so finish() does not race with the IO work.
 */
class AutomationStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "AutomationStartActivity: starting service")
        // Use a detached scope — not tied to the activity lifecycle,
        // so finish() below does not cancel the work mid-flight.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val configRepository = ConfigRepository(applicationContext)
                val config = configRepository.configFlow.first()
                val serviceIntent = Intent(applicationContext, YggstackService::class.java).apply {
                    action = YggstackService.ACTION_START
                    putExtra(
                        YggstackService.EXTRA_CONFIG,
                        YggstackConfigParcelable.fromYggstackConfig(config)
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(serviceIntent)
                } else {
                    applicationContext.startService(serviceIntent)
                }
                Log.d(TAG, "AutomationStartActivity: service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "AutomationStartActivity: failed to start service", e)
            }
        }
        finish()
    }

    companion object {
        private const val TAG = "AutomationStartActivity"
    }
}

/**
 * Transparent activity that stops the Yggstack service.
 */
class AutomationStopActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "AutomationStopActivity: stopping service")
        try {
            val serviceIntent = Intent(this, YggstackService::class.java).apply {
                action = YggstackService.ACTION_STOP
            }
            startService(serviceIntent)
            Log.d(TAG, "AutomationStopActivity: service stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "AutomationStopActivity: failed to stop service", e)
        }
        finish()
    }

    companion object {
        private const val TAG = "AutomationStopActivity"
    }
}

