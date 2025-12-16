package link.yggdrasil.yggstack.android.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import link.yggdrasil.yggstack.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

private val Context.versionDataStore: DataStore<Preferences> by preferencesDataStore(name = "version_prefs")

data class VersionInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String
)

class VersionChecker(private val context: Context) {
    private val LAST_CHECK_KEY = longPreferencesKey("last_version_check")
    private val POSTPONED_VERSION_KEY = stringPreferencesKey("postponed_version")
    
    private val CHECK_INTERVAL = TimeUnit.HOURS.toMillis(1) // 1 hour
    private val GITHUB_API_URL = "https://api.github.com/repos/DrewCyber/yggstack-android/releases/latest"
    
    suspend fun shouldCheckForUpdate(): Boolean {
        val prefs = context.versionDataStore.data.first()
        val lastCheck = prefs[LAST_CHECK_KEY] ?: 0L
        val currentTime = System.currentTimeMillis()
        return (currentTime - lastCheck) >= CHECK_INTERVAL
    }
    
    suspend fun checkForUpdate(): VersionInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i("VersionChecker", "Checking for updates from GitHub...")
            val json = URL(GITHUB_API_URL).readText()
            val jsonObj = JSONObject(json)
            
            val tagName = jsonObj.getString("tag_name").removePrefix("v")
            Log.i("VersionChecker", "Latest version on GitHub: $tagName (current: ${BuildConfig.VERSION_NAME})")
            
            val downloadUrl = jsonObj.getJSONArray("assets")
                .let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.getString("name")
                        if (name.endsWith(".apk")) {
                            return@let asset.getString("browser_download_url")
                        }
                    }
                    jsonObj.getString("html_url") // Fallback to release page
                }
            
            val releaseNotes = jsonObj.optString("body", "")
            
            // Update last check time
            context.versionDataStore.edit { prefs ->
                prefs[LAST_CHECK_KEY] = System.currentTimeMillis()
            }
            
            // Check if this is a new version
            if (isNewerVersion(tagName, BuildConfig.VERSION_NAME)) {
                Log.i("VersionChecker", "New version available: $tagName")
                // Check if user has postponed this version
                val prefs = context.versionDataStore.data.first()
                val postponedVersion = prefs[POSTPONED_VERSION_KEY]
                
                if (postponedVersion != tagName) {
                    Log.i("VersionChecker", "Showing update notification for version $tagName")
                    return@withContext VersionInfo(tagName, downloadUrl, releaseNotes)
                } else {
                    Log.i("VersionChecker", "Version $tagName was postponed by user")
                }
            } else {
                Log.i("VersionChecker", "Already running the latest version")
            }
            
            null
        } catch (e: Exception) {
            Log.e("VersionChecker", "Error checking for updates: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }
    
    suspend fun postponeVersion(version: String) {
        context.versionDataStore.edit { prefs ->
            prefs[POSTPONED_VERSION_KEY] = version
        }
    }
    
    suspend fun clearPostponedVersion() {
        context.versionDataStore.edit { prefs ->
            prefs.remove(POSTPONED_VERSION_KEY)
        }
    }
    
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        try {
            val newParts = newVersion.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }
            
            for (i in 0 until maxOf(newParts.size, currentParts.size)) {
                val newPart = newParts.getOrNull(i) ?: 0
                val currentPart = currentParts.getOrNull(i) ?: 0
                
                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }
            
            return false
        } catch (e: Exception) {
            return false
        }
    }
}
