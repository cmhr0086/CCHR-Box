package io.nekohasekai.sagernet.cchr

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler

/** Exercises the real request and response code with in-process HTTP connections, never production. */
class PrivateServiceRequestsTest {
    companion object {
        private val requests = mutableListOf<FakeConnection>()
        private var responseCode = 200
        private var overrideResponse: String? = null

        init {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): HttpURLConnection {
                        check(url.host == "service.example.invalid") { "External network access forbidden in tests" }
                        return FakeConnection(url).also { requests.add(it) }
                    }
                }
            }
        }

        private class FakeConnection(url: URL) : HttpURLConnection(url) {
            val body = ByteArrayOutputStream()
            var disconnected = false
            override fun connect() = Unit
            override fun disconnect() { disconnected = true }
            override fun usingProxy() = false
            override fun getResponseCode() = PrivateServiceRequestsTest.responseCode
            override fun getOutputStream() = body
            override fun getErrorStream() = getInputStream()
            override fun getInputStream() = (overrideResponse ?: when (url.path) {
                "/subscription" -> """{"subscriptionUrl":"https://config.example.invalid/self-hosted"}"""
                "/announcement" -> """{"enabled":true,"title":"Notice","content":"Content","updatedAt":"today"}"""
                "/control" -> """{"serviceEnabled":false,"minVersionCode":9999,"updateUrl":"https://update.example.invalid","updateMessage":"Upgrade","disabledMessage":"Disabled"}"""
                else -> error("Unexpected test path")
            }).byteInputStream()
        }
    }

    @Before fun reset() {
        requests.clear()
        responseCode = 200
        overrideResponse = null
    }

    @Test fun allEightConfigurationsOnlyContactEnabledServices() = runBlocking {
        for (mask in 0..7) {
            requests.clear()
            fun endpoint(bit: Int, path: String) =
                if (mask and bit != 0) "https://service.example.invalid/$path" else ""
            val subscription = PrivateSubscriptionManager.requestSubscriptionUrl("test-invite", endpoint(1, "subscription"))
            val announcement = PrivateSubscriptionManager.fetchAnnouncement(endpoint(2, "announcement"))
            val control = PrivateSubscriptionManager.fetchAppControl(endpoint(4, "control"))
            assertEquals(mask and 1 != 0, subscription != null)
            assertEquals(mask and 2 != 0, announcement != null)
            assertEquals(mask and 4 != 0, control != null)
            assertEquals(Integer.bitCount(mask), requests.size)
            assertTrue(requests.all { it.disconnected })
            requests.firstOrNull { it.url.path == "/subscription" }?.let {
                assertEquals("POST", it.requestMethod)
                val json = JSONObject(it.body.toString("UTF-8"))
                assertEquals("test-invite", json.getString("inviteCode"))
                assertEquals(BuildConfig.VERSION_CODE, json.getInt("versionCode"))
                assertEquals("https://config.example.invalid/self-hosted", subscription)
            }
            requests.filter { it.url.path != "/subscription" }.forEach { assertEquals("GET", it.requestMethod) }
            announcement?.let { assertEquals("Content", it.content) }
            control?.let {
                assertFalse(it.serviceEnabled)
                assertEquals(9999, it.minVersionCode)
                assertEquals("Disabled", it.disabledMessage)
                assertEquals("https://update.example.invalid", it.updateUrl)
            }
        }
    }

    @Test fun emptyBuildConfigDefaultsDoNotOpenConnections() = runBlocking {
        if (BuildConfig.CCHR_SUBSCRIPTION_ENDPOINT.isBlank()) {
            assertFalse(PrivateSubscriptionManager.subscriptionEnabled)
            assertNull(PrivateSubscriptionManager.requestSubscriptionUrl("unused"))
        }
        if (BuildConfig.CCHR_ANNOUNCEMENT_ENDPOINT.isBlank()) {
            assertNull(PrivateSubscriptionManager.fetchAnnouncement())
        }
        if (BuildConfig.CCHR_APP_CONTROL_ENDPOINT.isBlank()) {
            assertNull(PrivateSubscriptionManager.fetchAppControl())
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun serviceErrorAndOptionalServiceFailuresKeepExistingSemantics() = runBlocking {
        responseCode = 403
        overrideResponse = """{"message":"Invalid invitation"}"""
        val error = runCatching {
            PrivateSubscriptionManager.requestSubscriptionUrl("bad", "https://service.example.invalid/subscription")
        }.exceptionOrNull()
        assertEquals("Invalid invitation", error?.message)
        assertNull(PrivateSubscriptionManager.fetchAnnouncement("https://service.example.invalid/announcement"))
        assertNull(PrivateSubscriptionManager.fetchAppControl("https://service.example.invalid/control"))
        assertTrue(requests.all { it.disconnected })
    }

    @Test fun disabledAnnouncementsAndMalformedOptionalResponsesAreIgnored() = runBlocking {
        overrideResponse = """{"enabled":false,"content":"Hidden"}"""
        assertNull(PrivateSubscriptionManager.fetchAnnouncement("https://service.example.invalid/announcement"))
        overrideResponse = "not json"
        assertNull(PrivateSubscriptionManager.fetchAppControl("https://service.example.invalid/control"))
    }

    @Test fun publicBuildPreservesExpiredLegacySubscriptionWithoutOpeningDatabase() = runBlocking {
        assumeTrue(BuildConfig.CCHR_SUBSCRIPTION_ENDPOINT.isBlank())
        val subscription = SubscriptionBean().apply {
            link = "https://config.example.invalid/legacy"
            autoUpdate = true
            subscriptionUserinfo = "upload=100; download=200; total=10; expire=1"
        }
        val group = ProxyGroup(id = 42, type = GroupType.SUBSCRIPTION, name = "Existing", subscription = subscription)
        assertNull(PrivateSubscriptionManager.validateDefaultSubscription(group))
        assertEquals("Existing", group.name)
        assertEquals("https://config.example.invalid/legacy", group.subscription!!.link)
        assertTrue(group.subscription!!.autoUpdate)
        assertTrue(requests.isEmpty())
    }
}
