package io.nekohasekai.sagernet.cchr

import org.junit.Assert.*
import org.junit.Test

class DefaultProxyDisplayTest {
    @Test fun collidingShortLabelsKeepTheDistinguishingSuffix() {
        val peers = listOf("Hong Kong | A", "Hong Kong | B", "Tokyo | C")
        assertEquals("Hong Kong | A", DefaultProxyDisplay.name(peers[0], "Node 1", peers))
        assertEquals("Hong Kong | B", DefaultProxyDisplay.name(peers[1], "Node 2", peers))
        assertEquals("Tokyo", DefaultProxyDisplay.name(peers[2], "Node 3", peers))
    }

    @Test fun emptyNameUsesFallback() {
        assertEquals("Node 1", DefaultProxyDisplay.name("", "Node 1", emptyList()))
    }
}
