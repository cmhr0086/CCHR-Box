package io.nekohasekai.sagernet.cchr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.room.withTransaction
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
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.nekohasekai.sagernet.ui.MainActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object PrivateSubscriptionManager {

    private val subscriptionOperations = SubscriptionOperations()
    private fun inviteKey(groupId: Long) = "cchr.privateSubscriptionInvite.$groupId"

    internal fun isManagedSubscription(group: ProxyGroup): Boolean =
        subscriptionEnabled && group.type == GroupType.SUBSCRIPTION &&
            group.name == CCHR_DEFAULT_SUBSCRIPTION_NAME

    internal suspend fun updateManagedSubscription(groupId: Long, byUser: Boolean): Boolean =
        subscriptionOperations.run {
            // Reload after acquiring the lock: a replacement may have removed this group.
            val group = SagerDatabase.groupDao.getById(groupId) ?: return@run false
            if (!isManagedSubscription(group)) return@run false
            requireAppAccess()
            val invite = DataStore.configurationStore.getString(inviteKey(groupId))
                ?.takeIf { it.isNotBlank() }
                ?: error(app.getString(R.string.cchr_invite_reentry_required))
            val previousNodeIds = SagerDatabase.proxyDao.getByGroup(groupId).map { it.id }.toSet()
            // The new URL is only persisted with the successfully parsed node update.
            group.subscription!!.link = fetchSubscriptionUrl(invite)
            val ok = GroupUpdater.executeUpdateDirect(group, byUser, throwOnFailure = true)
            defaultUpdateFailed = !ok
            val selectedId = DataStore.selectedProxy
            if (ok && selectedId in previousNodeIds &&
                SagerDatabase.proxyDao.getById(selectedId) == null) {
                DataStore.selectedProxy = 0L
                if (DataStore.serviceState.started) SagerNet.stopService()
            }
            ok
        }

    val subscriptionEnabled: Boolean get() = CCHR_SUBSCRIPTION_ENDPOINT.isNotBlank()

    private const val DEFAULT_UPDATE_URL = "https://github.com/cmhr0086/CCHR-Box/releases"

    var defaultUpdateFailed: Boolean = false
        private set

    data class UsageInfo(
        val upload: Long = 0L,
        val download: Long = 0L,
        val total: Long = 0L,
        val expire: Long = 0L,
        val expiryKnown: Boolean = false,
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
            .filter { it.name != CCHR_TEMP_SUBSCRIPTION_NAME }
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
        val group = getDefaultSubscription() ?: run {
            if (showError) onMainDispatcher {
                activity.snackbar(
                    if (subscriptionEnabled) R.string.cchr_activate_required else R.string.cchr_import_required
                ).show()
            }
            return false
        }
        var failureMessage: String? = null
        val ok = try {
            if (subscriptionEnabled) group.name = CCHR_DEFAULT_SUBSCRIPTION_NAME
            if (subscriptionEnabled) updateManagedSubscription(group.id, false)
            else GroupUpdater.executeUpdate(group, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failureMessage = e.readableMessage
            false
        }
        defaultUpdateFailed = !ok
        val updatedGroup = getDefaultSubscription() ?: return false
        val invalidReason = if (ok) validateDefaultSubscription(updatedGroup) else null
        if (invalidReason != null) {
            showInvalidDialog(activity, invalidReason)
            return false
        }
        if (!ok && showError) {
            onMainDispatcher {
                activity.snackbar(failureMessage ?: app.getString(R.string.cchr_subscription_update_failed)).show()
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
        return subscriptionOperations.run {
            requireSubscriptionService()
            requireAppAccess()
            val current = getDefaultSubscription()
            if (!replaceExisting && current != null) return@run true
            val subscriptionUrl = fetchSubscriptionUrl(inviteCode.trim())
            val oldGroups = SagerDatabase.groupDao.subscriptions().filter {
                it.id == current?.id || it.name == CCHR_DEFAULT_SUBSCRIPTION_NAME ||
                    it.name == CCHR_TEMP_SUBSCRIPTION_NAME
            }
            val group = createSubscriptionGroup(subscriptionUrl)
            var committed = false
            try {
                if (!updateAndValidateNewSubscription(group)) return@run false
                // Keep the invite outside the shared subscription serialization format.
                DataStore.configurationStore.putString(inviteKey(group.id), inviteCode.trim())
                withContext(NonCancellable) {
                    SagerDatabase.instance.withTransaction {
                        SagerDatabase.proxyDao.deleteByGroup(oldGroups.map { it.id }.toLongArray())
                        SagerDatabase.groupDao.deleteGroup(oldGroups)
                        group.name = CCHR_DEFAULT_SUBSCRIPTION_NAME
                        group.subscription!!.autoUpdate = true
                        SagerDatabase.groupDao.updateGroup(group)
                    }
                    committed = true
                    DataStore.selectedGroup = group.id
                    DataStore.selectedProxy = 0L
                    if (replaceExisting && DataStore.serviceState.started) SagerNet.stopService()
                    for (old in oldGroups) {
                        DataStore.configurationStore.remove(inviteKey(old.id))
                        GroupManager.iterator { groupRemoved(old.id) }
                    }
                    GroupManager.postUpdate(group)
                    SubscriptionUpdater.reconfigureUpdater()
                }
                true
            } finally {
                if (!committed) withContext(NonCancellable) {
                    DataStore.configurationStore.remove(inviteKey(group.id))
                    GroupManager.deleteGroup(group.id)
                }
            }
        }
    }

    private suspend fun createSubscriptionGroup(subscriptionUrl: String): ProxyGroup {
        val group = ProxyGroup(
            type = GroupType.SUBSCRIPTION,
            name = CCHR_TEMP_SUBSCRIPTION_NAME,
            subscription = SubscriptionBean().apply {
                link = subscriptionUrl
                deduplication = true
                // Never schedule a background update for an uncommitted candidate.
                autoUpdate = false
            },
        )
        return GroupManager.createGroup(group)
    }

    private suspend fun updateAndValidateNewSubscription(group: ProxyGroup): Boolean {
        val ok = GroupUpdater.executeUpdateDirect(group, false, throwOnFailure = true)
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
                resolveAppUpdateUrl(control.updateUrl),
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

    internal fun resolveAppUpdateUrl(value: String): String {
        val candidate = value.trim()
        val valid = runCatching {
            val url = URL(candidate)
            url.protocol.equals("https", ignoreCase = true) && url.host.isNotBlank()
        }.getOrDefault(false)
        return if (valid) candidate else DEFAULT_UPDATE_URL
    }

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

    internal fun requireUsableSubscription(group: ProxyGroup) {
        if (!subscriptionEnabled || (group.name != CCHR_DEFAULT_SUBSCRIPTION_NAME &&
                group.name != CCHR_TEMP_SUBSCRIPTION_NAME)) return
        val usage = parseUsageInfo(group.subscription?.subscriptionUserinfo)
        if (usage.isExpired || usage.isExhausted) {
            error(app.getString(R.string.cchr_subscription_unusable_preserved))
        }
    }

    fun parseUsageInfo(subscriptionUserinfo: String?): UsageInfo {
        if (subscriptionUserinfo.isNullOrBlank()) return UsageInfo()

        val expiryField = "(?:^|;)\\s*expire\\s*=\\s*([^;]*)".toRegex()
            .find(subscriptionUserinfo)
        val expiry = expiryField?.groupValues?.get(1)?.trim()?.toLongOrNull()
        val knownStats = "(?:^|;)\\s*(?:upload|download|total)=[0-9]+".toRegex()
            .containsMatchIn(subscriptionUserinfo)

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
            expire = expiry ?: 0L,
            expiryKnown = if (expiryField != null) expiry != null && expiry >= -1L else knownStats,
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
                useCaches = false
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
