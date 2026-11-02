package com.warden.blocker.ui.tour

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs saves one after another, in the order they were asked for ("skipped" must never be
 * overwritten by an older "in progress"). A save that fails (e.g. the file is locked for a
 * moment) is retried and never stops the saves after it.
 */
class SerialWriter(scope: CoroutineScope) {
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (write in writes) runWithRetry(write) }
    }

    fun write(block: suspend () -> Unit) { writes.trySend(block) }

    private suspend fun runWithRetry(write: suspend () -> Unit) {
        for (attempt in 1..5) {
            try {
                write()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Shows up in logcat as System.err.
                System.err.println("SerialWriter: attempt $attempt failed: $e")
                delay(100L * attempt)
            }
        }
    }
}
