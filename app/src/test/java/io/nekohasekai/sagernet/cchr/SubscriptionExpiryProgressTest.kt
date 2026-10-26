package io.nekohasekai.sagernet.cchr

import org.junit.Assert.*
import org.junit.Test

class SubscriptionExpiryProgressTest {
    private val start = 1000000L
    private val day = 86400L

    @Test fun ninetyDayPeriodDoesNotBecomeFullAtThirtyDaysRemaining() {
        val expiry = start + 90 * day
        val first = SubscriptionExpiryProgress.calculate(expiry, true, start, null)
        assertEquals(90, first.daysRemaining)
        assertEquals(100, first.percentRemaining)
        val sixty = SubscriptionExpiryProgress.calculate(expiry, true, start + 30 * day, first.reference)
        assertEquals(60, sixty.daysRemaining)
        assertEquals(67, sixty.percentRemaining)
        val thirty = SubscriptionExpiryProgress.calculate(expiry, true, start + 60 * day, sixty.reference)
        assertEquals(30, thirty.daysRemaining)
        assertEquals(33, thirty.percentRemaining)
        assertEquals(first.reference, thirty.reference)
    }

    @Test fun renewalStartsANewReferencePeriod() {
        val old = SubscriptionExpiryProgress.calculate(start + 90 * day, true, start, null)
        val renewed = SubscriptionExpiryProgress.calculate(start + 180 * day, true, start + 60 * day, old.reference)
        assertEquals(120, renewed.daysRemaining)
        assertEquals(100, renewed.percentRemaining)
        assertNotEquals(old.reference, renewed.reference)
    }

    @Test fun partialDayIsNotShownAsExpired() {
        val state = SubscriptionExpiryProgress.calculate(start + 1L, true, start, null)
        assertEquals(SubscriptionExpiryProgress.Kind.ACTIVE, state.kind)
        assertEquals(1, state.daysRemaining)
        assertEquals(SubscriptionExpiryProgress.Kind.EXPIRED,
            SubscriptionExpiryProgress.calculate(start, true, start, null).kind)
    }

    @Test fun unlimitedAndMissingMetadataHaveDifferentStates() {
        assertEquals(SubscriptionExpiryProgress.Kind.UNLIMITED,
            SubscriptionExpiryProgress.calculate(0L, true, start, null).kind)
        assertEquals(SubscriptionExpiryProgress.Kind.UNLIMITED,
            SubscriptionExpiryProgress.calculate(-1L, true, start, null).kind)
        assertEquals(SubscriptionExpiryProgress.Kind.UNKNOWN,
            SubscriptionExpiryProgress.calculate(0L, false, start, null).kind)
    }

    @Test fun malformedReferenceAndVeryLargeExpiryDoNotOverflow() {
        val state = SubscriptionExpiryProgress.calculate(Long.MAX_VALUE, true, start, "broken")
        assertEquals(Int.MAX_VALUE, state.daysRemaining)
        assertEquals(100, state.percentRemaining)
    }
}
