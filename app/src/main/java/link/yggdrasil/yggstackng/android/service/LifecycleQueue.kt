package link.yggdrasil.yggstackng.android.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Process-owned FIFO. Never cancelled with a Service: blocking JNI must finish before
 * another command can touch the node or bind replacement listeners. No timeout can
 * establish that a native stop finished. A throwing cleanup retains ownership (fail closed).
 */
internal class LifecycleQueue {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var owner: Session? = null // accessed only by the consumer

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (command in commands) command()
        }
    }

    fun session(cleanup: suspend () -> Unit, failure: (Throwable) -> Unit): Session =
        Session(cleanup, failure)

    inner class Session internal constructor(
        private val cleanup: suspend () -> Unit,
        private val failure: (Throwable) -> Unit
    ) {
        private val publicationLock = Any()
        private var cleaned = false

        private suspend fun cleanupOnce() {
            if (cleaned) return
            cleanup()
            cleaned = true
        }
        @Volatile var isDestroyed = false
            private set

        /** Linearizes publication with destruction; never put JNI or suspension here. */
        fun publish(action: () -> Unit) = synchronized(publicationLock) {
            if (!isDestroyed) action()
        }

        fun submit(acquire: Boolean = false, action: suspend () -> Unit) {
            synchronized(publicationLock) {
                if (isDestroyed) return
                enqueue {
                    if (isDestroyed) return@enqueue
                    if (acquire && owner !== this) {
                        owner?.let { previous ->
                            previous.destroy()
                            previous.cleanupOnce()
                        }
                        owner = this
                    }
                    if (owner === this || owner == null) action()
                }
            }
        }

        /** Invalidate synchronously, then queue teardown behind any in-flight native start. */
        fun destroy(invalidate: () -> Unit = {}) {
            synchronized(publicationLock) {
                if (isDestroyed) return
                isDestroyed = true
                invalidate()
                enqueue {
                    cleanupOnce()
                    if (owner === this) owner = null
                }
            }
        }

        private fun enqueue(action: suspend () -> Unit) {
            check(commands.trySend {
                try {
                    action()
                } catch (error: Throwable) {
                    // One failed operation must not kill the process lifecycle consumer.
                    try { failure(error) } catch (_: Throwable) { }
                }
            }.isSuccess)
        }
    }
}
