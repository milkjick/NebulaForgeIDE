package com.nebulaforge.core.projectmodel

import java.io.File

/**
 * 项目发现与 Android 构建需求解析器。
 *
 * 不通过文件名猜项目类型：先调用 ProjectTypeRegistry，再读取实际 Gradle 配置，
 * 为工具链解析器提供 compileSdk / buildTools / NDK 等可验证输入。
 */
data class ProjectDescriptor(
    val root: File,
    val type: ProjectType,
    val buildSystem: BuildSystem,
    val settingsFile: File?,
    val gradleRootBuildFile: File?,
    val wrapperProperties: File?,
    val metadata: ProjectMetadata
)

data class ProjectMetadata(
    val compileSdk: Int? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val buildToolsVersion: String? = null,
    val ndkVersion: String? = null,
    val namespace: String? = null,
    val applicationId: String? = null
)

class ProjectResolver {
    fun resolve(projectRoot: File): ProjectDescriptor? {
        val root = projectRoot.canonicalFile
        if (!root.isDirectory) return null
        val type = ProjectTypeRegistry.detect(root) ?: return null
        val settings = sequenceOf(File(root, "settings.gradle.kts"), File(root, "settings.gradle"))
            .firstOrNull { it.isFile }
        val build = sequenceOf(File(root, "build.gradle.kts"), File(root, "build.gradle"))
            .firstOrNull { it.isFile }
        val wrapper = File(root, "gradle/wrapper/gradle-wrapper.properties").takeIf { it.isFile }
        return ProjectDescriptor(
            root = root,
            type = type,
            buildSystem = type.createBuildSystem(),
            settingsFile = settings,
            gradleRootBuildFile = build,
            wrapperProperties = wrapper,
            metadata = parseMetadata(root)
        )
    }

    private fun parseMetadata(root: File): ProjectMetadata {
        val files = root.walkTopDown().filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }.take(200).toList()
        val text = files.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        return ProjectMetadata(
            compileSdk = firstInt(text, listOf("compileSdk", "compileSdkVersion")),
            minSdk = firstInt(text, listOf("minSdk", "minSdkVersion")),
            targetSdk = firstInt(text, listOf("targetSdk", "targetSdkVersion")),
            buildToolsVersion = firstString(text, "buildToolsVersion"),
            ndkVersion = firstString(text, "ndkVersion"),
            namespace = firstString(text, "namespace"),
            applicationId = firstString(text, "applicationId")
        )
    }

    private fun firstInt(text: String, keys: List<String>): Int? = keys.asSequence()
        .mapNotNull { key ->
            Regex("\\b${Regex.escape(key)}\\s*(?:=|\\()\\s*[\\\"']?(\\d+)", RegexOption.MULTILINE)
                .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.firstOrNull()

    private fun firstString(text: String, key: String): String? = Regex(
        "\\b${Regex.escape(key)}\\s*(?:=|\\()\\s*[\\\"']([^\\\"']+)[\\\"']",
        RegexOption.MULTILINE
    ).find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
}
