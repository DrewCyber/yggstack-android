package link.yggdrasil.yggstack.android.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BestPeerSelector state machine on millisecond-scale timing knobs. Commands
 * are recorded in order; assertions wait on the published state (commands for
 * a transition are issued before that transition becomes visible).
 */
class BestPeerSelectorTest {

    // Compressed production timings: settle 60ms, hard 200ms, cooldown
    // 250ms, unmeasured-cost threshold 4000ms (production value).
    private val settle = 60L
    private val hard = 200L
    private val cooldown = 250L

    private class Recorder : BestPeerSelector.Commands {
        val adds = mutableListOf<String>()
        val removes = mutableListOf<String>()
        val logs = mutableListOf<String>()
        override fun addPeer(rawUri: String) { adds += rawUri }
        override fun removePeer(rawUri: String) { removes += rawUri }
        override fun log(message: String) { logs += message }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = Recorder()
    private val selector = BestPeerSelector(
        scope = scope,
        commands = recorder,
        settleMs = settle,
        hardTimeoutMs = hard,
        recoveryCooldownMs = cooldown,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun sample(uri: String, up: Boolean = true, cost: Long = 50, priority: Int = 0) =
        BestPeerSelector.PeerSample(uri = uri, up = up, cost = cost, latencyMs = cost, priority = priority)

    /** Wait for the published state to satisfy [predicate]; fails the test on timeout. */
    private suspend fun awaitState(predicate: (BestPeerSelector.SelectionState) -> Boolean) {
        withTimeout(5_000) { selector.state.first { predicate(it) } }
    }

    @Test
    fun selectsCheapestAndParksTheRest() = kotlinx.coroutines.runBlocking {
        selector.start()
        selector.post(
            BestPeerSelector.Msg.ConfigChanged(
                enabled = true,
                candidates = listOf("tcp://a:1", "tcp://b:2", "tcp://c:3")
            )
        )
        // Slow peer c never connects; a and b come up staggered, each Up
        // transition restarting the settle window.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(sample("tcp://a:1", cost = 50))))
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 20)
        )))
        awaitState { it.active && it.bestUri == "tcp://b:2" }
        assertEquals(setOf("tcp://a:1", "tcp://c:3"), selector.state.value.parkedUris)
        // All non-best candidates are parked, c included: the engine's retry
        // of a configured-but-down peer is exactly the traffic to stop.
        assertEquals(listOf("tcp://a:1", "tcp://c:3"), recorder.removes)
        assertTrue(recorder.adds.isEmpty())
    }

    @Test
    fun unmeasuredPeerNeverWinsSelection() = kotlinx.coroutines.runBlocking {
        selector.start()
        selector.post(
            BestPeerSelector.Msg.ConfigChanged(
                enabled = true,
                candidates = listOf("tcp://slow:9", "tcp://fresh:1")
            )
        )
        // fresh just connected: ng-engine unmeasured sentinel (cost 5000) or
        // Go's huge sentinel — either way at/above the threshold.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://slow:9", cost = 5000),
            sample("tcp://fresh:1", cost = 4_290_000L)
        )))
        // Past the hard timeout nothing is measurable: the window restarts
        // instead of selecting, and nobody is removed.
        kotlinx.coroutines.delay(hard + settle + 50)
        assertNull(selector.state.value.bestUri)
        assertTrue(recorder.removes.isEmpty())
        // The measurement arrives; now selection can proceed.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://slow:9", cost = 5000),
            sample("tcp://fresh:1", cost = 30)
        )))
        awaitState { it.bestUri == "tcp://fresh:1" }
        assertEquals(listOf("tcp://slow:9"), recorder.removes)
    }

    @Test
    fun selectionAlwaysWaitsForTheSettleWindow() = kotlinx.coroutines.runBlocking {
        selector.start()
        selector.post(
            BestPeerSelector.Msg.ConfigChanged(
                enabled = true,
                candidates = listOf("tcp://a:1", "tcp://b:2")
            )
        )
        // Service restart shape: everyone is already Up AND measured in the
        // very first snapshot (costs arrive within ~1 RTT of connect). The
        // selection must still wait out the settle window — ranking on the
        // first EWMA sample would favor whichever peer finished its first
        // exchange first, not the actually-cheapest one.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        kotlinx.coroutines.delay(settle / 4)
        assertNull(selector.state.value.bestUri)
        assertTrue(recorder.removes.isEmpty())
        awaitState { it.bestUri == "tcp://b:2" }
    }

    @Test
    fun singleSnapshotAtArmIsEnoughToSelect() = kotlinx.coroutines.runBlocking {
        // The live ALL→BEST toggle on a steady node: after arming, exactly
        // one snapshot arrives (the service's arm-time kick) and then
        // nothing — the peer set is stable, so the engine callback stays
        // silent. The window must still run to completion and select.
        selector.start()
        selector.post(
            BestPeerSelector.Msg.ConfigChanged(
                enabled = true,
                candidates = listOf("tcp://a:1", "tcp://b:2", "tcp://c:3")
            )
        )
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50),
            sample("tcp://b:2", cost = 10),
            sample("tcp://c:3", cost = 30)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        assertEquals(listOf("tcp://a:1", "tcp://c:3"), recorder.removes)
        assertTrue(recorder.adds.isEmpty())
    }

    @Test
    fun bestLossReDialsParkedAndReSelects() = kotlinx.coroutines.runBlocking {
        selector.start()
        val candidates = listOf("tcp://a:1", "tcp://b:2", "tcp://c:3")
        selector.post(BestPeerSelector.Msg.ConfigChanged(enabled = true, candidates = candidates))
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 20), sample("tcp://c:3", cost = 40)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        val removesAfterFirstSelection = recorder.removes.size
        // Best drops (network switch, remote shutdown — any reason). The
        // parked peers are re-dialed right away (fastest path back to any
        // connectivity), then the selection repeats over fresh measurements.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(sample("tcp://a:1", cost = 50))))
        awaitState { it.bestUri != "tcp://b:2" && it.bestUri != null }
        assertEquals("tcp://a:1", selector.state.value.bestUri)
        // Parked peers were re-dialed before the second selection.
        assertTrue(recorder.adds.containsAll(listOf("tcp://a:1", "tcp://c:3")))
        assertTrue(recorder.removes.size > removesAfterFirstSelection)
    }

    @Test
    fun bestLossTriggersImmediateReDial() = kotlinx.coroutines.runBlocking {
        selector.start()
        selector.post(
            BestPeerSelector.Msg.ConfigChanged(
                enabled = true,
                candidates = listOf("tcp://a:1", "tcp://b:2")
            )
        )
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        // Best drops: the re-dial must happen immediately with the loss
        // snapshot, not after any grace delay. Recovery clears bestUri while
        // measuring; commands precede the state publication, so observing the
        // state implies the add was issued.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(sample("tcp://a:1", cost = 50))))
        awaitState { it.active && it.bestUri == null }
        assertEquals(listOf("tcp://a:1"), recorder.adds)
        // Everything reconnects; after the settle window a new best is picked
        // (b again — cheapest of the fresh measurements).
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 12)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
    }

    @Test
    fun bestReturnDuringThrottledRecoveryResumesConverged() = kotlinx.coroutines.runBlocking {
        selector.start()
        val candidates = listOf("tcp://a:1", "tcp://b:2")
        selector.post(BestPeerSelector.Msg.ConfigChanged(enabled = true, candidates = candidates))
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        // First loss: immediate recovery re-dials a, a wins the re-selection.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(sample("tcp://a:1", cost = 50))))
        awaitState { it.bestUri == "tcp://a:1" }
        assertEquals(1, recorder.adds.size)
        // Second loss right after: inside the cooldown, so the re-dial is
        // throttled — but the survivor's own engine retry brings it back,
        // resuming CONVERGED without another dial.
        selector.post(BestPeerSelector.Msg.Snapshot(emptyList()))
        kotlinx.coroutines.delay(settle / 2)
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(sample("tcp://a:1", cost = 50))))
        kotlinx.coroutines.delay(settle)
        assertEquals("tcp://a:1", selector.state.value.bestUri)
        assertEquals(1, recorder.adds.size) // no re-dial happened
    }

    @Test
    fun throttledRecoveryFiresAtTheCooldownBoundary() = kotlinx.coroutines.runBlocking {
        selector.start()
        val candidates = listOf("tcp://a:1", "tcp://b:2")
        selector.post(BestPeerSelector.Msg.ConfigChanged(enabled = true, candidates = candidates))
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        // First loss → immediate recovery (a re-dialed, then selected).
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(sample("tcp://a:1", cost = 50))))
        awaitState { it.bestUri == "tcp://a:1" }
        assertEquals(1, recorder.adds.size)
        // Second loss stays down: throttled now, re-dial at the cooldown
        // boundary.
        selector.post(BestPeerSelector.Msg.Snapshot(emptyList()))
        kotlinx.coroutines.delay(cooldown / 3)
        assertEquals(1, recorder.adds.size) // still throttled
        kotlinx.coroutines.delay(cooldown + hard)
        awaitState { recorder.adds.size >= 2 }
        assertTrue(recorder.adds.count { it == "tcp://b:2" } >= 1)
    }

    @Test
    fun networkChangeForcesReSelectionEvenWithSurvivingBest() = kotlinx.coroutines.runBlocking {
        // Cellular → WiFi where the surviving link never went down: no peer
        // loss is ever observed, but the service's network notification must
        // still re-dial the parked peers and re-select over fresh costs.
        selector.start()
        selector.post(
            BestPeerSelector.Msg.ConfigChanged(
                enabled = true,
                candidates = listOf("tcp://a:1", "tcp://b:2")
            )
        )
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        assertEquals(listOf("tcp://a:1"), recorder.removes)
        // Network changes; the WiFi-side peer is now the cheaper one.
        selector.post(BestPeerSelector.Msg.NetworkChanged)
        awaitState { it.active && it.bestUri == null } // fresh measuring window
        assertEquals(listOf("tcp://a:1"), recorder.adds) // parked re-dialed
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 20), sample("tcp://b:2", cost = 80)
        )))
        awaitState { it.bestUri == "tcp://a:1" }
    }

    @Test
    fun networkChangeBreaksTheRecoveryThrottle() = kotlinx.coroutines.runBlocking {
        selector.start()
        val candidates = listOf("tcp://a:1", "tcp://b:2")
        selector.post(BestPeerSelector.Msg.ConfigChanged(enabled = true, candidates = candidates))
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        // First loss → immediate recovery, a wins the re-selection.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(sample("tcp://a:1", cost = 50))))
        awaitState { it.bestUri == "tcp://a:1" }
        assertEquals(1, recorder.adds.size)
        // Second loss right after would be throttled by the cooldown…
        selector.post(BestPeerSelector.Msg.Snapshot(emptyList()))
        kotlinx.coroutines.delay(settle / 2)
        assertEquals(1, recorder.adds.size)
        // …but a network change is not loop churn: it re-dials immediately.
        selector.post(BestPeerSelector.Msg.NetworkChanged)
        awaitState { recorder.adds.size >= 2 }
        assertTrue(recorder.adds.count { it == "tcp://b:2" } >= 1)
    }

    @Test
    fun strayConnectedCandidateIsReParked() = kotlinx.coroutines.runBlocking {
        selector.start()
        selector.post(
            BestPeerSelector.Msg.ConfigChanged(
                enabled = true,
                candidates = listOf("tcp://a:1", "tcp://b:2")
            )
        )
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        assertEquals(1, recorder.removes.size)
        // a briefly shows Up again (removal raced its redial): re-parked.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 11)
        )))
        awaitState { it.parkedUris.contains("tcp://a:1") }
        assertEquals(2, recorder.removes.filter { it == "tcp://a:1" }.size)
        assertEquals("tcp://b:2", selector.state.value.bestUri)
    }

    @Test
    fun disablingReDialsParkedAndGoesIdle() = kotlinx.coroutines.runBlocking {
        selector.start()
        val candidates = listOf("tcp://a:1", "tcp://b:2")
        selector.post(BestPeerSelector.Msg.ConfigChanged(enabled = true, candidates = candidates))
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        selector.post(BestPeerSelector.Msg.ConfigChanged(enabled = false, candidates = candidates))
        awaitState { !it.active }
        assertNull(selector.state.value.bestUri)
        assertTrue(selector.state.value.parkedUris.isEmpty())
        assertEquals(listOf("tcp://a:1"), recorder.adds) // parked peer re-dialed
    }

    @Test
    fun nodeStopClearsStateWithoutCommands() = kotlinx.coroutines.runBlocking {
        selector.start()
        val candidates = listOf("tcp://a:1", "tcp://b:2")
        selector.post(BestPeerSelector.Msg.ConfigChanged(enabled = true, candidates = candidates))
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 50), sample("tcp://b:2", cost = 10)
        )))
        awaitState { it.bestUri == "tcp://b:2" }
        selector.post(BestPeerSelector.Msg.NodeStopped)
        awaitState { !it.active }
        // No add commands: the node is going down, the next start dials the
        // config's peers on its own.
        assertTrue(recorder.adds.isEmpty())
    }

    @Test
    fun priorityBreaksCostTiesInFavorOfTheConfiguredPreference() = kotlinx.coroutines.runBlocking {
        selector.start()
        selector.post(
            BestPeerSelector.Msg.ConfigChanged(
                enabled = true,
                candidates = listOf("tcp://a:1", "tcp://b:2")
            )
        )
        // Equal cost, but a carries ?priority=… (reported by the engine as
        // Priority): the explicit user preference wins.
        selector.post(BestPeerSelector.Msg.Snapshot(listOf(
            sample("tcp://a:1", cost = 40, priority = 5),
            sample("tcp://b:2", cost = 40, priority = 0)
        )))
        awaitState { it.bestUri == "tcp://a:1" }
    }
}
