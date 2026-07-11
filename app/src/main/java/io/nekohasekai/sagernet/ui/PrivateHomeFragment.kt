package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.cchr.PrivateSubscriptionManager
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.databinding.LayoutPrivateHomeBinding
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import moe.matsuri.nb4a.utils.toBytesString
import kotlin.math.roundToInt

class PrivateHomeFragment : ToolbarFragment(R.layout.layout_private_home), GroupManager.Listener {

    private var binding: LayoutPrivateHomeBinding? = null
    private var refreshingSubscription = false
    private var testingLatency = false
    private var changingInvite = false
    private var loadingAnnouncement = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutPrivateHomeBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_configuration)
        GroupManager.addListener(this)
        binding?.subscriptionRefresh?.setOnClickListener { refreshSubscription() }
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
            onMainDispatcher {
                bind(group, proxy)
            }
        }
    }

    private fun bind(group: ProxyGroup?, proxy: ProxyEntity?) {
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
        binding.changeInvite.isEnabled = !changingInvite

        if (group == null || group.type != GroupType.SUBSCRIPTION) {
            binding.subscriptionStatus.setText(R.string.cchr_subscription_not_activated)
            binding.subscriptionUsage.text = getString(R.string.cchr_usage_used_only, 0L.toBytesString())
            binding.usageProgress.progress = 0
            binding.subscriptionExpire.setText(R.string.cchr_expire_unknown)
            binding.expireProgress.progress = 0
            renderLatency(proxy)
            return
        }

        val usage = PrivateSubscriptionManager.parseUsageInfo(group.subscription?.subscriptionUserinfo)
        binding.subscriptionStatus.setText(
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
        binding.expireProgress.setIndicatorColor(ContextCompat.getColor(requireContext(), android.R.color.white))

        renderLatency(proxy)
    }

    private fun renderLatency(proxy: ProxyEntity?) {
        val binding = binding ?: return
        binding.latencyName.setText(R.string.cchr_node_label_single)
        val (text, color) = when {
            proxy == null -> getString(R.string.cchr_latency_untested) to secondaryTextColor()
            proxy.status == 1 && proxy.ping > 0 -> getString(R.string.available, proxy.ping) to latencyColor(proxy.ping)
            proxy.status > 1 -> getString(R.string.unavailable) to ContextCompat.getColor(requireContext(), R.color.material_red_500)
            else -> getString(R.string.cchr_latency_untested) to secondaryTextColor()
        }
        binding.latencyValue.text = text
        binding.latencyValue.setTextColor(color)
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
            if (proxy != null) {
                PrivateSubscriptionManager.testDefaultProxyLatency(proxy.id)
            }
            val updated = proxy?.id?.let { id ->
                PrivateSubscriptionManager.getSelectedDefaultProxy()?.takeIf { it.id == id }
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
        if (changingInvite) return
        changingInvite = true
        binding?.changeInvite?.isEnabled = false
        runOnDefaultDispatcher {
            val ok = PrivateSubscriptionManager.replaceDefaultSubscriptionWithInviteCode(activity)
            onMainDispatcher {
                changingInvite = false
                activity.snackbar(
                    if (ok) R.string.cchr_invite_changed
                    else R.string.cchr_invite_change_cancelled
                ).show()
                reload()
            }
        }
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
        val value = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(requireContext(), value.resourceId)
        } else {
            value.data
        }
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
