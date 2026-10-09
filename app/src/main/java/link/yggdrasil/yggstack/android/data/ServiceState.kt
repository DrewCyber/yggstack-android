package link.yggdrasil.yggstack.android.data

/**
 * Service state model
 */
sealed class ServiceState {
    object Stopped : ServiceState()
    object Starting : ServiceState()
    object Running : ServiceState()
    object PowerSaving : ServiceState()
    object Stopping : ServiceState()
    data class Error(val message: String) : ServiceState()
}

/**
 * One probe result of a ping session: [rttMs] on success, [error] on failure.
 */
data class PingProbe(
    val seq: Int,
    val rttMs: Double?,
    val error: String?
)

/**
 * Live state of the internal (netstack) ping session owned by the service.
 * [doneReason] is null while the session is running, then one of
 * "completed", "stopped" or "error"; [error] carries a start-failure message.
 */
data class PingSessionState(
    val target: String,
    val running: Boolean,
    val probes: List<PingProbe>,
    val doneReason: String?,
    val error: String? = null
)

