package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.cchr.DefaultProxyDisplay
import io.nekohasekai.sagernet.cchr.PrivateSubscriptionManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutNodeSelectBinding
import io.nekohasekai.sagernet.databinding.LayoutNodeSelectItemBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class NodeSelectActivity : ThemedActivity() {

    private lateinit var binding: LayoutNodeSelectBinding
    private val nodesAdapter = NodesAdapter()
    private var nodes = emptyList<ProxyEntity>()
    private var selectedId: Long? = null
    private var saving = false
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutNodeSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cchr_select_node)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.nodeList.layoutManager = LinearLayoutManager(this)
        binding.nodeList.adapter = nodesAdapter
        binding.testAllNodes.setOnClickListener { testAllNodes() }
        binding.confirmSelection.setOnClickListener { confirmSelection() }
        loadNodes()
    }

    private fun loadNodes() {
        binding.confirmSelection.isEnabled = false
        binding.testAllNodes.isEnabled = false
        runOnDefaultDispatcher {
            val loadedNodes = PrivateSubscriptionManager.getDefaultProxies()
            onMainDispatcher {
                nodes = loadedNodes
                selectedId = null
                binding.emptyNodes.isVisible = nodes.isEmpty()
                binding.nodeList.isVisible = nodes.isNotEmpty()
                updateActionState()
                nodesAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun selectNode(proxy: ProxyEntity) {
        if (saving || selectedId == proxy.id) return
        selectedId = proxy.id
        updateActionState()
        nodesAdapter.notifyDataSetChanged()
    }

    private fun testAllNodes() {
        if (testing || nodes.isEmpty()) return
        testing = true
        updateActionState()
        binding.testAllNodes.setText(R.string.cchr_testing_all_nodes)
        runOnDefaultDispatcher {
            nodes.forEach { proxy ->
                PrivateSubscriptionManager.testDefaultProxyLatency(proxy.id)
                val updatedNodes = PrivateSubscriptionManager.getDefaultProxies()
                onMainDispatcher {
                    nodes = updatedNodes
                    nodesAdapter.notifyDataSetChanged()
                }
            }
            onMainDispatcher {
                testing = false
                binding.testAllNodes.setText(R.string.cchr_test_all_nodes)
                updateActionState()
            }
        }
    }

    private fun confirmSelection() {
        val targetId = selectedId ?: return
        if (saving) return
        saving = true
        updateActionState()
        binding.confirmSelection.setText(R.string.cchr_saving)
        runOnDefaultDispatcher {
            val saved = runCatching { PrivateSubscriptionManager.selectDefaultProxy(targetId) }
                .getOrDefault(false)
            onMainDispatcher {
                saving = false
                binding.confirmSelection.setText(R.string.cchr_confirm_selection)
                if (saved) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_PROFILE_ID, targetId))
                    finish()
                } else {
                    updateActionState()
                    snackbar(R.string.cchr_node_selection_save_failed).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)

    private fun updateActionState() {
        binding.testAllNodes.isEnabled = !testing && !saving && nodes.isNotEmpty()
        binding.confirmSelection.isEnabled = !testing && !saving && selectedId != null
    }

    private inner class NodeViewHolder(
        private val itemBinding: LayoutNodeSelectItemBinding,
    ) : RecyclerView.ViewHolder(itemBinding.root), View.OnClickListener {
        private lateinit var proxy: ProxyEntity

        init {
            itemBinding.root.setOnClickListener(this)
        }

        fun bind(item: ProxyEntity, position: Int) {
            proxy = item
            itemBinding.nodeName.text = DefaultProxyDisplay.name(
                item,
                getString(R.string.cchr_node_label, position + 1)
            )
            val (latency, color) = latencyDisplay(item)
            itemBinding.nodeLatency.text = latency
            itemBinding.nodeLatency.setTextColor(color)
            itemBinding.nodeRadio.isChecked = item.id == selectedId
        }

        override fun onClick(view: View?) {
            if (!testing) selectNode(proxy)
        }
    }

    private inner class NodesAdapter : RecyclerView.Adapter<NodeViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder =
            NodeViewHolder(LayoutNodeSelectItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = nodes.size

        override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
            holder.bind(nodes[position], position)
        }
    }

    private fun latencyDisplay(proxy: ProxyEntity): Pair<String, Int> = when {
        proxy.status == 1 && proxy.ping > 0 ->
            getString(R.string.available, proxy.ping) to latencyColor(proxy.ping)

        proxy.status > 1 ->
            getString(R.string.unavailable) to ContextCompat.getColor(this, R.color.material_red_500)

        else -> getString(R.string.cchr_latency_untested) to secondaryTextColor()
    }

    private fun latencyColor(ping: Int): Int = ContextCompat.getColor(
        this,
        when {
            ping < 150 -> R.color.material_green_500
            ping <= 250 -> R.color.material_amber_500
            else -> R.color.material_red_500
        }
    )

    private fun secondaryTextColor(): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
