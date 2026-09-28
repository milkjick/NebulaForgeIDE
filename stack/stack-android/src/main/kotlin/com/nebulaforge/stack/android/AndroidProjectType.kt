package com.nebulaforge.stack.android

import android.content.Context
import com.nebulaforge.core.projectmodel.*
import java.io.File

class AndroidProjectType(private val context: Context? = null) : ProjectType {
    override val id = "android"
    override val displayNameKey = "android"

    override fun detect(projectRoot: File): Boolean {
        val settings = sequenceOf(File(projectRoot, "settings.gradle.kts"), File(projectRoot, "settings.gradle"))
            .any { it.isFile }
        if (!settings) return false
        return projectRoot.walkTopDown().take(300).filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .any { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                text.contains("com.android.application") || text.contains("com.android.library") || text.contains("com.android.dynamic-feature")
            }
    }

    override fun createBuildSystem(): BuildSystem = GradleAndroidBuildSystem(context)
    override fun createRunConfiguration() = AndroidRunConfiguration()
    override fun requiredLanguageServiceIds(): List<String> = listOf("kotlin", "java", "xml")
    override fun templateIds(): List<String> = listOf("android-empty", "android-empty-compose")
}
