package com.nebulaforge.core.session

import java.net.InetSocketAddress
import java.net.ServerSocket

object PortAllocator {
    fun isAvailable(port: Int): Boolean {
        if (port !in 1..65535) return false
        return runCatching { ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)); true } }.getOrDefault(false)
    }

    fun allocate(preferred: Int? = null, rangeStart: Int = 3000, rangeEnd: Int = 3999): Int? {
        preferred?.takeIf { isAvailable(it) }?.let { return it }
        for (p in rangeStart..rangeEnd) if (isAvailable(p)) return p
        return null
    }
}
