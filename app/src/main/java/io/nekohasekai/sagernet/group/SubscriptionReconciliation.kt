package io.nekohasekai.sagernet.group

internal data class SubscriptionReconciliation<T>(val retained: Map<String, T>, val removed: List<T>)

/** Keep one existing row per incoming name, preferring the selected node. */
internal fun <T> reconcileSubscription(
    existing: List<T>, incomingNames: Set<String>, name: (T) -> String, preferred: (T) -> Boolean,
): SubscriptionReconciliation<T> {
    val retained = linkedMapOf<String, T>()
    val removed = mutableListOf<T>()
    for (item in existing) {
        val key = name(item)
        val previous = retained[key]
        when {
            key !in incomingNames -> removed.add(item)
            previous == null -> retained[key] = item
            preferred(item) -> {
                removed.add(previous)
                retained[key] = item
            }
            else -> removed.add(item)
        }
    }
    return SubscriptionReconciliation(retained, removed)
}
