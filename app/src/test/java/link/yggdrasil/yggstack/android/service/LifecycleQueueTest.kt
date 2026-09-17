package link.yggdrasil.yggstack.android.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LifecycleQueueTest {
    private fun CountDownLatch.awaitCompletion() {
        assertTrue("Lifecycle operation did not finish", await(5, TimeUnit.SECONDS))
    }

    @Test
    fun stopDuringBlockedStartIsNotDropped() {
        val queue = LifecycleQueue()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val running = AtomicBoolean(false)
        val session = queue.session({}, { throw AssertionError(it) })
        session.submit(acquire = true) {
            started.countDown()
            release.awaitCompletion()
            running.set(true)
        }
        started.awaitCompletion()
        session.submit {
            assertTrue(running.get())
            running.set(false)
            stopped.countDown()
        }
        try {
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        stopped.awaitCompletion()
        assertFalse(running.get())
    }

    @Test
    fun destructionReturnsBeforeNativeCleanupAndBlocksReplacement() {
        val queue = LifecycleQueue()
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val session = queue.session({
            cleanupStarted.countDown()
            releaseCleanup.awaitCompletion()
        }, { throw AssertionError(it) })
        session.submit(acquire = true) { acquired.countDown() }
        acquired.awaitCompletion()
        session.destroy()
        cleanupStarted.awaitCompletion()
        val replacement = queue.session({}, { throw AssertionError(it) })
        replacement.submit(acquire = true) { replacementStarted.countDown() }
        try {
            assertFalse(replacementStarted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseCleanup.countDown()
        }
        replacementStarted.awaitCompletion()
    }

    @Test
    fun destructionDuringStartPreventsLatePublicationAndQueuedWake() {
        val queue = LifecycleQueue()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val running = AtomicBoolean(false)
        val session = queue.session({ cleaned.countDown() }, { throw AssertionError(it) })
        session.submit(acquire = true) {
            started.countDown()
            release.awaitCompletion()
            session.publish { running.set(true) }
        }
        started.awaitCompletion()
        session.submit { running.set(true) }
        session.destroy()
        release.countDown()
        cleaned.awaitCompletion()
        assertFalse(running.get())
    }

    @Test
    fun repeatedIdleWakeCommandsPreserveSessionUntilFullStop() {
        val queue = LifecycleQueue()
        val done = CountDownLatch(1)
        val failures = AtomicInteger()
        val session = queue.session({}, { failures.incrementAndGet() })
        var running = false
        var active = false
        var placeholders = false
        session.submit(acquire = true) { running = true; active = true }
        repeat(25) {
            session.submit {
                check(running && active && !placeholders)
                running = false
                placeholders = true
            }
            session.submit {
                check(!running && active && placeholders)
                placeholders = false
                running = true
            }
        }
        session.submit {
            running = false
            active = false
            placeholders = false
            done.countDown()
        }
        done.awaitCompletion()
        assertEquals(0, failures.get())
        assertFalse(running || active || placeholders)
    }

    @Test
    fun failedCleanupRetainsOwnershipAndConsumerSurvives() {
        val queue = LifecycleQueue()
        val failure = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val replacementRan = AtomicBoolean(false)
        val session = queue.session({ error("native stop failed") }, {})
        session.submit(acquire = true) { acquired.countDown() }
        acquired.awaitCompletion()
        val replacement = queue.session({}, { failure.countDown() })
        replacement.submit(acquire = true) { replacementRan.set(true) }
        failure.awaitCompletion()
        val observer = queue.session({}, { drained.countDown() })
        observer.submit(acquire = true) { replacementRan.set(true) }
        drained.awaitCompletion()
        assertFalse(replacementRan.get())
    }

    @Test
    fun lateDestructionOfReplacedSessionDoesNotCleanUpNewOwner() {
        val queue = LifecycleQueue()
        val cleaned = AtomicInteger()
        val oldAcquired = CountDownLatch(1)
        val newAcquired = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val old = queue.session({ cleaned.incrementAndGet() }, {})
        old.submit(acquire = true) { oldAcquired.countDown() }
        oldAcquired.awaitCompletion()
        val replacement = queue.session({}, {})
        replacement.submit(acquire = true) { newAcquired.countDown() }
        newAcquired.awaitCompletion()
        old.destroy()
        replacement.submit { drained.countDown() }
        drained.awaitCompletion()
        assertEquals(1, cleaned.get())
    }
}
