package io.nekohasekai.sagernet.cchr

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.CCHR_ANNOUNCEMENT_ENDPOINT
import io.nekohasekai.sagernet.CCHR_DEFAULT_SUBSCRIPTION_NAME
import io.nekohasekai.sagernet.CCHR_SUBSCRIPTION_ENDPOINT
import io.nekohasekai.sagernet.CCHR_TEMP_SUBSCRIPTION_NAME
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onIoDispatcher
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.MainActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object PrivateSubscriptionManager {

    var defaultUpdateFailed: Boolean = false
        private set

    data class UsageInfo(
        val upload: Long = 0L,
        val download: Long = 0L,
        val total: Long = 0L,
        val expire: Long = 0L,
    ) {
        val used: Long get() = upload + download
        val remaining: Long get() = total - used
        val isExpired: Boolean
            get() = expire > 0L && expire <= System.currentTimeMillis() / 1000
        val isExhausted: Boolean
            get() = total > 0L && used >= total
    }

    data class Announcement(
        val title: String,
        val content: String,
        val updatedAt: String,
    )

    enum class InvalidReason {
        EXPIRED,
        EXHAUSTED,
    }

    suspend fun getDefaultSubscription(): ProxyGroup? {
        val groups = SagerDatabase.groupDao.subscriptions()
        val group = groups.firstOrNull { it.name == CCHR_DEFAULT_SUBSCRIPTION_NAME }
            ?: groups.firstOrNull()
            ?: return null
        if (group.name != CCHR_DEFAULT_SUBSCRIPTION_NAME) {
            group.name = CCHR_DEFAULT_SUBSCRIPTION_NAME
            GroupManager.updateGroup(group)
        }
        return group
    }

    suspend fun ensureReadyForConnection(activity: MainActivity): Boolean {
        val group = getDefaultSubscription()
        return if (group == null) {
            onMainDispatcher {
                activity.snackbar(R.string.cchr_activate_required).show()
            }
            false
        } else {
            ensureDefaultSubscriptionConnectable(activity, group)
        }
    }

    suspend fun refreshDefaultSubscription(activity: MainActivity, showError: Boolean): Boolean {
        val group = getDefaultSubscription() ?: return false
        val ok = try {
            group.name = CCHR_DEFAULT_SUBSCRIPTION_NAME
            GroupUpdater.executeUpdate(group, false)
        } catch (e: Throwable) {
            if (showError) {
                onMainDispatcher {
                    activity.snackbar(e.readableMessage).show()
                }
            }
            false
        }
        defaultUpdateFailed = !ok
        val updatedGroup = getDefaultSubscription() ?: return false
        val invalidReason = validateDefaultSubscription(updatedGroup)
        if (invalidReason != null) {
            showInvalidDialog(activity, invalidReason)
            return false
        }
        if (!ok && showError) {
            onMainDispatcher {
                activity.snackbar(R.string.cchr_subscription_update_failed).show()
            }
        }
        ensureDefaultProxySelected(updatedGroup)
        return ok
    }

    suspend fun activateWithInviteCode(activity: MainActivity, inviteCode: String): Boolean {
        return try {
            activateWithInviteCode(inviteCode, replaceExisting = false)
        } catch (e: Throwable) {
            onMainDispatcher {
                activity.snackbar(e.readableMessage).show()
            }
            false
        }
    }

    suspend fun activateWithInviteCode(inviteCode: String, replaceExisting: Boolean): Boolean {
        val subscriptionUrl = fetchSubscriptionUrl(inviteCode)
        val oldGroups = SagerDatabase.groupDao.subscriptions()
        if (!replaceExisting && oldGroups.isNotEmpty()) return true

        val group = createSubscriptionGroup(subscriptionUrl, temporary = replaceExisting)
        val ok = try {
            updateAndValidateNewSubscription(group)
        } catch (e: Throwable) {
            GroupManager.deleteGroup(group.id)
            throw e
        }
        if (!ok) {
            GroupManager.deleteGroup(group.id)
            return false
        }

        if (replaceExisting) {
            GroupManager.deleteGroup(oldGroups.filter { it.id != group.id })
        }
        group.name = CCHR_DEFAULT_SUBSCRIPTION_NAME
        GroupManager.updateGroup(group)
        ensureDefaultProxySelected(group)
        if (replaceExisting && DataStore.serviceState.started) {
            SagerNet.reloadService()
        }
        return true
    }

    private suspend fun createSubscriptionGroup(subscriptionUrl: String, temporary: Boolean): ProxyGroup {
        val group = ProxyGroup(
            type = GroupType.SUBSCRIPTION,
            name = if (temporary) CCHR_TEMP_SUBSCRIPTION_NAME else CCHR_DEFAULT_SUBSCRIPTION_NAME,
            subscription = SubscriptionBean().apply {
                link = subscriptionUrl
                deduplication = true
                autoUpdate = true
            },
        )
        return GroupManager.createGroup(group)
    }

    private suspend fun updateAndValidateNewSubscription(group: ProxyGroup): Boolean {
        val ok = GroupUpdater.executeUpdate(group, false)
        defaultUpdateFailed = !ok
        if (!ok) return false
        validateDefaultSubscription(group)?.let { return false }
        if (SagerDatabase.proxyDao.getByGroup(group.id).isEmpty()) {
            error(app.getString(R.string.cchr_subscription_no_nodes))
        }
        return true
    }

    suspend fun getSelectedDefaultProxy(): ProxyEntity? {
        val group = getDefaultSubscription() ?: return null
        return ensureDefaultProxySelected(group)
    }

    suspend fun getDefaultProxies(): List<ProxyEntity> {
        val group = getDefaultSubscription() ?: return emptyList()
        return SagerDatabase.proxyDao.getByGroup(group.id)
    }

    suspend fun selectDefaultProxy(profileId: Long): Boolean {
        val group = getDefaultSubscription() ?: return false
        val target = SagerDatabase.proxyDao.getById(profileId)
            ?.takeIf { it.groupId == group.id }
            ?: return false
        val previous = DataStore.selectedProxy

        DataStore.selectedGroup = group.id
        DataStore.selectedProxy = target.id

        if (previous != target.id) {
            ProfileManager.postUpdate(previous, true)
            ProfileManager.postUpdate(target.id, true)
            GroupManager.postUpdate(group.id)
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
        }
        return true
    }

    suspend fun recordDefaultProxyLatency(profileId: Long, ping: Int, error: String?): ProxyEntity? {
        val group = getDefaultSubscription() ?: return null
        val profile = SagerDatabase.proxyDao.getById(profileId)
            ?.takeIf { it.groupId == group.id }
            ?: return null

        if (error == null && ping > 0) {
            profile.status = 1
            profile.ping = ping
            profile.error = null
        } else {
            profile.status = 3
            profile.ping = 0
            profile.error = error
        }
        SagerDatabase.proxyDao.updateProxy(profile)
        ProfileManager.postUpdate(profile.id, true)
        GroupManager.postUpdate(group.id)
        return profile
    }

    suspend fun testDefaultProxyLatency(profileId: Long) {
        val group = getDefaultSubscription() ?: return
        val profile = SagerDatabase.proxyDao.getById(profileId) ?: return
        if (profile.groupId != group.id) return

        try {
            recordDefaultProxyLatency(profile.id, UrlTest().doTest(profile), null)
        } catch (e: Throwable) {
            recordDefaultProxyLatency(profile.id, 0, e.readableMessage)
        }
    }

    suspend fun fetchAnnouncement(): Announcement? {
        if (CCHR_ANNOUNCEMENT_ENDPOINT.isBlank()) return null
        return runCatching {
            onIoDispatcher {
                val connection = (URL(CCHR_ANNOUNCEMENT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (connection.responseCode !in 200..299) return@onIoDispatcher null
                    val responseText = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(responseText.takeIf { it.isNotBlank() } ?: "{}")
                    if (!json.optBoolean("enabled", false)) return@onIoDispatcher null
                    val content = json.optString("content").trim()
                    if (content.isBlank()) return@onIoDispatcher null
                    Announcement(
                        title = json.optString("title").trim(),
                        content = content,
                        updatedAt = json.optString("updatedAt").trim(),
                    )
                } finally {
                    connection.disconnect()
                }
            }
        }.getOrNull()
    }

    private suspend fun ensureDefaultSubscriptionConnectable(
        activity: MainActivity,
        group: ProxyGroup,
    ): Boolean {
        val invalidReason = validateDefaultSubscription(group)
        if (invalidReason != null) {
            showInvalidDialog(activity, invalidReason)
            return false
        }
        return ensureDefaultProxyAvailable(activity, allowRefresh = true)
    }

    private suspend fun ensureDefaultProxyAvailable(
        activity: MainActivity,
        allowRefresh: Boolean,
    ): Boolean {
        val group = getDefaultSubscription() ?: return false
        if (ensureDefaultProxySelected(group) != null) return true

        if (allowRefresh) {
            refreshDefaultSubscription(activity, showError = true)
            val refreshedGroup = getDefaultSubscription() ?: return false
            if (ensureDefaultProxySelected(refreshedGroup) != null) return true
        }

        onMainDispatcher {
            activity.snackbar(R.string.cchr_subscription_no_nodes).show()
        }
        return false
    }

    private suspend fun ensureDefaultProxySelected(group: ProxyGroup): ProxyEntity? {
        val proxies = SagerDatabase.proxyDao.getByGroup(group.id)
        if (proxies.isEmpty()) return null

        val selectedProxy = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        val target = selectedProxy?.takeIf { it.groupId == group.id } ?: proxies.first()
        val previous = DataStore.selectedProxy

        DataStore.selectedGroup = group.id
        DataStore.selectedProxy = target.id

        if (previous != target.id) {
            ProfileManager.postUpdate(previous, true)
            ProfileManager.postUpdate(target.id, true)
            GroupManager.postUpdate(group.id)
        }
        return target
    }

    suspend fun validateDefaultSubscription(group: ProxyGroup): InvalidReason? {
        val usage = parseUsageInfo(group.subscription?.subscriptionUserinfo)
        return when {
            usage.isExpired -> {
                GroupManager.deleteGroup(group.id)
                InvalidReason.EXPIRED
            }

            usage.isExhausted -> {
                GroupManager.deleteGroup(group.id)
                InvalidReason.EXHAUSTED
            }

            else -> null
        }
    }

    fun parseUsageInfo(subscriptionUserinfo: String?): UsageInfo {
        if (subscriptionUserinfo.isNullOrBlank()) return UsageInfo()

        fun get(key: String): Long {
            return "$key=([0-9]+)".toRegex().find(subscriptionUserinfo)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: 0L
        }

        return UsageInfo(
            upload = get("upload"),
            download = get("download"),
            total = get("total"),
            expire = get("expire"),
        )
    }

    private suspend fun fetchSubscriptionUrl(inviteCode: String): String {
        if (CCHR_SUBSCRIPTION_ENDPOINT.isBlank()) {
            error(app.getString(R.string.cchr_subscription_service_not_configured))
        }
        return onIoDispatcher {
            val connection = (URL(CCHR_SUBSCRIPTION_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val body = JSONObject().put("inviteCode", inviteCode).toString()
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                    it.write(body)
                }
                val responseText = if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText().orEmpty()
                }
                val json = JSONObject(responseText.takeIf { it.isNotBlank() } ?: "{}")
                if (connection.responseCode !in 200..299) {
                    error(json.optString("message").takeIf { it.isNotBlank() }
                        ?: "HTTP ${connection.responseCode}")
                }
                json.optString("subscriptionUrl")
                    .takeIf { it.isNotBlank() }
                    ?: error(app.getString(R.string.cchr_subscription_url_missing))
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun showInvalidDialog(activity: MainActivity, reason: InvalidReason) {
        onMainDispatcher {
            MaterialAlertDialogBuilder(activity)
                .setMessage(
                    when (reason) {
                        InvalidReason.EXPIRED -> R.string.cchr_default_subscription_removed_expired
                        InvalidReason.EXHAUSTED -> R.string.cchr_default_subscription_removed_exhausted
                    }
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
