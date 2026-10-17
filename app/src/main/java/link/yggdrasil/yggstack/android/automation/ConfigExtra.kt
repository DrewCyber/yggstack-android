package link.yggdrasil.yggstack.android.automation

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import link.yggdrasil.yggstack.android.data.BackupConfig
import link.yggdrasil.yggstack.android.data.ConfigRepository
import java.io.File

/**
 * Shared handling for the `config` intent extra, accepted by MainActivity
 * and AutomationStartActivity:
 *
 *     am start -n .../.MainActivity --es config "$(cat backup.toml)"
 *     am start -n .../.automation.AutomationStartActivity --es config /data/local/tmp/backup.toml
 *
 * The value is either the backup content itself (TOML) or an absolute path
 * to a file containing it. World-readable locations the app can actually
 * open include /data/local/tmp (adb push, chmod 644); arbitrary /sdcard
 * paths are blocked by scoped storage — for those, pass the content inline
 * instead. Parsing, validation and application are identical to the in-app
 * Restore flow.
 */
object ConfigExtra {
    const val EXTRA = "config"

    sealed interface Result {
        /** Backup parsed, validated and saved over the current config. */
        data object Applied : Result
        /** Extra absent — nothing to do, not an error. */
        data object NoExtra : Result
        data class Error(val message: String) : Result
    }

    suspend fun apply(repository: ConfigRepository, raw: String): Result {
        val content = if (raw.startsWith("/")) {
            withContext(Dispatchers.IO) {
                try {
                    File(raw).readText()
                } catch (e: Exception) {
                    null
                }
            } ?: return Result.Error("Cannot read config file: $raw")
        } else {
            raw
        }
        val backup = BackupConfig.fromToml(content).getOrElse { error ->
            return Result.Error("Failed to parse backup: ${error.message}")
        }
        return backup.validate().fold(
            onSuccess = {
                val current = repository.configFlow.first()
                repository.saveConfig(backup.applyTo(current))
                Result.Applied
            },
            onFailure = { error ->
                Result.Error("Invalid backup: ${error.message}")
            }
        )
    }

    /**
     * Surfaces the outcome as a toast with the same wording as the in-app
     * Restore flow. Must be called on the main thread; NoExtra shows nothing.
     */
    fun toast(context: Context, result: Result) {
        val message = when (result) {
            Result.Applied -> "Configuration imported successfully" to Toast.LENGTH_SHORT
            is Result.Error -> result.message to Toast.LENGTH_LONG
            Result.NoExtra -> return
        }
        Toast.makeText(context, message.first, message.second).show()
    }
}
