package link.yggdrasil.yggstack.android.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.yggdrasil.yggstack.android.data.LowPowerModeConstants
import link.yggdrasil.yggstack.android.data.Protocol
import link.yggdrasil.yggstack.android.data.YggstackConfig

/**
 * Manages low power mode state and coordinates transitions between full power and low power states
 */
class LowPowerModeManager(
    private val connectionMonitor: ConnectionMonitor,
    private val proxyServer: ProxySocketServer,
    private val scope: CoroutineScope
) {
    
    private val mutex = Mutex()
    private var idleCheckJob: Job? = null
    private var timeoutSeconds: Int = LowPowerModeConstants.DEFAULT_TIMEOUT_SECONDS
    private var config: YggstackConfig? = null
    private var isEnabled: Boolean = false
    private var isInLowPower: Boolean = false
    
    // Callbacks for state transitions
    private var onStartNode: (suspend () -> Unit)? = null
    private var onStopNode: (suspend () -> Unit)? = null
    private var onStateChanged: ((Boolean) -> Unit)? = null
    
    companion object {
        private const val TAG = "LowPowerModeManager"
    }
    
    /**
     * Enable low power mode with specified timeout
     */
    suspend fun enable(timeoutSeconds: Int, config: YggstackConfig) = mutex.withLock {
        if (isEnabled) {
            Log.w(TAG, "Low power mode already enabled")
            return@withLock
        }
        
        this.timeoutSeconds = timeoutSeconds
        this.config = config // Save config for later use
        this.isEnabled = true
        
        Log.i(TAG, "Low power mode enabled with ${timeoutSeconds}s timeout")
        
        // Configure proxy server to detect connections
        proxyServer.setOnConnectionDetected {
            scope.launch {
                onConnectionDetected()
            }
        }
        
        // Start idle timeout monitoring
        startIdleMonitoring()
    }
    
    /**
     * Disable low power mode
     */
    suspend fun disable() = mutex.withLock {
        if (!isEnabled) {
            return@withLock
        }
        
        isEnabled = false
        
        // Stop idle monitoring
        stopIdleMonitoring()
        
        // If currently in low power state, transition back to full power
        if (isInLowPower) {
            transitionToFullPower()
        }
        
        Log.i(TAG, "Low power mode disabled")
    }
    
    /**
     * Start periodic idle timeout checking
     */
    private fun startIdleMonitoring() {
        stopIdleMonitoring() // Ensure no duplicate jobs
        
        idleCheckJob = scope.launch {
            while (isActive && isEnabled) {
                delay(LowPowerModeConstants.IDLE_CHECK_INTERVAL_SECONDS * 1000L)
                
                try {
                    checkIdleTimeout()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking idle timeout: ${e.message}", e)
                }
            }
        }
        
        Log.d(TAG, "Started idle timeout monitoring")
    }
    
    /**
     * Stop idle timeout monitoring
     */
    private fun stopIdleMonitoring() {
        idleCheckJob?.cancel()
        idleCheckJob = null
        Log.d(TAG, "Stopped idle timeout monitoring")
    }
    
    /**
     * Check if node should stop due to idle timeout
     */
    private suspend fun checkIdleTimeout() {
        if (!isEnabled || isInLowPower) {
            return
        }
        
        val shouldStop = connectionMonitor.shouldStopNode(timeoutSeconds)
        
        if (shouldStop) {
            val idleTime = connectionMonitor.getIdleTimeSeconds()
            Log.i(TAG, "Idle timeout reached ($idleTime s >= $timeoutSeconds s), transitioning to low power")
            transitionToLowPower()
        }
    }
    
    /**
     * Transition from full power to low power (stop node, start proxy)
     */
    suspend fun transitionToLowPower() = mutex.withLock {
        if (isInLowPower) {
            Log.w(TAG, "Already in low power state")
            return@withLock
        }
        
        Log.i(TAG, "Transitioning to low power state")
        
        try {
            // Stop yggstack node
            onStopNode?.invoke()
            
            // Start proxy server on configured ports
            val cfg = config
            if (cfg != null) {
                val result = startProxyListening(cfg)
                result.onFailure { e ->
                    Log.e(TAG, "Failed to start proxy listeners: ${e.message}", e)
                    throw e
                }
                Log.i(TAG, "Proxy server started successfully")
            } else {
                Log.w(TAG, "No config available to start proxy")
            }
            
            isInLowPower = true
            onStateChanged?.invoke(true)
            
            Log.i(TAG, "Successfully transitioned to low power state")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transition to low power: ${e.message}", e)
            isInLowPower = false
            throw e
        }
    }
    
    /**
     * Transition from low power to full power (stop proxy, start node)
     */
    suspend fun transitionToFullPower() = mutex.withLock {
        if (!isInLowPower) {
            Log.w(TAG, "Not in low power state")
            return@withLock
        }
        
        Log.i(TAG, "Transitioning to full power state")
        
        try {
            // Stop proxy server
            proxyServer.stopListening()
            Log.i(TAG, "Proxy server stopped")
            
            // Start yggstack node
            Log.i(TAG, "Calling onStartNode callback")
            onStartNode?.invoke()
            Log.i(TAG, "onStartNode callback completed")
            
            // Reset idle timer
            connectionMonitor.resetIdleTimer()
            
            isInLowPower = false
            onStateChanged?.invoke(false)
            
            Log.i(TAG, "Successfully transitioned to full power state")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transition to full power: ${e.message}", e)
            // Try to recover by clearing queue
            proxyServer.clearQueue()
            throw e
        }
    }
    
    /**
     * Handle connection detected while in low power mode
     */
    private suspend fun onConnectionDetected() {
        if (!isInLowPower) {
            Log.w(TAG, "Connection detected but not in low power state")
            return
        }
        
        Log.i(TAG, "Connection detected in low power mode, starting node")
        
        try {
            transitionToFullPower()
            
            // After node starts, forward queued connections
            // Wait a bit for node to be fully ready
            delay(1000)
            
            // Get config values for forwarding
            val cfg = config
            val socksProxyAddress = if (cfg?.proxyEnabled == true && cfg.socksProxy.isNotEmpty()) {
                cfg.socksProxy
            } else null
            
            val forwardPorts = if (cfg?.forwardEnabled == true) {
                cfg.forwardMappings.associate { 
                    it.localPort to (it.remoteIp to it.remotePort)
                }
            } else emptyMap()
            
            val forwardResult = proxyServer.forwardQueuedConnections(
                socksProxyAddress = socksProxyAddress,
                forwardPorts = forwardPorts
            )
            
            forwardResult.fold(
                onSuccess = { count ->
                    Log.i(TAG, "Forwarded $count queued connections")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to forward connections: ${error.message}", error)
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection detection: ${e.message}", e)
            // Clear queue if forwarding failed
            proxyServer.clearQueue()
        }
    }
    
    /**
     * Start proxy listening with configuration
     */
    suspend fun startProxyListening(config: YggstackConfig): Result<Unit> {
        val socksProxyAddress = if (config.proxyEnabled && config.socksProxy.isNotEmpty()) {
            config.socksProxy
        } else null
        
        val forwardPorts = if (config.forwardEnabled) {
            config.forwardMappings.map { 
                Triple(it.localPort, it.localIp, it.protocol)
            }
        } else emptyList()
        
        return proxyServer.startListening(socksProxyAddress, forwardPorts)
    }
    
    /**
     * Forward queued connections with configuration
     */
    suspend fun forwardQueuedConnectionsWithConfig(config: YggstackConfig): Result<Int> {
        val socksAddress = if (config.proxyEnabled) config.socksProxy else null
        
        val forwardPorts = if (config.forwardEnabled) {
            config.forwardMappings.associate { 
                it.localPort to (it.remoteIp to it.remotePort)
            }
        } else emptyMap()
        
        return proxyServer.forwardQueuedConnections(socksAddress, forwardPorts)
    }
    
    /**
     * Set callback for starting node
     */
    fun setOnStartNode(callback: suspend () -> Unit) {
        onStartNode = callback
    }
    
    /**
     * Set callback for stopping node
     */
    fun setOnStopNode(callback: suspend () -> Unit) {
        onStopNode = callback
    }
    
    /**
     * Set callback for state changes
     */
    fun setOnStateChanged(callback: (Boolean) -> Unit) {
        onStateChanged = callback
    }
    
    /**
     * Check if low power mode is enabled
     */
    fun isEnabled(): Boolean = isEnabled
    
    /**
     * Check if currently in low power state
     */
    fun isInLowPowerState(): Boolean = isInLowPower
    
    /**
     * Get active connection count
     */
    fun getActiveConnectionCount(): Int {
        return connectionMonitor.getActiveConnectionCount()
    }
    
    /**
     * Get idle time in seconds
     */
    fun getIdleTimeSeconds(): Long {
        return connectionMonitor.getIdleTimeSeconds()
    }
    
    /**
     * Get queued connection count
     */
    fun getQueuedConnectionCount(): Int {
        return proxyServer.getQueuedConnectionCount()
    }
    
    /**
     * Get timeout value
     */
    fun getTimeoutSeconds(): Int = timeoutSeconds
    
    /**
     * Get time remaining until auto-stop (in seconds), or null if not applicable
     */
    fun getTimeUntilStopSeconds(): Long? {
        if (!isEnabled || isInLowPower || connectionMonitor.getActiveConnectionCount() > 0) {
            return null
        }
        
        val idleTime = connectionMonitor.getIdleTimeSeconds()
        val remaining = timeoutSeconds - idleTime
        return if (remaining > 0) remaining else 0
    }
    
    /**
     * Shutdown and clean up
     */
    suspend fun shutdown() {
        disable()
        proxyServer.shutdown()
    }
}
