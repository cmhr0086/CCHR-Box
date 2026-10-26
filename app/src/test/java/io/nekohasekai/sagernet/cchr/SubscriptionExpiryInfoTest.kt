package io.nekohasekai.sagernet.cchr

import org.junit.Assert.*
import org.junit.Test

class SubscriptionExpiryInfoTest {
    @Test fun unlimitedValuesAndOmittedExpiryAreKnown() {
        for (header in listOf("expire=0", "expire=-1", "upload=1; download=2; total=0")) {
            val info = PrivateSubscriptionManager.parseUsageInfo(header)
            assertTrue(header, info.expiryKnown)
            assertTrue(info.expire <= 0L)
        }
    }

    @Test fun missingOrMalformedMetadataDoesNotClaimUnlimitedValidity() {
        for (header in listOf(null, "", "nonsense", "upload=1; expire=broken", "expire=-2")) {
            assertFalse(PrivateSubscriptionManager.parseUsageInfo(header).expiryKnown)
        }
    }

    @Test fun finiteExpiryKeepsItsActualTimestamp() {
        val info = PrivateSubscriptionManager.parseUsageInfo("upload=1; expire=1900000000; total=100")
        assertTrue(info.expiryKnown)
        assertEquals(1900000000L, info.expire)
    }
}
