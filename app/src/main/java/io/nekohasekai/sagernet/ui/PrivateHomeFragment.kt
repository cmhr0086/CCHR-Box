package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.cchr.PrivateSubscriptionManager
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutPrivateHomeBinding
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString

class PrivateHomeFragment : ToolbarFragment(R.layout.layout_private_home), GroupManager.Listener {

    private var binding: LayoutPrivateHomeBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutPrivateHomeBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_configuration)
        GroupManager.addListener(this)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        GroupManager.removeListener(this)
        binding = null
        super.onDestroyView()
    }

    private fun reload() {
        runOnDefaultDispatcher {
            val group = PrivateSubscriptionManager.getDefaultSubscription()
            val proxies = group?.let { SagerDatabase.proxyDao.getByGroup(it.id) }.orEmpty()
            onMainDispatcher {
                bind(group, proxies)
            }
        }
    }

    private fun bind(group: ProxyGroup?, proxies: List<ProxyEntity>) {
        val binding = binding ?: return
        if (group == null || group.type != GroupType.SUBSCRIPTION) {
            binding.subscriptionStatus.setText(R.string.cchr_subscription_not_activated)
            binding.subscriptionDetail.text = getString(R.string.cchr_subscription_nodes, 0)
            renderLatencyRows(emptyList())
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
        binding.subscriptionDetail.text = buildString {
            append(getString(R.string.cchr_subscription_nodes, proxies.size))
            append('\n')
            val updated = group.subscription?.lastUpdated ?: 0
            append(
                if (updated > 0) {
                    getString(
                        R.string.cchr_subscription_updated_at,
                        Util.timeStamp2Text(updated.toLong() * 1000)
                    )
                } else {
                    getString(R.string.cchr_subscription_never_updated)
                }
            )
            if (usage.used > 0L || usage.total > 0L) {
                append('\n')
                append(
                    if (usage.remaining > 0L) {
                        getString(
                            R.string.subscription_traffic,
                            usage.used.toBytesString(),
                            usage.remaining.toBytesString()
                        )
                    } else {
                        getString(R.string.subscription_used, usage.used.toBytesString())
                    }
                )
            }
            if (usage.expire > 0L) {
                append('\n')
                append(
                    getString(
                        R.string.subscription_expire,
                        Util.timeStamp2Text(usage.expire * 1000)
                    )
                )
            }
        }
        renderLatencyRows(proxies)
    }

    private fun renderLatencyRows(proxies: List<ProxyEntity>) {
        val container = binding?.latencyContainer ?: return
        container.removeAllViews()
        proxies.forEachIndexed { index, proxy ->
            container.addView(
                createLatencyRow(
                    getString(R.string.cchr_node_label, index + 1),
                    when {
                        proxy.status == 1 && proxy.ping > 0 -> getString(R.string.available, proxy.ping)
                        proxy.status > 1 -> getString(R.string.unavailable)
                        else -> getString(R.string.cchr_latency_untested)
                    }
                )
            )
        }
    }

    private fun createLatencyRow(name: String, latency: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val vertical = (10 * resources.displayMetrics.density).toInt()
            setPadding(0, vertical, 0, vertical)
            addView(TextView(context).apply {
                text = name
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1F)
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
            })
            addView(TextView(context).apply {
                text = latency
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
            })
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
