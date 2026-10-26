package io.nekohasekai.sagernet.cchr

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateUrlTest {
    @Test fun preservesTheConfiguredHttpsDownloadInsteadOfFallingBackToGithub() {
        val download = "https://download.example.invalid/client.apk?version=1.4"
        assertEquals(download, PrivateSubscriptionManager.resolveAppUpdateUrl(download))
        assertEquals(download, PrivateSubscriptionManager.resolveAppUpdateUrl("  $download  "))
    }

    @Test fun invalidOrInsecureAddressesUseTheExistingFallback() {
        val fallback = "https://github.com/cmhr0086/CCHR-Box/releases"
        for (value in listOf("", "not a url", "http://download.example.invalid/app.apk", "https:///app.apk")) {
            assertEquals(fallback, PrivateSubscriptionManager.resolveAppUpdateUrl(value))
        }
    }
}
