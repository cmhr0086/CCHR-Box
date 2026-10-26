package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test

class SubscriptionReconciliationTest {
    private data class Node(val id: Long, val name: String)

    @Test fun removesExistingDuplicatesAndObsoleteNodesWhileKeepingSelectedId() {
        val rows = listOf(Node(1, "A"), Node(2, "A"), Node(3, "obsolete"), Node(4, "B"))
        val result = reconcileSubscription(rows, setOf("A", "B", "C"), { it.name }, { it.id == 2L })
        assertEquals(mapOf("A" to rows[1], "B" to rows[3]), result.retained)
        assertEquals(listOf(rows[0], rows[2]), result.removed)
        assertEquals(rows.toSet(), (result.retained.values + result.removed).toSet())
    }

    @Test fun secondRefreshDoesNotRetainAnyExtraDuplicateRows() {
        val rows = listOf(Node(1, "A"), Node(2, "A"), Node(3, "A"), Node(4, "B"))
        val first = reconcileSubscription(rows, setOf("A", "B"), { it.name }, { false })
        val next = reconcileSubscription(first.retained.values.toList(), setOf("A", "B"), { it.name }, { false })
        assertEquals(2, next.retained.size)
        assertTrue(next.removed.isEmpty())
    }
}
