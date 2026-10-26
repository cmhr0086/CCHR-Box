package io.nekohasekai.sagernet.cchr

import io.nekohasekai.sagernet.database.ProxyEntity

object DefaultProxyDisplay {
    fun name(proxy: ProxyEntity, fallback: String, peers: List<ProxyEntity> = emptyList()): String {
        val rawName = runCatching { proxy.displayName().trim() }.getOrDefault("")
        return name(rawName, fallback, peers.map { it.displayName().trim() })
    }

    internal fun name(rawName: String, fallback: String, peers: List<String>): String {
        val shortName = rawName.substringBefore('|').trim()
        // Different nodes can share the prefix hidden by the compact label.
        val visibleName = if (peers.count { it.substringBefore('|').trim() == shortName } > 1) {
            rawName.trim()
        } else shortName
        return visibleName.ifBlank { rawName.ifBlank { fallback } }
    }
}
