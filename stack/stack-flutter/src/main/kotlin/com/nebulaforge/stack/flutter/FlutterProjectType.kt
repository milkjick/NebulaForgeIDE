package com.nebulaforge.stack.flutter

import android.content.Context
import com.nebulaforge.core.projectmodel.*
import java.io.File

class FlutterProjectType(private val context: Context) : ProjectType {
    override val id = "flutter"
    override val displayNameKey = "flutter"

    override fun detect(projectRoot: File): Boolean {
        val pubspec = File(projectRoot, "pubspec.yaml")
        if (!pubspec.isFile) return false
        val text = runCatching { pubspec.readText() }.getOrDefault("")
        return Regex("(?m)^\\s*flutter\\s*:").containsMatchIn(text)
    }

    override fun createBuildSystem(): BuildSystem = FlutterBuildSystem(context)
    override fun createRunConfiguration() = FlutterRunConfiguration()
    override fun requiredLanguageServiceIds(): List<String> = listOf("dart")
    override fun templateIds(): List<String> = listOf("flutter-empty-app")
}
