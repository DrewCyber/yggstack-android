package io.github.yggstack.android.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

object AutostartHelper {
    
    /**
     * Check if device requires manufacturer-specific autostart permission
     */
    fun requiresManufacturerAutostartPermission(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer in listOf("xiaomi", "huawei", "oppo", "vivo", "letv", "honor", "asus", "meizu", "oneplus")
    }
    
    /**
     * Get manufacturer name for display
     */
    fun getManufacturerName(): String {
        return Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    }
    
    /**
     * Try to open manufacturer-specific autostart settings
     * Returns true if an intent was found and launched
     */
    fun openAutostartSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        val intents = when (manufacturer) {
            "xiaomi" -> getXiaomiIntents(context)
            "huawei" -> getHuaweiIntents(context)
            "oppo" -> getOppoIntents(context)
            "vivo" -> getVivoIntents(context)
            "letv" -> getLetvIntents(context)
            "honor" -> getHonorIntents(context)
            "asus" -> getAsusIntents(context)
            "meizu" -> getMeizuIntents(context)
            "oneplus" -> getOnePlusIntents(context)
            else -> emptyList()
        }
        
        // Try each intent in order until one works
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Try next intent
                continue
            }
        }
        
        return false
    }
    
    private fun getXiaomiIntents(context: Context): List<Intent> {
        return listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            Intent("miui.intent.action.AUTOSTART_MANAGER_SETTINGS"),
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                )
            }
        )
    }
    
    private fun getHuaweiIntents(context: Context): List<Intent> {
        return listOf(
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            }
        )
    }
    
    private fun getOppoIntents(context: Context): List<Intent> {
        return listOf(
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
            }
        )
    }
    
    private fun getVivoIntents(context: Context): List<Intent> {
        return listOf(
            Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            }
        )
    }
    
    private fun getLetvIntents(context: Context): List<Intent> {
        return listOf(
            Intent().apply {
                component = ComponentName(
                    "com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity"
                )
            }
        )
    }
    
    private fun getHonorIntents(context: Context): List<Intent> {
        return listOf(
            Intent().apply {
                component = ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
        )
    }
    
    private fun getAsusIntents(context: Context): List<Intent> {
        return listOf(
            Intent().apply {
                component = ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.MainActivity"
                )
            },
            Intent().apply {
                component = ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity"
                )
            }
        )
    }
    
    private fun getMeizuIntents(context: Context): List<Intent> {
        return listOf(
            Intent("com.meizu.safe.security.SHOW_APPSEC").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra("packageName", context.packageName)
            }
        )
    }
    
    private fun getOnePlusIntents(context: Context): List<Intent> {
        return listOf(
            Intent().apply {
                component = ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            }
        )
    }
}
