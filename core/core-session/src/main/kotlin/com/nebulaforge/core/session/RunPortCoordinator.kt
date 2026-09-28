package com.nebulaforge.core.session

import com.nebulaforge.core.projectmodel.RunConfigurationSpec

/** Allocates ports at launch time and returns a copy; never silently reuses a busy port. */
object RunPortCoordinator {
    fun resolve(spec: RunConfigurationSpec): Result<RunConfigurationSpec> {
        val port = spec.port ?: return Result.success(spec)
        if (PortAllocator.isAvailable(port)) return Result.success(spec)
        val allocated = PortAllocator.allocate(null, maxOf(3000, port), minOf(3999, port + 999))
            ?: PortAllocator.allocate(null)
        return allocated?.let { Result.success(spec.copy(port = it)) }
            ?: Result.failure(IllegalStateException("端口 ${spec.port} 被占用且没有可用替代端口"))
    }
}
