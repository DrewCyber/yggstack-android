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
    val httpProxy: String,
    val socksEnabled: Boolean = true,
    val httpEnabled: Boolean = false,
    val dnsServer: String,
    val dnsServer2: String = "",
    val proxyEnabled: Boolean,
    val pacEnabled: Boolean,
    val pacIp: String = "127.0.0.1",
    val pacPort: Int,
    val pacAllTraffic: Boolean,
    val exposeMappings: List<ExposeMapping>,
    val exposeEnabled: Boolean,
    val forwardMappings: List<ForwardMapping>,
    val forwardEnabled: Boolean,
    val multicastBeacon: Boolean,
    val multicastListen: Boolean,
    val listenEnabled: Boolean = false,
    val listenEntries: List<ListenEntry> = emptyList(),
    val groupPasswordEnabled: Boolean,
    val groupPassword: String,
    val logLevel: String,
    val maxBackoffEnabled: Boolean,
    val maxBackoff: Int,
    val disabledPeers: List<String>,
    val powerSaveEnabled: Boolean,
    val powerSaveIdleTimeoutSeconds: Int,
    val powerSaveSleepOnPortsIdle: Boolean,
    val powerSaveWakeOnPortsActive: Boolean,
    val powerSaveSleepDuringScreenOff: Boolean,
    val powerSaveWakeOnScreenOn: Boolean
) : Parcelable {

    fun toYggstackConfig(): YggstackConfig {
        return YggstackConfig(
            peers = peers,
            privateKey = privateKey,
            socksProxy = socksProxy,
            httpProxy = httpProxy,
            socksEnabled = socksEnabled,
            httpEnabled = httpEnabled,
            dnsServer = dnsServer,
            dnsServer2 = dnsServer2,
            proxyEnabled = proxyEnabled,
            pacEnabled = pacEnabled,
            pacIp = pacIp,
            pacPort = pacPort,
            pacAllTraffic = pacAllTraffic,
            exposeMappings = exposeMappings,
            exposeEnabled = exposeEnabled,
            forwardMappings = forwardMappings,
            forwardEnabled = forwardEnabled,
            multicastBeacon = multicastBeacon,
            multicastListen = multicastListen,
            listenEnabled = listenEnabled,
            listenEntries = listenEntries,
            groupPasswordEnabled = groupPasswordEnabled,
            groupPassword = groupPassword,
            logLevel = logLevel,
            maxBackoffEnabled = maxBackoffEnabled,
            maxBackoff = maxBackoff,
            disabledPeers = disabledPeers,
            powerSaveEnabled = powerSaveEnabled,
            powerSaveIdleTimeoutSeconds = powerSaveIdleTimeoutSeconds,
            powerSaveSleepOnPortsIdle = powerSaveSleepOnPortsIdle,
            powerSaveWakeOnPortsActive = powerSaveWakeOnPortsActive,
            powerSaveSleepDuringScreenOff = powerSaveSleepDuringScreenOff,
            powerSaveWakeOnScreenOn = powerSaveWakeOnScreenOn
        )
    }

    companion object {
        fun fromYggstackConfig(config: YggstackConfig): YggstackConfigParcelable {
            return YggstackConfigParcelable(
                peers = config.peers,
                privateKey = config.privateKey,
                socksProxy = config.socksProxy,
                httpProxy = config.httpProxy,
                socksEnabled = config.socksEnabled,
                httpEnabled = config.httpEnabled,
                dnsServer = config.dnsServer,
                dnsServer2 = config.dnsServer2,
                proxyEnabled = config.proxyEnabled,
                pacEnabled = config.pacEnabled,
                pacIp = config.pacIp,
                pacPort = config.pacPort,
                pacAllTraffic = config.pacAllTraffic,
                exposeMappings = config.exposeMappings,
                exposeEnabled = config.exposeEnabled,
                forwardMappings = config.forwardMappings,
                forwardEnabled = config.forwardEnabled,
                multicastBeacon = config.multicastBeacon,
                multicastListen = config.multicastListen,
                listenEnabled = config.listenEnabled,
                listenEntries = config.listenEntries,
                groupPasswordEnabled = config.groupPasswordEnabled,
                groupPassword = config.groupPassword,
                logLevel = config.logLevel,
                maxBackoffEnabled = config.maxBackoffEnabled,
                maxBackoff = config.maxBackoff,
                disabledPeers = config.disabledPeers,
                powerSaveEnabled = config.powerSaveEnabled,
                powerSaveIdleTimeoutSeconds = config.powerSaveIdleTimeoutSeconds,
                powerSaveSleepOnPortsIdle = config.powerSaveSleepOnPortsIdle,
                powerSaveWakeOnPortsActive = config.powerSaveWakeOnPortsActive,
                powerSaveSleepDuringScreenOff = config.powerSaveSleepDuringScreenOff,
                powerSaveWakeOnScreenOn = config.powerSaveWakeOnScreenOn
            )
        }
    }
}

