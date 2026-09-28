package com.nebulaforge.core.session

import java.io.File

/**
 * Project-level language discovery. It only discovers requirements; it never claims a
 * Language Server is installed or running. Runtime startup remains owned by
 * LanguageServiceRegistry.
 */
data class ProjectLanguageRequirement(
    val languageId: String,
    val displayName: String,
    val files: List<String>,
    val installedCommandDetected: Boolean
)

class ProjectLanguageProfile(private val registry: LanguageServiceRegistry) {
    fun discover(projectRoot: File, maxFiles: Int = 3000): List<ProjectLanguageRequirement> {
        if (!projectRoot.isDirectory) return emptyList()
        val grouped = linkedMapOf<String, MutableList<String>>()
        var count = 0
        projectRoot.walkTopDown()
            .onEnter { dir ->
                val n = dir.name
                n != ".git" && n != ".gradle" && n != "build" && n != "node_modules" && n != ".dart_tool" && n != ".idea"
            }
            .forEach { file ->
                if (count >= maxFiles || !file.isFile) return@forEach
                val descriptor = registry.descriptorFor(file) ?: return@forEach
                count++
                grouped.getOrPut(descriptor.id) { mutableListOf() }.add(file.absolutePath)
            }
        return grouped.mapNotNull { (id, files) ->
            val d = registry.findDescriptor(id) ?: return@mapNotNull null
            ProjectLanguageRequirement(id, d.displayName, files.take(20), registry.isCommandAvailable(d))
        }
    }
}
