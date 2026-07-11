package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.cchr.PrivateSubscriptionManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.databinding.LayoutPrivateHomeBinding
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import moe.matsuri.nb4a.utils.toBytesString
import kotlin.math.roundToInt

class PrivateHomeFragment : ToolbarFragment(R.layout.layout_private_home), GroupManager.Listener {

    private var binding: LayoutPrivateHomeBinding? = null
    private var refreshingSubscription = false
    private var testingLatency = false
    private var loadingAnnouncement = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutPrivateHomeBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_configuration)
        GroupManager.addListener(this)
        binding?.subscriptionRefresh?.setOnClickListener { refreshSubscription() }
        binding?.nodeSelectorCard?.setOnClickListener { showNodeSelector() }
        binding?.latencyTest?.setOnClickListener { testLatency() }
        binding?.changeInvite?.setOnClickListener { changeInviteCode() }
        reload()
        reloadAnnouncement()
    }

    override fun onResume() {
        super.onResume()
        reload()
        reloadAnnouncement()
    }

    override fun onDestroyView() {
        GroupManager.removeListener(this)
        binding = null
        super.onDestroyView()
    }

    private fun reload() {
        runOnDefaultDispatcher {
            val group = PrivateSubscriptionManager.getDefaultSubscription()
            val proxy = PrivateSubscriptionManager.getSelectedDefaultProxy()
            val proxies = PrivateSubscriptionManager.getDefaultProxies()
            onMainDispatcher {
                bind(group, proxy, proxies)
            }
        }
    }

    private fun bind(group: ProxyGroup?, proxy: ProxyEntity?, proxies: List<ProxyEntity>) {
        val binding = binding ?: return
        updateButtonState(
            binding.subscriptionRefresh,
            refreshingSubscription,
            R.string.cchr_refresh_subscription,
            R.string.cchr_refreshing
        )
        updateButtonState(
            binding.latencyTest,
            testingLatency,
            R.string.cchr_test_latency,
            R.string.cchr_testing_latency
        )
        if (group == null || group.type != GroupType.SUBSCRIPTION) {
            setSubscriptionStatus(R.string.cchr_subscription_not_activated)
            binding.subscriptionUsage.text = getString(R.string.cchr_usage_used_only, 0L.toBytesString())
            binding.usageProgress.progress = 0
            binding.subscriptionExpire.setText(R.string.cchr_expire_unknown)
            binding.expireProgress.progress = 0
            renderNodeSelector(null, emptyList())
            return
        }

        val usage = PrivateSubscriptionManager.parseUsageInfo(group.subscription?.subscriptionUserinfo)
        setSubscriptionStatus(
            when {
                GroupUpdater.updating.contains(group.id) -> R.string.cchr_subscription_updating
                usage.isExpired -> R.string.cchr_subscription_expired
                usage.isExhausted -> R.string.cchr_subscription_exhausted
                PrivateSubscriptionManager.defaultUpdateFailed -> R.string.cchr_subscription_update_failed
                else -> R.string.cchr_subscription_updated
            }
        )

        if (usage.total > 0L) {
            binding.subscriptionUsage.text = getString(
                R.string.cchr_usage_used_total,
                usage.used.coerceAtLeast(0L).toBytesString(),
                usage.total.toBytesString()
            )
            val usagePercent = ((usage.used.coerceAtLeast(0L).toDouble() / usage.total) * 100)
                .roundToInt()
                .coerceIn(0, 100)
            binding.usageProgress.progress = usagePercent
            binding.usageProgress.setIndicatorColor(usageColor(usagePercent))
        } else {
            binding.subscriptionUsage.text = getString(
                R.string.cchr_usage_used_only,
                usage.used.coerceAtLeast(0L).toBytesString()
            )
            binding.usageProgress.progress = 0
            binding.usageProgress.setIndicatorColor(usageColor(0))
        }

        val daysRemaining = daysRemaining(usage.expire)
        when {
            usage.expire <= 0L -> {
                binding.subscriptionExpire.setText(R.string.cchr_expire_unknown)
                binding.expireProgress.progress = 0
            }

            daysRemaining <= 0 -> {
                binding.subscriptionExpire.setText(R.string.cchr_expired)
                binding.expireProgress.progress = 0
            }

            else -> {
                binding.subscriptionExpire.text =
                    resources.getQuantityString(
                        R.plurals.cchr_expire_days_remaining,
                        daysRemaining,
                        daysRemaining
                    )
                binding.expireProgress.progress = (daysRemaining.coerceAtMost(30) * 100 / 30)
            }
        }
        binding.expireProgress.setIndicatorColor(themeColor(R.attr.colorPrimary))
        binding.expireProgress.trackColor = themeColor(R.attr.colorMaterial100)

        renderNodeSelector(proxy, proxies)
    }

    private fun setSubscriptionStatus(statusRes: Int) {
        binding?.subscriptionStatus?.text =
            "${getString(R.string.cchr_subscription_overview)}: ${getString(statusRes)}"
    }

    private fun renderNodeSelector(proxy: ProxyEntity?, proxies: List<ProxyEntity>) {
        val binding = binding ?: return
        binding.nodeSelectorCurrent.text = when {
            proxy != null -> proxyDisplayName(proxy, 0)
            proxies.isEmpty() -> getString(R.string.cchr_no_available_nodes)
            else -> proxyDisplayName(proxies.first(), 0)
        }
        val (text, color) = latencyDisplay(proxy)
        binding.nodeSelectorLatency.text = text
        binding.nodeSelectorLatency.setTextColor(color)
        binding.nodeSelectorCard.isEnabled = proxies.isNotEmpty()
    }

    private fun latencyDisplay(proxy: ProxyEntity?): Pair<String, Int> {
        return when {
            proxy == null -> getString(R.string.cchr_latency_untested) to secondaryTextColor()
            proxy.status == 1 && proxy.ping > 0 -> getString(R.string.available, proxy.ping) to latencyColor(proxy.ping)
            proxy.status > 1 -> getString(R.string.unavailable) to ContextCompat.getColor(requireContext(), R.color.material_red_500)
            else -> getString(R.string.cchr_latency_untested) to secondaryTextColor()
        }
    }

    private fun showNodeSelector() {
        val activity = activity as? MainActivity ?: return
        runOnDefaultDispatcher {
            val proxies = PrivateSubscriptionManager.getDefaultProxies()
            val selected = PrivateSubscriptionManager.getSelectedDefaultProxy()
            onMainDispatcher {
                if (proxies.isEmpty()) {
                    activity.snackbar(R.string.cchr_subscription_no_nodes).show()
                    return@onMainDispatcher
                }
                val names = proxies.mapIndexed { index, proxy -> proxyDisplayName(proxy, index) }.toTypedArray()
                val checked = proxies.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.cchr_select_node)
                    .setSingleChoiceItems(names, checked) { dialog, which ->
                        dialog.dismiss()
                        val target = proxies[which]
                        runOnDefaultDispatcher {
                            val ok = PrivateSubscriptionManager.selectDefaultProxy(target.id)
                            onMainDispatcher {
                                if (ok) reload()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun refreshSubscription() {
        val activity = activity as? MainActivity ?: return
        if (refreshingSubscription) return
        refreshingSubscription = true
        reload()
        runOnDefaultDispatcher {
            val ok = PrivateSubscriptionManager.refreshDefaultSubscription(activity, showError = false)
            onMainDispatcher {
                refreshingSubscription = false
                activity.snackbar(
                    if (ok) R.string.cchr_subscription_refresh_success
                    else R.string.cchr_subscription_update_failed
                ).show()
                reload()
                reloadAnnouncement()
            }
        }
    }

    private fun testLatency() {
        val activity = activity as? MainActivity ?: return
        if (testingLatency) return
        testingLatency = true
        reload()
        runOnDefaultDispatcher {
            val proxy = PrivateSubscriptionManager.getSelectedDefaultProxy()
            val updated = proxy?.let {
                if (DataStore.serviceState.connected) {
                    try {
                        PrivateSubscriptionManager.recordDefaultProxyLatency(it.id, activity.urlTest(), null)
                    } catch (e: Throwable) {
                        PrivateSubscriptionManager.recordDefaultProxyLatency(it.id, 0, e.readableMessage)
                    }
                } else {
                    PrivateSubscriptionManager.testDefaultProxyLatency(it.id)
                    PrivateSubscriptionManager.getSelectedDefaultProxy()?.takeIf { updated -> updated.id == it.id }
                }
            }
            onMainDispatcher {
                testingLatency = false
                activity.snackbar(
                    if (updated?.status == 1 && updated.ping > 0) R.string.cchr_latency_test_success
                    else R.string.cchr_latency_test_failed
                ).show()
                reload()
            }
        }
    }

    private fun changeInviteCode() {
        val activity = activity as? MainActivity ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cchr_change_invite)
            .setMessage(R.string.cchr_change_invite_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                startActivity(
                    Intent(activity, InviteCodeActivity::class.java)
                        .putExtra(InviteCodeActivity.EXTRA_REPLACE, true)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reloadAnnouncement() {
        if (loadingAnnouncement) return
        loadingAnnouncement = true
        runOnDefaultDispatcher {
            val announcement = PrivateSubscriptionManager.fetchAnnouncement()
            onMainDispatcher {
                loadingAnnouncement = false
                renderAnnouncement(announcement)
            }
        }
    }

    private fun renderAnnouncement(announcement: PrivateSubscriptionManager.Announcement?) {
        val binding = binding ?: return
        binding.announcementCard.isVisible = announcement != null
        if (announcement == null) return

        binding.announcementTitle.text =
            announcement.title.ifBlank { getString(R.string.cchr_announcement) }
        binding.announcementContent.text = announcement.content
        binding.announcementUpdatedAt.isVisible = announcement.updatedAt.isNotBlank()
        if (announcement.updatedAt.isNotBlank()) {
            binding.announcementUpdatedAt.text =
                getString(R.string.cchr_announcement_updated_at, announcement.updatedAt)
        }
    }

    private fun updateButtonState(
        button: MaterialButton,
        loading: Boolean,
        normalText: Int,
        loadingText: Int,
    ) {
        button.isEnabled = !loading
        button.setText(if (loading) loadingText else normalText)
    }

    private fun daysRemaining(expire: Long): Int {
        if (expire <= 0L) return 0
        val seconds = expire - System.currentTimeMillis() / 1000
        if (seconds <= 0L) return 0
        return ((seconds + 86399L) / 86400L).toInt().coerceAtLeast(1)
    }

    private fun usageColor(percent: Int): Int {
        return ContextCompat.getColor(
            requireContext(),
            when {
                percent < 80 -> R.color.material_green_500
                percent < 90 -> R.color.material_orange_500
                else -> R.color.material_red_500
            }
        )
    }

    private fun latencyColor(ping: Int): Int {
        return ContextCompat.getColor(
            requireContext(),
            when {
                ping < 150 -> R.color.material_green_500
                ping <= 250 -> R.color.material_amber_500
                else -> R.color.material_red_500
            }
        )
    }

    private fun secondaryTextColor(): Int {
        return themeColor(android.R.attr.textColorSecondary)
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(requireContext(), value.resourceId)
        } else {
            value.data
        }
    }

    private fun proxyDisplayName(proxy: ProxyEntity, index: Int): String {
        return runCatching { proxy.displayName().takeIf { it.isNotBlank() } }
            .getOrNull()
            ?: getString(R.string.cchr_node_label, index + 1)
    }

    override suspend fun groupAdd(group: ProxyGroup) {
        reload()
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        reload()
    }

    override suspend fun groupRemoved(groupId: Long) {
        reload()
    }

    override suspend fun groupUpdated(groupId: Long) {
        reload()
    }
}
