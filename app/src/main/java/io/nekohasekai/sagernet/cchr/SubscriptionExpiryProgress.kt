package io.nekohasekai.sagernet.cchr

import kotlin.math.roundToInt

internal object SubscriptionExpiryProgress {
    enum class Kind { UNKNOWN, UNLIMITED, EXPIRED, ACTIVE }

    data class State(
        val kind: Kind,
        val daysRemaining: Int = 0,
        val percentRemaining: Int = 0,
        val reference: String? = null,
    )

    fun calculate(
        expire: Long,
        expiryKnown: Boolean,
        now: Long,
        savedReference: String?,
    ): State {
        if (!expiryKnown) return State(Kind.UNKNOWN)
        if (expire <= 0L) return State(Kind.UNLIMITED)
        if (expire <= now) return State(Kind.EXPIRED)

        val parts = savedReference?.split(':', limit = 2)
        val savedExpiry = parts?.getOrNull(0)?.toLongOrNull()
        val savedStart = parts?.getOrNull(1)?.toLongOrNull()
        // An unchanged deadline retains its reference across refreshes and restarts.
        // A changed deadline represents a new validity period (renewal/plan change).
        val start = savedStart?.takeIf {
            savedExpiry == expire && it >= 0L && it <= now && it < expire
        } ?: now
        val remaining = expire - now
        val duration = expire - start
        val days = remaining / 86400L + if (remaining % 86400L > 0L) 1L else 0L
        return State(
            Kind.ACTIVE,
            days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            (remaining.toDouble() / duration * 100).roundToInt().coerceIn(0, 100),
            "$expire:$start",
        )
    }
}
