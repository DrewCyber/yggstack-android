package link.yggdrasil.yggstack.android.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runtime "Best one" peer selection (Peers card toggle).
 *
 * While the node runs with the mode on, every ticked peer is dialed (the
 * native config carries them all), given a stability window to connect and
 * have its route cost measured by the engine, then all peers except the
 * cheapest are removed from the running core — "parked". If the survivor is
 * lost — network change, remote shutdown, anything — the parked peers are
 * re-dialed right away and the selection repeats. A network transition also
 * arrives as an explicit [Msg.NetworkChanged] (the service watches the
 * underlay), because a link can silently survive a WiFi↔cellular handover:
 * no loss is ever observed, but the measured costs no longer rank the right
 * peer. Parking is a runtime overlay: the stored config's tick set is never
 * modified, so "All" mode or the next service start simply dials everything
 * again.
 *
 * The stability window is enforced even when every candidate is already Up
 * with a measured cost: the engine's per-peer cost is an EWMA that starts
 * from the first round trip, so ranking on arrival would favor whichever
 * peer finished its first exchange first, not the actually-cheapest one.
 * Selection only ever happens at the window's settle deadline (or the hard
 * timeout), which also resets whenever a new candidate becomes measurable —
 * the window covers the slowest dial *and* the freshest measurement.
 *
 * Cost semantics (both engines expose it in the shared peers JSON): the
 * ironwood router's per-peer cost, max(1, measured RTT in ms). A peer whose
 * cost has not been measured yet reports a sentinel — huge in the Go engine,
 * 5000 in the ng engine — so candidates at or above [unmeasuredCostThreshold]
 * are treated as not yet measured and never win a selection.
 *
 * Pure Kotlin actor: the service feeds it peer snapshots and config/node
 * transitions via [post]; it answers with [Commands] (executed inline, in
 * order, on the actor coroutine) and publishes [state] for the UI. All timing
 * knobs are constructor-injectable so tests run on millisecond scales.
 */
class BestPeerSelector(
    private val scope: CoroutineScope,
    private val commands: Commands,
    private val settleMs: Long = 5_000,
    private val hardTimeoutMs: Long = 15_000,
    private val recoveryCooldownMs: Long = 30_000,
    private val unmeasuredCostThreshold: Long = 4_000,
    private val timeMs: () -> Long = System::currentTimeMillis,
) {

    /** Engine actions the selector requests. Called inline from the actor
     *  coroutine in a strict order — implementations must be quick and must
     *  not hop to another dispatcher, or add/remove could reorder. */
    interface Commands {
        fun addPeer(rawUri: String)
        fun removePeer(rawUri: String)
        fun log(message: String)
    }

    /** One peer entry from a snapshot. [uri] is query-stripped (the engine
     *  drops ?priority=… style options the config URIs may carry). */
    data class PeerSample(
        val uri: String,
        val up: Boolean,
        val cost: Long,
        val latencyMs: Long,
        val priority: Int
    )

    /** UI-facing selection state. URIs are query-stripped. */
    data class SelectionState(
        val active: Boolean = false,
        val bestUri: String? = null,
        val parkedUris: Set<String> = emptySet()
    )

    sealed interface Msg {
        data class Snapshot(val samples: List<PeerSample>) : Msg
        data class ConfigChanged(val enabled: Boolean, val candidates: List<String>) : Msg
        /** The underlay network changed (WiFi ↔ cellular, outage recovery).
         *  Measured costs are stale and the current link may have silently
         *  survived the handover, so this forces a re-dial of everything
         *  parked and a fresh selection — regardless of the recovery
         *  cooldown, and even when the survivor never went down. */
        data object NetworkChanged : Msg
        data object NodeStopped : Msg
    }

    private enum class Phase { IDLE, MEASURING, CONVERGED, RECOVERING }

    private val channel = Channel<Msg>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(SelectionState())
    val state: StateFlow<SelectionState> = _state.asStateFlow()
    private var job: Job? = null

    // ── actor-confined state ────────────────────────────────────────────────
    private var phase = Phase.IDLE
    private var candidates: List<String> = emptyList()   // raw config URIs, ticked only
    private var parked: MutableSet<String> = mutableSetOf() // raw URIs removed from the core
    private var bestRaw: String? = null                  // raw URI of the survivor
    private var samples: Map<String, PeerSample> = emptyMap() // stripped URI → latest
    private var windowStart = 0L
    private var lastUpChange = 0L
    private var lastRecoveryAt = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun post(msg: Msg) {
        channel.trySend(msg)
    }

    private suspend fun loop() {
        while (true) {
            val timeout = when (phase) {
                Phase.MEASURING -> measuringDeadline()
                Phase.RECOVERING -> recoveryDeadline()
                else -> null
            }
            val msg = if (timeout == null) channel.receive()
            else withTimeoutOrNull(timeout.coerceAtLeast(1)) { channel.receive() }
            if (msg != null) handle(msg) else onDeadline()
        }
    }

    // ── deadlines ───────────────────────────────────────────────────────────

    /** Selection is allowed once no candidate has become newly measurable
     *  (Up with a real cost) for [settleMs], and forced [hardTimeoutMs] after
     *  the window opened. The earlier of the two; both stay in the future
     *  because a settle expiry with nothing measurable resets the settle
     *  point (see [onDeadline]). */
    private fun measuringDeadline(): Long {
        val settleAt = lastUpChange + settleMs
        val hardAt = windowStart + hardTimeoutMs
        return minOf(settleAt, hardAt) - timeMs()
    }

    /** Best lost while still inside the recovery cooldown (a full re-dial
     *  happened recently): wait for the cooldown, unless the survivor's own
     *  engine retry brings it back first (handleSnapshot resumes CONVERGED). */
    private fun recoveryDeadline(): Long =
        lastRecoveryAt + recoveryCooldownMs - timeMs()

    // ── message handling ────────────────────────────────────────────────────

    private fun handle(msg: Msg) {
        when (msg) {
            is Msg.Snapshot -> handleSnapshot(msg.samples)
            is Msg.ConfigChanged -> handleConfigChanged(msg.enabled, msg.candidates)
            Msg.NetworkChanged -> if (phase != Phase.IDLE) {
                commands.log("Best one: network changed — re-selecting")
                // Bypasses the recovery cooldown deliberately: the cost basis
                // changed with the network, this is not loop churn.
                fullRecovery()
            }
            Msg.NodeStopped -> reset()
        }
    }

    private fun handleConfigChanged(enabled: Boolean, newCandidates: List<String>) {
        // Candidates that were deleted/unticked are dropped from the parked
        // set: re-adding them later would resurrect removed peers.
        candidates = newCandidates.distinctBy { it.stripped() }
        parked.retainAll(newCandidates.toSet())
        samples = emptyMap()
        if (!enabled || candidates.isEmpty()) {
            if (phase != Phase.IDLE && parked.isNotEmpty()) {
                commands.log("Best one: mode off — re-dialing ${parked.size} parked peer(s)")
                parked.forEach(commands::addPeer)
            }
            reset()
            return
        }
        when (phase) {
            Phase.IDLE -> {
                phase = Phase.MEASURING
                windowStart = timeMs()
                lastUpChange = windowStart
                // Fresh window: the first snapshot's measurables must count as
                // new (restart the settle clock), whatever the previous
                // active period saw.
                lastMeasurableCount = -1
                // A first recovery is never throttled — the cooldown clock
                // starts with this window, not with process start.
                lastRecoveryAt = windowStart - recoveryCooldownMs
                publishState()
                commands.log("Best one: measuring ${candidates.size} peer(s)")
            }
            // Already measuring or converged with (possibly) new candidates:
            // keep the phase; the next snapshot/deadline re-evaluates against
            // the new list. A converged run whose best is no longer a
            // candidate loses it on the next snapshot (best gone → recovery).
            else -> publishState()
        }
    }

    private fun handleSnapshot(newSamples: List<PeerSample>) {
        if (phase == Phase.IDLE) return
        samples = newSamples.associateBy { it.uri }
        val upKeys = samples.values.filter { it.up }.map { it.uri }.toSet()
        when (phase) {
            Phase.MEASURING -> {
                // A candidate becoming newly measurable (Up with a real cost)
                // restarts the settle timer: the window must cover the slowest
                // dial and the freshest measurement, and the EWMA cost needs
                // settle time after the first round trip before it can be
                // trusted to rank peers. Selection itself happens only at the
                // window's deadline — never directly on a snapshot.
                val measurableNow = measurableCandidates().size
                if (measurableNow > lastMeasurableCount) lastUpChange = timeMs()
                lastMeasurableCount = measurableNow
            }
            Phase.CONVERGED -> {
                val bestKey = bestRaw?.stripped()
                if (bestKey == null || bestKey !in upKeys) {
                    // Lost the survivor — any reason counts (network switch,
                    // remote shutdown). Restart from the beginning: re-dial
                    // everything parked right away (fastest path back to any
                    // connectivity) and re-select over fresh measurements —
                    // unless a full recovery just happened, in which case the
                    // cooldown throttles the loop and the survivor's own
                    // engine retry may still bring it back meanwhile.
                    commands.log("Best one: peer lost — re-selecting")
                    if (timeMs() - lastRecoveryAt >= recoveryCooldownMs) {
                        fullRecovery()
                    } else {
                        phase = Phase.RECOVERING
                        publishState()
                    }
                } else {
                    pruneStrays(upKeys)
                }
            }
            Phase.RECOVERING -> {
                val bestKey = bestRaw?.stripped()
                if (bestKey != null && bestKey in upKeys) {
                    // The survivor reconnected on its own (engine retry) while
                    // the recovery was throttled.
                    phase = Phase.CONVERGED
                    commands.log("Best one: peer reconnected")
                    publishState()
                }
            }
            Phase.IDLE -> Unit
        }
    }

    private fun onDeadline() {
        when (phase) {
            Phase.MEASURING -> {
                if (measurableCandidates().isEmpty()) {
                    // Nothing to rank yet (network dead, or costs still at the
                    // unmeasured sentinel): restart the window instead of
                    // spinning on a past deadline.
                    windowStart = timeMs()
                    lastUpChange = windowStart
                    return
                }
                select()
            }
            Phase.RECOVERING -> fullRecovery()
            else -> Unit
        }
    }

    // ── selection ───────────────────────────────────────────────────────────

    /** Candidates that are Up with a believable cost, keyed by stripped URI. */
    private fun measurableCandidates(): Map<String, PeerSample> =
        candidates.mapNotNull { raw ->
            val key = raw.stripped()
            val sample = samples[key] ?: return@mapNotNull null
            if (sample.up && isMeasured(sample)) key to sample else null
        }.toMap()

    private fun isMeasured(sample: PeerSample): Boolean =
        sample.cost in 1 until unmeasuredCostThreshold

    private fun select() {
        val measured = measurableCandidates()
        if (measured.isEmpty()) return
        val bestKey = measured.entries
            .sortedWith(
                compareBy<Map.Entry<String, PeerSample>>({ it.value.cost })
                    .thenByDescending { it.value.priority }
                    // Incumbent wins ties: don't churn the live link over noise
                    .thenBy { it.key != bestRaw?.stripped() }
                    .thenBy { it.key }
            )
            .first().key
        val winnerRaw = candidates.first { it.stripped() == bestKey }
        val losers = candidates.filter { it.stripped() != bestKey }
        if (losers.isNotEmpty()) {
            losers.forEach(commands::removePeer)
            // Parked mirrors the engine's runtime peer set: exactly the
            // candidates currently removed (a previous selection's parked
            // entries were re-added by recovery or replaced here).
            parked.clear()
            parked.addAll(losers)
        }
        bestRaw = winnerRaw
        phase = Phase.CONVERGED
        commands.log(
            "Best one: keeping ${winnerRaw.stripped()} " +
                "(cost ${measured[bestKey]?.cost} ms) — parked ${losers.size} peer(s)"
        )
        publishState()
    }

    /** A parked (removed) candidate can still appear Up briefly — the core
     *  noticed the removal after a redial race, or the peer was re-added by
     *  something else. The invariant is "only the survivor stays connected",
     *  so re-remove it. */
    private fun pruneStrays(upKeys: Set<String>) {
        val bestKey = bestRaw?.stripped() ?: return
        var pruned = false
        candidates
            .filter { it.stripped() != bestKey && it.stripped() in upKeys }
            .forEach { raw ->
                parked.add(raw)
                commands.removePeer(raw)
                pruned = true
            }
        if (pruned) publishState()
    }

    private fun fullRecovery() {
        lastRecoveryAt = timeMs()
        phase = Phase.MEASURING
        windowStart = lastRecoveryAt
        lastUpChange = lastRecoveryAt
        // Fresh window: peers re-measure one by one as they reconnect, and
        // each must restart the settle clock (not compare against the count
        // from before the loss).
        lastMeasurableCount = -1
        if (parked.isNotEmpty()) {
            commands.log("Best one: re-dialing ${parked.size} parked peer(s) to re-select")
            parked.forEach(commands::addPeer)
            // Everything is being re-dialed: no candidate is parked anymore.
            parked.clear()
        }
        publishState()
    }

    // ── misc ────────────────────────────────────────────────────────────────

    private fun reset() {
        phase = Phase.IDLE
        samples = emptyMap()
        bestRaw = null
        parked.clear()
        lastMeasurableCount = -1
        publishState()
    }

    private fun publishState() {
        _state.value = SelectionState(
            active = phase != Phase.IDLE,
            bestUri = bestRaw?.stripped()?.takeIf { phase == Phase.CONVERGED || phase == Phase.RECOVERING },
            parkedUris = parked.map { it.stripped() }.toSet()
        )
    }

    private fun String.stripped(): String = split("?")[0]

    // Settle-timer bookkeeping: count of measurable (Up + real-cost)
    // candidates at the last snapshot.
    private var lastMeasurableCount = -1
}
