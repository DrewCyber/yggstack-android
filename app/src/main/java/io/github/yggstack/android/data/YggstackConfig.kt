package io.github.yggstack.android.data

import kotlinx.serialization.Serializable

/**
 * Configuration model for Yggstack
 */
@Serializable
data class YggstackConfig(
    val peers: List<String> = emptyList(),
    val privateKey: String = "",
    val socksProxy: String = "",
    val dnsServer: String = "",
    val proxyEnabled: Boolean = true,
    val exposeMappings: List<ExposeMapping> = emptyList(),
    val exposeEnabled: Boolean = true,
    val forwardMappings: List<ForwardMapping> = emptyList(),
    val forwardEnabled: Boolean = true
)

/**
 * Mapping for exposing local ports to Yggdrasil network
 */
@Serializable
data class ExposeMapping(
    val protocol: Protocol,
    val localPort: Int,
    val localIp: String = "127.0.0.1",
    val yggPort: Int
)

/**
 * Mapping for forwarding remote Yggdrasil ports to local
 */
@Serializable
data class ForwardMapping(
    val protocol: Protocol,
    val localIp: String,
    val localPort: Int,
    val remoteIp: String,
    val remotePort: Int
)

/**
 * Network protocol type
 */
@Serializable
enum class Protocol {
    TCP, UDP
}

