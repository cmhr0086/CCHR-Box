package io.nekohasekai.sagernet.cchr

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionOperationsTest {
    @Test fun replacementAndQueuedRefreshCannotUseTheSameOldSnapshot() = runBlocking {
        val operations = SubscriptionOperations()
        val started = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        var activeGroup = "old"
        val events = mutableListOf<String>()
        val replacement = launch {
            operations.run {
                events.add("download-new")
                started.complete(Unit)
                finishDownload.await()
                activeGroup = "new"
                events.add("commit-new")
            }
        }
        started.await()
        val refresh = launch {
            operations.run { events.add("refresh-$activeGroup") }
        }
        yield()
        assertEquals(listOf("download-new"), events)
        assertEquals("old", activeGroup)
        finishDownload.complete(Unit)
        joinAll(replacement, refresh)
        assertEquals(listOf("download-new", "commit-new", "refresh-new"), events)
    }

    @Test fun cancelledDownloadReleasesTheGateForTheNextOperation() = runBlocking {
        val operations = SubscriptionOperations()
        val started = CompletableDeferred<Unit>()
        val cancelled = launch {
            operations.run { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        cancelled.cancelAndJoin()
        assertEquals("ready", withTimeout(1000) { operations.run { "ready" } })
    }
}
