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

/**
 * One-shot liveness verdict for a forward mapping's remote service, from
 * the engine's netstack probe. Drives the alive dot on forward cards.
 */
enum class PortCheckState {
    /** The remote service answered: TCP handshake completed / UDP reply. */
    ALIVE,

    /** The remote side answered RST — nothing listens on that port (TCP only). */
    DEAD,

    /** UDP probe got no reply — inconclusive in UDP, never shown as dead. */
    NO_ANSWER
}

/** Stable key identifying a forward mapping in the port-check results. */
fun ForwardMapping.portCheckKey(): String =
    "${protocol.name}|$localIp:$localPort|[$remoteIp]:$remotePort"

/** Stable key identifying an expose mapping in the port-check results.
 *  Prefixed to never collide with forward keys. */
fun ExposeMapping.portCheckKey(): String =
    "expose|${protocol.name}|$localIp:$localPort|ygg:$yggPort"

