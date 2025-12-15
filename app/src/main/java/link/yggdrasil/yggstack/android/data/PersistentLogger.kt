package link.yggdrasil.yggstack.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PersistentLogger(private val context: Context) {
    private val logFile: File
        get() = File(context.filesDir, "yggstack_service.log")
    
    private val maxLogSize = 5 * 1024 * 1024 // 5 MB
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    
    suspend fun appendLog(message: String) = withContext(Dispatchers.IO) {
        try {
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] $message\n"
            
            // Check file size before writing
            if (logFile.exists() && logFile.length() >= maxLogSize) {
                // Trim the log file to keep the most recent 80% of data
                trimLogFile()
            }
            
            logFile.appendText(logEntry)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    suspend fun readLogs(): List<String> = withContext(Dispatchers.IO) {
        try {
            if (!logFile.exists()) {
                return@withContext emptyList()
            }
            
            logFile.readLines().takeLast(1000) // Return last 1000 lines
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    suspend fun getLogFile(): File? = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists()) logFile else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    suspend fun getLogSize(): Long = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists()) logFile.length() else 0L
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }
    
    private fun trimLogFile() {
        try {
            val lines = logFile.readLines()
            val keepLines = (lines.size * 0.8).toInt()
            val trimmedContent = lines.takeLast(keepLines).joinToString("\n") + "\n"
            logFile.writeText(trimmedContent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
