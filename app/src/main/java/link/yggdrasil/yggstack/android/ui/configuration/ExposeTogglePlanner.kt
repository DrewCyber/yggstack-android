package link.yggdrasil.yggstack.android.ui.configuration

import link.yggdrasil.yggstack.android.data.ExposeMapping
import link.yggdrasil.yggstack.android.data.YggstackConfig
import link.yggdrasil.yggstack.android.service.ExposeMappingResult

internal data class ExposeTogglePlan(
    val config: YggstackConfig?,
    val error: String?
)

/**
 * Pure state transition for a live Expose toggle. The caller applies the
 * engine-side add/remove first; this function only decides whether the UI
 * config may be persisted and which sibling rules must be disabled.
 *
 * Keeping this pure makes the critical rule testable: a failed live bind
 * must never leave an enabled toggle in the persisted config.
 */
internal fun planExposeToggle(
    config: YggstackConfig,
    mapping: ExposeMapping,
    liveResult: ExposeMappingResult
): ExposeTogglePlan {
    if (!liveResult.ok) {
        return ExposeTogglePlan(
            config = null,
            error = liveResult.message ?: "expose failed"
        )
    }
    val newEnabled = !mapping.enabled
    val updated = config.exposeMappings.map { candidate ->
        when {
            candidate == mapping -> candidate.copy(enabled = newEnabled)
            newEnabled &&
                candidate.protocol == mapping.protocol &&
                candidate.localIp == mapping.localIp &&
                candidate.localPort == mapping.localPort &&
                candidate.enabled -> candidate.copy(enabled = false)
            else -> candidate
        }
    }
    return ExposeTogglePlan(config = config.copy(exposeMappings = updated), error = null)
}
