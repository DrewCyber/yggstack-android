package link.yggdrasil.yggstack.android.utils

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Helper class for checking and managing app permissions related to
 * background execution and battery optimization
 */
object PermissionHelper {
    
    /**
     * Check if battery optimization is disabled for the app
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true // Not applicable for versions below M
    }
    
    /**
     * Check if the app can run in background without restrictions
     * This checks for background restrictions on Android P and above
     */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.isBackgroundRestricted
        }
        return false // Not applicable for versions below P
    }
    
    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true // Not required for versions below Android 13
    }
    
    /**
     * Check if all background and notification permissions are granted
     */
    fun hasAllBackgroundPermissions(context: Context): Boolean {
        return isBatteryOptimizationDisabled(context) && 
               !isBackgroundRestricted(context) && 
               isNotificationPermissionGranted(context)
    }
    
    /**
     * Get an intent to open battery optimization settings for the app
     */
    fun getBatteryOptimizationIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }
    
    /**
     * Get an intent to open app info settings where users can manage background restrictions
     */
    fun getAppInfoIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
    
    /**
     * Get an intent to open notification settings for the app
     */
    fun getNotificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            getAppInfoIntent(context)
        }
    }
    
    /**
     * Constant for notification permission request (Android 13+)
     */
    const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS
}
