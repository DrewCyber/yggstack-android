package io.github.yggstack.android.service

import android.os.Parcelable
import io.github.yggstack.android.data.YggstackConfig
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
    val exposeEnabled: Boolean,
    val forwardEnabled: Boolean
) : Parcelable {

    fun toYggstackConfig(): YggstackConfig {
        return YggstackConfig(
            peers = peers,
            privateKey = privateKey,
            socksProxy = socksProxy,
            dnsServer = dnsServer,
            proxyEnabled = proxyEnabled,
            exposeMappings = emptyList(), // TODO: Implement in Phase 3
            exposeEnabled = exposeEnabled,
            forwardMappings = emptyList(), // TODO: Implement in Phase 3
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
                exposeEnabled = config.exposeEnabled,
                forwardEnabled = config.forwardEnabled
            )
        }
    }
}

