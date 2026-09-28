package com.nebulaforge.core.agent.generate

import com.nebulaforge.core.projectmodel.ProjectTemplateGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 阶段4「项目骨架生成」的抽象。方案 6.2 中编排器注入的 `templateEngine`，
 * 在本仓库中等价于「模板引擎 + 依赖声明写入器」的组合。
 *
 * 实现必须把产物写入 **临时工作区**，绝不直接写真实项目目录（见 6.3 事务约束）。
 */
interface ProjectScaffolder {
    suspend fun scaffold(plan: ProjectPlan, stagingDir: File, packageName: String): ScaffoldResult

    /** 把 [ProjectPlan.dependencies] 写入目标技术栈的依赖声明文件 */
    suspend fun writeDependencies(plan: ProjectPlan, projectDir: File): List<String>
}

/** 基于 [ProjectTemplateGenerator] 的默认实现。 */
class TemplateProjectScaffolder : ProjectScaffolder {

    override suspend fun scaffold(plan: ProjectPlan, stagingDir: File, packageName: String): ScaffoldResult =
        withContext(Dispatchers.IO) {
            val templateId = plan.templateId.ifBlank {
                plan.projectType.templateIds().firstOrNull()
                    ?: error("技术栈 ${plan.projectType.id} 未声明任何模板")
            }
            stagingDir.mkdirs()
            ProjectTemplateGenerator.create(templateId, stagingDir, packageName)
            writeDependencies(plan, stagingDir)
            ScaffoldResult(
                stagingDir = stagingDir,
                packageName = packageName,
                files = stagingDir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(stagingDir).invariantSeparatorsPath }
                    .sorted()
                    .toList()
            )
        }

    override suspend fun writeDependencies(plan: ProjectPlan, projectDir: File): List<String> =
        withContext(Dispatchers.IO) {
            if (plan.dependencies.isEmpty()) return@withContext emptyList()
            when (plan.projectType.id) {
                "flutter" -> writePubspec(projectDir, plan.dependencies)
                "web-frontend", "web-backend" -> writePackageJson(projectDir, plan.dependencies)
                "cpp" -> writeCmake(projectDir, plan.dependencies)
                else -> writeGradle(projectDir, plan.dependencies)
            }
        }

    private fun writeGradle(projectDir: File, dependencies: List<String>): List<String> {
        val module = File(projectDir, "app").takeIf { it.isDirectory } ?: projectDir
        val target = listOf("build.gradle.kts", "build.gradle")
            .map { File(module, it) }
            .firstOrNull { it.isFile }
            ?: File(module, "build.gradle.kts").also { it.parentFile?.mkdirs(); it.writeText("") }
        val text = target.readText()
        val block = dependencies.joinToString("\n") { "    implementation(\"$it\")" }
        val updated = if (text.contains("dependencies {")) {
            text.replaceFirst("dependencies {", "dependencies {\n$block")
        } else {
            text.trimEnd() + "\n\ndependencies {\n$block\n}\n"
        }
        target.writeText(updated)
        return listOf(target.relativeTo(projectDir).invariantSeparatorsPath)
    }

    private fun writePubspec(projectDir: File, dependencies: List<String>): List<String> {
        val target = File(projectDir, "pubspec.yaml")
        val marker = "dev_dependencies:"
        val lines = dependencies.joinToString("\n") { "  ${it.substringBefore(':')}: ^${it.substringAfter(':').substringAfter(':').ifBlank { "any" }}" }
        val text = target.readText()
        val updated = if (text.contains(marker)) text.replace(marker, "$lines\n$marker") else text.trimEnd() + "\ndependencies:\n$lines\n"
        target.writeText(updated)
        return listOf("pubspec.yaml")
    }

    private fun writePackageJson(projectDir: File, dependencies: List<String>): List<String> {
        val target = File(projectDir, "package.json")
        val root = runCatching { JSONObject(target.readText()) }.getOrElse { JSONObject() }
        val deps = root.optJSONObject("dependencies") ?: JSONObject().also { root.put("dependencies", it) }
        dependencies.forEach { spec ->
            val name = spec.substringBefore(':')
            deps.put(name, spec.substringAfter(':').ifBlank { "latest" })
        }
        target.writeText(root.toString(2) + "\n")
        return listOf("package.json")
    }

    private fun writeCmake(projectDir: File, dependencies: List<String>): List<String> {
        val target = File(projectDir, "CMakeLists.txt")
        val block = dependencies.joinToString("\n") { "find_package(${it.substringBefore(':')} REQUIRED)" }
        target.writeText(target.readText().trimEnd() + "\n\n" + block + "\n")
        return listOf("CMakeLists.txt")
    }
}
