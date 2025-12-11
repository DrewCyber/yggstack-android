package io.github.yggstack.android.service

import android.os.Parcelable
import io.github.yggstack.android.data.*
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
    val forwardEnabled: Boolean
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
            forwardEnabled = forwardEnabled
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
                forwardEnabled = config.forwardEnabled
            )
        }
    }
}

