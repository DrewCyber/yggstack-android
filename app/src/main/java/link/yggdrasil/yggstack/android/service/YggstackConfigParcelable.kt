package link.yggdrasil.yggstack.android.service

import android.os.Parcelable
import link.yggdrasil.yggstack.android.data.*
import kotlinx.parcelize.Parcelize

/**
 * Parcelable wrapper for YggstackConfig to pass through Intent
 */
@Parcelize
data class YggstackConfigParcelable(
    val peers: List<String>,
    val privateKey: String,
    val socksProxy: String,
    val dnsServer: String,
    val proxyEnabled: Boolean,
    val exposeMappings: List<ExposeMapping>,
    val exposeEnabled: Boolean,
    val forwardMappings: List<ForwardMapping>,
    val forwardEnabled: Boolean,
    val multicastBeacon: Boolean,
    val multicastListen: Boolean,
    val groupPasswordEnabled: Boolean,
    val groupPassword: String,
    val logLevel: String,
    val maxBackoffEnabled: Boolean,
    val maxBackoff: Int,
    val disabledPeers: List<String>,
    val powerSaveEnabled: Boolean,
    val powerSaveIdleTimeoutSeconds: Int
) : Parcelable {

    fun toYggstackConfig(): YggstackConfig {
        return YggstackConfig(
            peers = peers,
            privateKey = privateKey,
            socksProxy = socksProxy,
            dnsServer = dnsServer,
            proxyEnabled = proxyEnabled,
            exposeMappings = exposeMappings,
            exposeEnabled = exposeEnabled,
            forwardMappings = forwardMappings,
            forwardEnabled = forwardEnabled,
            multicastBeacon = multicastBeacon,
            multicastListen = multicastListen,
            groupPasswordEnabled = groupPasswordEnabled,
            groupPassword = groupPassword,
            logLevel = logLevel,
            maxBackoffEnabled = maxBackoffEnabled,
            maxBackoff = maxBackoff,
            disabledPeers = disabledPeers,
            powerSaveEnabled = powerSaveEnabled,
            powerSaveIdleTimeoutSeconds = powerSaveIdleTimeoutSeconds
        )
    }

    companion object {
        fun fromYggstackConfig(config: YggstackConfig): YggstackConfigParcelable {
            return YggstackConfigParcelable(
                peers = config.peers,
                privateKey = config.privateKey,
                socksProxy = config.socksProxy,
                dnsServer = config.dnsServer,
                proxyEnabled = config.proxyEnabled,
                exposeMappings = config.exposeMappings,
                exposeEnabled = config.exposeEnabled,
                forwardMappings = config.forwardMappings,
                forwardEnabled = config.forwardEnabled,
                multicastBeacon = config.multicastBeacon,
                multicastListen = config.multicastListen,
                groupPasswordEnabled = config.groupPasswordEnabled,
                groupPassword = config.groupPassword,
                logLevel = config.logLevel,
                maxBackoffEnabled = config.maxBackoffEnabled,
                maxBackoff = config.maxBackoff,
                disabledPeers = config.disabledPeers,
                powerSaveEnabled = config.powerSaveEnabled,
                powerSaveIdleTimeoutSeconds = config.powerSaveIdleTimeoutSeconds
            )
        }
    }
}

