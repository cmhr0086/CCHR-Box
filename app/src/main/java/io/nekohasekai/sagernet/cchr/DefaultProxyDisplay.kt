package io.nekohasekai.sagernet.cchr

import io.nekohasekai.sagernet.database.ProxyEntity

object DefaultProxyDisplay {
    fun name(proxy: ProxyEntity, fallback: String): String {
        val rawName = runCatching { proxy.displayName().trim() }.getOrDefault("")
        val visibleName = rawName.substringBefore('|').trim()
        return visibleName.ifBlank { rawName.ifBlank { fallback } }
    }
}
