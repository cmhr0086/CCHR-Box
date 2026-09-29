package io.nekohasekai.sagernet.cchr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.CCHR_APP_CONTROL_ENDPOINT
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

    val subscriptionEnabled: Boolean get() = CCHR_SUBSCRIPTION_ENDPOINT.isNotBlank()

    private const val DEFAULT_UPDATE_URL = "https://github.com/cmhr0086/CCHR-Box/releases"

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

    data class AppControl(
        val serviceEnabled: Boolean,
        val minVersionCode: Int,
        val updateUrl: String,
        val updateMessage: String,
        val disabledMessage: String,
    )

    private enum class AppAccessReason {
        SERVICE_DISABLED,
        UPDATE_REQUIRED,
    }

    private class AppAccessException(
        val reason: AppAccessReason,
        message: String,
        val updateUrl: String = "",
    ) : IllegalStateException(message)

    private var appControlDialog: AlertDialog? = null
    private var appControlDialogActivity: Activity? = null

    enum class InvalidReason {
        EXPIRED,
        EXHAUSTED,
    }

    suspend fun getDefaultSubscription(): ProxyGroup? {
        if (!subscriptionEnabled) {
            val selected = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            if (selected != null) {
                return SagerDatabase.groupDao.getById(selected.groupId)
                    ?.takeIf { it.type == GroupType.SUBSCRIPTION }
            }
            return SagerDatabase.groupDao.getById(DataStore.selectedGroup)
                ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                ?: SagerDatabase.groupDao.subscriptions().firstOrNull()
        }
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
        if (!ensureAppAccess(activity)) return false
        if (!subscriptionEnabled) {
            if (getSelectedDefaultProxy() != null) return true
            onMainDispatcher {
                activity.snackbar(
                    if (getDefaultProxies().isEmpty()) R.string.cchr_import_required
                    else R.string.cchr_select_node_required
                ).show()
            }
            return false
        }
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

    suspend fun refreshDefaultSubscription(
        activity: MainActivity,
        showError: Boolean,
        byUser: Boolean = false,
    ): Boolean {
        if (!ensureAppAccess(activity)) return false
        if (!subscriptionEnabled && !byUser) return false
        val group = getDefaultSubscription() ?: return false
        val ok = try {
            if (subscriptionEnabled) group.name = CCHR_DEFAULT_SUBSCRIPTION_NAME
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
        return ok
    }

    suspend fun activateWithInviteCode(activity: MainActivity, inviteCode: String): Boolean {
        return try {
            requireSubscriptionService()
            if (!ensureAppAccess(activity)) return false
            activateWithInviteCode(inviteCode, replaceExisting = false)
        } catch (e: Throwable) {
            onMainDispatcher {
                activity.snackbar(e.readableMessage).show()
            }
            false
        }
    }

    suspend fun activateWithInviteCode(inviteCode: String, replaceExisting: Boolean): Boolean {
        requireSubscriptionService()
        requireAppAccess()
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
        if (!subscriptionEnabled) return SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        val group = getDefaultSubscription() ?: return null
        return SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            ?.takeIf { it.groupId == group.id }
    }

    suspend fun getDefaultProxies(): List<ProxyEntity> {
        if (!subscriptionEnabled) return SagerDatabase.proxyDao.getAll()
            .sortedWith(compareBy<ProxyEntity> { it.groupId }.thenBy { it.userOrder })
        val group = getDefaultSubscription() ?: return emptyList()
        return SagerDatabase.proxyDao.getByGroup(group.id)
    }

    suspend fun selectDefaultProxy(profileId: Long): Boolean {
        val target = getEligibleProxy(profileId) ?: return false
        val previous = DataStore.selectedProxy

        DataStore.selectedGroup = target.groupId
        DataStore.selectedProxy = target.id

        if (previous != target.id) {
            ProfileManager.postUpdate(previous, true)
            ProfileManager.postUpdate(target.id, true)
            GroupManager.postUpdate(target.groupId)
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
        }
        return true
    }

    suspend fun recordDefaultProxyLatency(profileId: Long, ping: Int, error: String?): ProxyEntity? {
        val profile = getEligibleProxy(profileId) ?: return null

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
        GroupManager.postUpdate(profile.groupId)
        return profile
    }

    suspend fun testDefaultProxyLatency(profileId: Long) {
        val profile = getEligibleProxy(profileId) ?: return

        try {
            recordDefaultProxyLatency(profile.id, UrlTest().doTest(profile), null)
        } catch (e: Throwable) {
            recordDefaultProxyLatency(profile.id, 0, e.readableMessage)
        }
    }

    internal suspend fun fetchAnnouncement(endpoint: String = CCHR_ANNOUNCEMENT_ENDPOINT): Announcement? {
        if (endpoint.isBlank()) return null
        return runCatching {
            onIoDispatcher {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
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

    suspend fun ensureAppAccess(activity: Activity): Boolean {
        val exception = appAccessException(fetchAppControl()) ?: return true
        if (DataStore.serviceState.started) {
            SagerNet.stopService()
        }
        showAppAccessDialog(activity, exception)
        return false
    }

    private suspend fun requireAppAccess() {
        appAccessException(fetchAppControl())?.let { throw it }
    }

    private fun appAccessException(control: AppControl?): AppAccessException? {
        if (control == null) return null
        if (!control.serviceEnabled) {
            return AppAccessException(
                AppAccessReason.SERVICE_DISABLED,
                control.disabledMessage.ifBlank { app.getString(R.string.cchr_service_disabled_message) },
            )
        }
        if (control.minVersionCode > BuildConfig.VERSION_CODE) {
            return AppAccessException(
                AppAccessReason.UPDATE_REQUIRED,
                control.updateMessage.ifBlank { app.getString(R.string.cchr_update_required_message) },
                control.updateUrl.takeIf(::isHttpsUrl) ?: DEFAULT_UPDATE_URL,
            )
        }
        return null
    }

    internal suspend fun fetchAppControl(endpoint: String = CCHR_APP_CONTROL_ENDPOINT): AppControl? {
        if (endpoint.isBlank()) return null
        return runCatching {
            onIoDispatcher {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                try {
                    if (connection.responseCode !in 200..299) return@onIoDispatcher null
                    val json = JSONObject(connection.inputStream.bufferedReader().readText())
                    AppControl(
                        serviceEnabled = json.optBoolean("serviceEnabled", true),
                        minVersionCode = json.optInt("minVersionCode", 0).coerceAtLeast(0),
                        updateUrl = json.optString("updateUrl").trim(),
                        updateMessage = json.optString("updateMessage").trim(),
                        disabledMessage = json.optString("disabledMessage").trim(),
                    )
                } finally {
                    connection.disconnect()
                }
            }
        }.getOrNull()
    }

    private suspend fun showAppAccessDialog(activity: Activity, exception: AppAccessException) {
        onMainDispatcher {
            if (activity.isFinishing) return@onMainDispatcher
            if (appControlDialog?.isShowing == true && appControlDialogActivity === activity) {
                return@onMainDispatcher
            }
            appControlDialog?.dismiss()
            val builder = MaterialAlertDialogBuilder(activity)
                .setCancelable(true)
                .setMessage(exception.message)
            when (exception.reason) {
                AppAccessReason.SERVICE_DISABLED -> builder
                    .setTitle(R.string.cchr_service_disabled_title)
                    .setPositiveButton(android.R.string.ok, null)

                AppAccessReason.UPDATE_REQUIRED -> {
                    builder.setTitle(R.string.cchr_update_required_title)
                    builder.setNeutralButton(R.string.cchr_update_copy_link) { _, _ ->
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Update URL", exception.updateUrl))
                        Toast.makeText(activity, R.string.cchr_update_link_copied, Toast.LENGTH_SHORT).show()
                    }
                    builder.setPositiveButton(R.string.cchr_update_now) { _, _ ->
                        if (!openUpdatePage(activity, exception.updateUrl)) {
                            Toast.makeText(activity, R.string.cchr_update_browser_unavailable, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            builder.create().apply {
                setCanceledOnTouchOutside(true)
                appControlDialog = this
                appControlDialogActivity = activity
                setOnDismissListener {
                    if (appControlDialog === this) {
                        appControlDialog = null
                        appControlDialogActivity = null
                    }
                }
                show()
                if (exception.reason == AppAccessReason.UPDATE_REQUIRED) {
                    insetUpdateButtons(activity)
                }
            }
        }
    }

    private fun AlertDialog.insetUpdateButtons(activity: Activity) {
        val offset = 8f * activity.resources.displayMetrics.density
        getButton(AlertDialog.BUTTON_NEUTRAL)?.translationX = offset
        getButton(AlertDialog.BUTTON_POSITIVE)?.translationX = -offset
    }

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        URL(value).protocol == "https:"
    }.getOrDefault(false)

    private fun openUpdatePage(activity: Activity, url: String): Boolean {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return runCatching {
            if (viewIntent.resolveActivity(activity.packageManager) == null) return false
            activity.startActivity(Intent.createChooser(viewIntent, null))
            true
        }.getOrDefault(false)
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
        if (getSelectedDefaultProxy() != null) return true

        var proxies = SagerDatabase.proxyDao.getByGroup(group.id)

        if (proxies.isEmpty() && allowRefresh) {
            refreshDefaultSubscription(activity, showError = true)
            val refreshedGroup = getDefaultSubscription() ?: return false
            proxies = SagerDatabase.proxyDao.getByGroup(refreshedGroup.id)
        }

        onMainDispatcher {
            activity.snackbar(
                if (proxies.isEmpty()) R.string.cchr_subscription_no_nodes
                else R.string.cchr_select_node_required
            ).show()
        }
        return false
    }

    suspend fun validateDefaultSubscription(group: ProxyGroup): InvalidReason? {
        if (!subscriptionEnabled) return null
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
        requireSubscriptionService()
        return checkNotNull(requestSubscriptionUrl(inviteCode))
    }

    private fun requireSubscriptionService() {
        if (CCHR_SUBSCRIPTION_ENDPOINT.isBlank()) {
            error(app.getString(R.string.cchr_subscription_service_not_configured))
        }
    }

    private suspend fun getEligibleProxy(profileId: Long): ProxyEntity? {
        val profile = SagerDatabase.proxyDao.getById(profileId) ?: return null
        if (!subscriptionEnabled) return profile
        val group = getDefaultSubscription() ?: return null
        return profile.takeIf { it.groupId == group.id }
    }

    internal suspend fun requestSubscriptionUrl(
        inviteCode: String,
        endpoint: String = CCHR_SUBSCRIPTION_ENDPOINT,
    ): String? {
        if (endpoint.isBlank()) return null
        return onIoDispatcher {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val body = JSONObject()
                    .put("inviteCode", inviteCode)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .toString()
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
