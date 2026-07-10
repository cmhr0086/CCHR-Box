package io.nekohasekai.sagernet.cchr

import android.text.InputType
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.CCHR_DEFAULT_SUBSCRIPTION_NAME
import io.nekohasekai.sagernet.CCHR_SUBSCRIPTION_ENDPOINT
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
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
import kotlinx.coroutines.CompletableDeferred
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
            val inviteCode = requestInviteCode(activity) ?: return false
            activateWithInviteCode(activity, inviteCode)
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
        val subscriptionUrl = try {
            fetchSubscriptionUrl(inviteCode)
        } catch (e: Throwable) {
            onMainDispatcher {
                activity.snackbar(e.readableMessage).show()
            }
            return false
        }
        val group = ProxyGroup(
            type = GroupType.SUBSCRIPTION,
            name = CCHR_DEFAULT_SUBSCRIPTION_NAME,
            subscription = SubscriptionBean().apply {
                link = subscriptionUrl
                deduplication = true
                autoUpdate = true
            },
        )
        GroupManager.createGroup(group)
        return refreshDefaultSubscription(activity, showError = true) &&
                ensureDefaultProxyAvailable(activity, allowRefresh = false)
    }

    suspend fun testDefaultProxyLatency(profileId: Long) {
        val group = getDefaultSubscription() ?: return
        val profile = SagerDatabase.proxyDao.getById(profileId) ?: return
        if (profile.groupId != group.id) return

        try {
            profile.status = 1
            profile.ping = UrlTest().doTest(profile)
            profile.error = null
        } catch (e: Throwable) {
            profile.status = 3
            profile.ping = 0
            profile.error = e.readableMessage
        }
        SagerDatabase.proxyDao.updateProxy(profile)
        ProfileManager.postUpdate(profile.id, true)
        GroupManager.postUpdate(group.id)
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
        DataStore.currentProfile = target.id

        if (previous != target.id) {
            ProfileManager.postUpdate(previous, true)
            ProfileManager.postUpdate(target.id, true)
        }
        GroupManager.postUpdate(group.id)
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

    private suspend fun requestInviteCode(activity: MainActivity): String? {
        val result = CompletableDeferred<String?>()
        onMainDispatcher {
            val horizontalPadding = (24 * activity.resources.displayMetrics.density).toInt()
            val topPadding = (8 * activity.resources.displayMetrics.density).toInt()
            val input = TextInputEditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            val inputLayout = TextInputLayout(activity).apply {
                hint = activity.getString(R.string.cchr_invite_hint)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                addView(
                    input,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(horizontalPadding, topPadding, horizontalPadding, 0)
                addView(
                    inputLayout,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.cchr_invite_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(input.text?.toString()?.trim().takeIf { !it.isNullOrBlank() })
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    result.complete(null)
                }
                .setOnCancelListener {
                    result.complete(null)
                }
                .show()
        }
        return result.await()
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
