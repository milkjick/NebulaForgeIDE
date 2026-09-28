package com.nebulaforge.core.agent.generate

import java.io.File

/**
 * 生成过程的文件系统事务（开发方案第 6.3 节）。
 *
 * 所有生成阶段的文件写入先写入临时工作区（`.nebulaforge/tmp/<sessionId>/`），
 * 只有用户在 DeliveryReview 阶段最终确认后，才通过 [commit] 移动到真实项目目录；
 * 用户可随时 [rollback] 丢弃整个临时工作区，不影响原项目任何文件。
 *
 * 注意：临时工作区放在 `realProjectDir.parentFile` 之下（与方案一致），
 * 因此它位于项目之外，不会被项目自身扫描/构建误当作源码。
 */
class GenerationTransaction(
    private val sessionId: String,
    private val realProjectDir: File
) {
    /** 真实项目目录（构建/提交的对象） */
    val projectDir: File get() = realProjectDir

    /** 临时工作区根目录 */
    val tempDir: File = File(realProjectDir.parentFile, ".nebulaforge/tmp/$sessionId")

    /** 将生成内容写入临时工作区；路径必须落在工作区内（防目录穿越） */
    fun stageFile(relativePath: String, content: String) {
        val target = resolveInside(tempDir, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    /** 读取临时工作区内已暂存的文件内容 */
    fun stagedContent(relativePath: String): String? =
        resolveInside(tempDir, relativePath).takeIf { it.isFile }?.readText()

    /** 列出临时工作区内全部暂存文件（相对路径，使用 '/' 分隔） */
    fun stagedPaths(): List<String> =
        tempDir.takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.map { it.relativeTo(tempDir).invariantSeparatorsPath }
            ?.sorted()
            ?.toList()
            ?: emptyList()

    /**
     * 生成 Diff 摘要清单，供 6.4 审查 UI 使用。
     * 逐文件判定「新增 / 修改 / 内容相同」，并给出与真实项目现有文件的行数差。
     */
    fun changeSummary(): List<FileChange> = stagedPaths().map { path ->
        val staged = resolveInside(tempDir, path)
        val existing = resolveInside(realProjectDir, path)
        val newLines = runCatching { staged.readText().lineSequence().count() }.getOrDefault(0)
        val summary = when {
            !existing.isFile -> "新增文件，共 $newLines 行"
            runCatching { existing.readText() == staged.readText() }.getOrDefault(false) -> "内容与现有文件相同"
            else -> {
                val oldLines = runCatching { existing.readText().lineSequence().count() }.getOrDefault(0)
                "修改文件，$oldLines 行 → $newLines 行"
            }
        }
        FileChange(path, summary, FileChange.ChangeStatus.PENDING)
    }

    /** 用户确认后，把被接受的文件移动到真实项目目录，并丢弃（删除）整个临时工作区 */
    fun commit(acceptedPaths: Set<String>) {
        acceptedPaths.forEach { path ->
            val src = resolveInside(tempDir, path)
            if (!src.isFile) return@forEach
            val dst = resolveInside(realProjectDir, path)
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
        tempDir.deleteRecursively()
    }

    /** 丢弃整个临时工作区，不影响原项目任何文件 */
    fun rollback() {
        tempDir.deleteRecursively()
    }

    private fun resolveInside(base: File, relativePath: String): File {
        val baseCanonical = base.canonicalFile
        val candidate = File(baseCanonical, relativePath).canonicalFile
        require(
            candidate.path == baseCanonical.path ||
                candidate.path.startsWith(baseCanonical.path + File.separator)
        ) { "非法路径（越出工作区）：$relativePath" }
        return candidate
    }
}
