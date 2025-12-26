package link.yggdrasil.yggstack.android.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.yggdrasil.yggstack.android.data.LowPowerModeConstants
import link.yggdrasil.yggstack.android.data.Protocol
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Proxy server that accepts connections while yggstack is stopped in low power mode
 * Queues connections and forwards them after yggstack starts
 */
class ProxySocketServer(private val scope: CoroutineScope) {
    
    private val tcpServers = mutableMapOf<Int, ServerSocket>()
    private val udpServers = mutableMapOf<Int, DatagramSocket>()
    private val serverJobs = mutableListOf<Job>()
    private val mutex = Mutex()
    
    // Queued connections waiting for yggstack to start
    private val queuedTcpConnections = ConcurrentLinkedQueue<QueuedTcpConnection>()
    private val queuedUdpPackets = ConcurrentLinkedQueue<QueuedUdpPacket>()
    
    @Volatile
    private var isListening = false
    
    @Volatile
    private var onConnectionCallback: (() -> Unit)? = null
    
    companion object {
        private const val TAG = "ProxySocketServer"
        private const val SOCKET_TIMEOUT_MS = 100
    }
    
    data class QueuedTcpConnection(
        val socket: Socket,
        val destinationPort: Int,
        val queueTime: Long = System.currentTimeMillis()
    )
    
    data class QueuedUdpPacket(
        val data: ByteArray,
        val sourceAddress: InetSocketAddress,
        val destinationPort: Int,
        val queueTime: Long = System.currentTimeMillis()
    )
    
    /**
     * Set callback to be invoked when connection is detected
     */
    fun setOnConnectionDetected(callback: () -> Unit) {
        onConnectionCallback = callback
    }
    
    /**
     * Start listening on configured ports
     */
    suspend fun startListening(
        socksProxyAddress: String? = null,
        forwardPorts: List<Triple<Int, String, Protocol>> = emptyList() // (port, localIp, protocol)
    ): Result<Unit> = mutex.withLock {
        return try {
            if (isListening) {
                return Result.failure(IllegalStateException("Already listening"))
            }
            
            // Set listening flag BEFORE starting listeners
            isListening = true
            
            // Start SOCKS proxy port if configured
            socksProxyAddress?.let { address ->
                val parts = address.split(":")
                if (parts.size == 2) {
                    val ip = parts[0]
                    val port = parts[1].toIntOrNull()
                    if (port != null) {
                        startTcpListener(port, ip)
                        Log.i(TAG, "Started TCP proxy listener on $ip:$port")
                    }
                }
            }
            
            // Start forward port listeners
            forwardPorts.forEach { (port, localIp, protocol) ->
                when (protocol) {
                    Protocol.TCP -> {
                        startTcpListener(port, localIp)
                        Log.i(TAG, "Started TCP listener on $localIp:$port")
                    }
                    Protocol.UDP -> {
                        startUdpListener(port, localIp)
                        Log.i(TAG, "Started UDP listener on $localIp:$port")
                    }
                }
            }
            
            Log.i(TAG, "ProxySocketServer started listening")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy listeners: ${e.message}", e)
            isListening = false
            stopListening()
            Result.failure(e)
        }
    }
    
    /**
     * Start TCP listener on specific port
     */
    private fun startTcpListener(port: Int, localIp: String = "127.0.0.1") {
        val serverSocket = ServerSocket().apply {
            reuseAddress = true
            soTimeout = SOCKET_TIMEOUT_MS
            bind(InetSocketAddress(localIp, port))
        }
        
        tcpServers[port] = serverSocket
        
        Log.i(TAG, "TCP listener bound to $localIp:$port, backlog=${serverSocket.receiveBufferSize}")
        
        val job = scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "TCP accept loop started for port $port")
                var acceptAttempts = 0
                while (isListening) {
                    try {
                        acceptAttempts++
                        if (acceptAttempts % 100 == 0) {
                            Log.d(TAG, "Still listening on port $port (attempts: $acceptAttempts)")
                        }
                        val socket = serverSocket.accept()
                        Log.i(TAG, "TCP connection accepted on port $port from ${socket.remoteSocketAddress}")
                        handleIncomingTcpConnection(socket, port)
                    } catch (e: SocketTimeoutException) {
                        // Expected timeout, continue
                        continue
                    } catch (e: IOException) {
                        if (isListening) {
                            Log.w(TAG, "TCP accept error on port $port: ${e.message}")
                        }
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error in accept loop on port $port: ${e.message}", e)
                        break
                    }
                }
                Log.i(TAG, "TCP accept loop exited for port $port")
            } finally {
                Log.i(TAG, "TCP listener on port $port closing")
                serverSocket.close()
            }
        }
        
        serverJobs.add(job)
    }
    
    /**
     * Start UDP listener on specific port
     */
    private fun startUdpListener(port: Int, localIp: String = "127.0.0.1") {
        val datagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = SOCKET_TIMEOUT_MS
            bind(InetSocketAddress(localIp, port))
        }
        
        udpServers[port] = datagramSocket
        
        val job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096) // Standard MTU size
            val packet = DatagramPacket(buffer, buffer.size)
            
            try {
                while (isListening) {
                    try {
                        datagramSocket.receive(packet)
                        handleIncomingUdpPacket(packet, port)
                    } catch (e: SocketTimeoutException) {
                        // Expected timeout, continue
                        continue
                    } catch (e: IOException) {
                        if (isListening) {
                            Log.w(TAG, "UDP receive error on port $port: ${e.message}")
                        }
                        break
                    }
                }
            } finally {
                datagramSocket.close()
            }
        }
        
        serverJobs.add(job)
    }
    
    /**
     * Handle incoming TCP connection
     */
    private fun handleIncomingTcpConnection(socket: Socket, port: Int) {
        Log.d(TAG, "TCP connection received on port $port from ${socket.remoteSocketAddress}")
        
        // Check queue size
        if (queuedTcpConnections.size >= LowPowerModeConstants.MAX_QUEUED_CONNECTIONS) {
            Log.w(TAG, "Connection queue full, rejecting connection")
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing rejected socket: ${e.message}")
            }
            return
        }
        
        // Queue the connection with destination port info
        queuedTcpConnections.offer(QueuedTcpConnection(socket, port))
        Log.i(TAG, "Queued TCP connection to port $port, queue size: ${queuedTcpConnections.size}")
        
        // Notify callback
        onConnectionCallback?.invoke()
    }
    
    /**
     * Handle incoming UDP packet
     */
    private fun handleIncomingUdpPacket(packet: DatagramPacket, port: Int) {
        Log.d(TAG, "UDP packet received on port $port from ${packet.socketAddress}")
        
        // Check queue size
        if (queuedUdpPackets.size >= LowPowerModeConstants.MAX_QUEUED_CONNECTIONS) {
            Log.w(TAG, "UDP packet queue full, dropping packet")
            return
        }
        
        // Copy packet data
        val data = packet.data.copyOf(packet.length)
        val sourceAddress = packet.socketAddress as InetSocketAddress
        
        queuedUdpPackets.offer(QueuedUdpPacket(data, sourceAddress, port))
        Log.i(TAG, "Queued UDP packet, queue size: ${queuedUdpPackets.size}")
        
        // Notify callback
        onConnectionCallback?.invoke()
    }
    
    /**
     * Stop listening on all ports
     */
    suspend fun stopListening() = mutex.withLock {
        if (!isListening) {
            return@withLock
        }
        
        isListening = false
        
        // Cancel all server jobs
        serverJobs.forEach { it.cancel() }
        serverJobs.clear()
        
        // Close all server sockets
        tcpServers.values.forEach { 
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing TCP server: ${e.message}")
            }
        }
        tcpServers.clear()
        
        // Close all UDP sockets
        udpServers.values.forEach { 
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing UDP server: ${e.message}")
            }
        }
        udpServers.clear()
        
        Log.i(TAG, "ProxySocketServer stopped listening")
    }
    
    /**
     * Get count of queued connections
     */
    fun getQueuedConnectionCount(): Int {
        return queuedTcpConnections.size + queuedUdpPackets.size
    }
    
    /**
     * Clear all queued connections (called if startup fails)
     */
    suspend fun clearQueue() = mutex.withLock {
        // Close all queued TCP connections
        queuedTcpConnections.forEach { queued ->
            try {
                queued.socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing queued socket: ${e.message}")
            }
        }
        queuedTcpConnections.clear()
        
        // Clear UDP packets
        queuedUdpPackets.clear()
        
        Log.i(TAG, "Cleared connection queue")
    }
    
    /**
     * Forward queued connections to actual yggstack proxy/ports
     * This is called after yggstack has started
     */
    suspend fun forwardQueuedConnections(
        socksProxyAddress: String?,
        forwardPorts: Map<Int, Pair<String, Int>> // port -> (remoteIp, remotePort)
    ): Result<Int> = mutex.withLock {
        var forwardedCount = 0
        val now = System.currentTimeMillis()
        val maxHoldTime = LowPowerModeConstants.MAX_CONNECTION_HOLD_TIME_SECONDS * 1000L
        
        // Forward TCP connections
        val tcpIterator = queuedTcpConnections.iterator()
        while (tcpIterator.hasNext()) {
            val queued = tcpIterator.next()
            
            // Check if connection has timed out
            if (now - queued.queueTime > maxHoldTime) {
                Log.w(TAG, "Queued connection expired, closing")
                try {
                    queued.socket.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing expired socket: ${e.message}")
                }
                tcpIterator.remove()
                continue
            }
            
            // Forward the connection to the appropriate destination
            scope.launch(Dispatchers.IO) {
                try {
                    // Check if this port has a forward mapping
                    val forwardMapping = forwardPorts[queued.destinationPort]
                    if (forwardMapping != null) {
                        // Forward to the mapped destination
                        val (remoteIp, remotePort) = forwardMapping
                        forwardToDestination(queued.socket, remoteIp, remotePort)
                        Log.d(TAG, "Successfully forwarded queued connection from port ${queued.destinationPort} to $remoteIp:$remotePort")
                    } else {
                        // Forward to SOCKS proxy
                        forwardTcpConnection(queued.socket, socksProxyAddress)
                        Log.d(TAG, "Successfully forwarded queued connection to SOCKS proxy")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to forward TCP connection: ${e.message}", e)
                    try {
                        queued.socket.close()
                    } catch (closeEx: Exception) {
                        // Ignore
                    }
                }
            }
            
            forwardedCount++
            tcpIterator.remove()
        }
        
        // For UDP, we can't really "forward" queued packets since yggstack handles UDP differently
        // Just clear them and let new packets go through the restarted yggstack
        val udpCount = queuedUdpPackets.size
        queuedUdpPackets.clear()
        
        Log.i(TAG, "Forwarded $forwardedCount TCP connections, cleared $udpCount UDP packets")
        return Result.success(forwardedCount)
    }
    
    /**
     * Forward a TCP connection directly to a destination
     */
    private suspend fun forwardToDestination(clientSocket: Socket, remoteIp: String, remotePort: Int) {
        val destinationSocket = Socket()
        try {
            destinationSocket.connect(InetSocketAddress(remoteIp, remotePort), 5000)
            Log.d(TAG, "Connected to destination $remoteIp:$remotePort")
            
            // Bridge the two sockets
            scope.launch(Dispatchers.IO) {
                bridgeSockets(clientSocket, destinationSocket)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to destination $remoteIp:$remotePort: ${e.message}", e)
            try {
                destinationSocket.close()
                clientSocket.close()
            } catch (closeEx: Exception) {
                // Ignore
            }
        }
    }
    
    /**
     * Forward a TCP connection to the real SOCKS proxy
     */
    private suspend fun forwardTcpConnection(clientSocket: Socket, socksProxyAddress: String?) {
        if (socksProxyAddress == null) {
            Log.w(TAG, "No SOCKS proxy configured, closing queued connection")
            clientSocket.close()
            return
        }
        
        // Parse SOCKS proxy address
        val parts = socksProxyAddress.split(":")
        if (parts.size != 2) {
            Log.e(TAG, "Invalid SOCKS proxy address: $socksProxyAddress")
            clientSocket.close()
            return
        }
        
        val proxyHost = parts[0]
        val proxyPort = parts[1].toIntOrNull() ?: run {
            Log.e(TAG, "Invalid SOCKS proxy port: ${parts[1]}")
            clientSocket.close()
            return
        }
        
        // Connect to SOCKS proxy
        val proxySocket = Socket()
        try {
            proxySocket.connect(InetSocketAddress(proxyHost, proxyPort), 5000)
            
            // Bridge the two sockets
            scope.launch(Dispatchers.IO) {
                bridgeSockets(clientSocket, proxySocket)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to SOCKS proxy: ${e.message}", e)
            try {
                proxySocket.close()
                clientSocket.close()
            } catch (closeEx: Exception) {
                // Ignore
            }
        }
    }
    
    /**
     * Bridge two sockets bidirectionally
     */
    private fun bridgeSockets(socket1: Socket, socket2: Socket) {
        try {
            val input1 = socket1.getInputStream()
            val output1 = socket1.getOutputStream()
            val input2 = socket2.getInputStream()
            val output2 = socket2.getOutputStream()
            
            val job1 = scope.launch(Dispatchers.IO) {
                try {
                    input1.copyTo(output2)
                } catch (e: IOException) {
                    // Connection closed
                } finally {
                    try { output2.flush() } catch (e: Exception) { }
                }
            }
            
            val job2 = scope.launch(Dispatchers.IO) {
                try {
                    input2.copyTo(output1)
                } catch (e: IOException) {
                    // Connection closed
                } finally {
                    try { output1.flush() } catch (e: Exception) { }
                }
            }
            
            // Wait for both directions to complete
            scope.launch {
                job1.join()
                job2.join()
                try {
                    socket1.close()
                    socket2.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error bridging sockets: ${e.message}", e)
            try {
                socket1.close()
                socket2.close()
            } catch (closeEx: Exception) {
                // Ignore
            }
        }
    }
    
    /**
     * Clean up resources
     */
    suspend fun shutdown() {
        stopListening()
        clearQueue()
    }
}
