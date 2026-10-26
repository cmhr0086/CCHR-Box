package io.nekohasekai.sagernet.cchr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by invite replacement, manual refresh, startup and background updates. */
internal class SubscriptionOperations {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
