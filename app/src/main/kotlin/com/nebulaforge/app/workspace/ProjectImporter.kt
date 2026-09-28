package com.nebulaforge.app.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 项目导入器：把用户通过系统文件选择器选中的内容落到项目根目录。
 *
 * 为什么走 SAF（ContentResolver + DocumentsContract）而不是直接按路径读：
 * 直读公共存储需要 READ_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE 运行时权限，
 * 该权限默认未授予（本机实测 granted=false），一旦依赖它就等于「导入功能默认不可用」。
 * SAF 由系统文件选择器授予读权限，用户选什么就能读什么，不依赖敏感存储权限。
 *
 * 两个入口：
 *  - [importFolder]：导入本地文件夹，**递归保留完整目录结构**。
 *    旧实现只复制所选目录的第一层文件（源码里自己标注了「简化：只做单层导入」），
 *    导进 Gradle/Kotlin 工程后会得到一堆被打散的源文件，这是用户反馈的核心问题。
 *  - [importZip]：导入压缩包（zip），带 Zip Slip 防护，并自动剥离压缩包常见的单一外壳目录。
 */
object ProjectImporter {

    data class Result(val target: File, val files: Int, val skipped: Int)

    /** 进度节流：每 N 个文件回调一次，避免超大工程把 UI 状态更新淹没。 */
    private const val PROGRESS_STEP = 40

    /** 从 SAF 目录树递归导入，目录名取所选文件夹名。 */
    fun importFolder(
        context: Context,
        treeUri: Uri,
        destRoot: File,
        nameHint: String? = null,
        onProgress: (String) -> Unit = {}
    ): Result {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val base = safeName(nameHint?.takeIf { it.isNotBlank() } ?: "imported-project")
        val target = File(destRoot, uniqueName(destRoot, base))
        return copyTree(context, treeUri, rootDocId, target, onProgress)
    }

    /** 从 zip / 压缩包导入。 */
    fun importZip(
        context: Context,
        zipUri: Uri,
        destRoot: File,
        nameHint: String? = null,
        onProgress: (String) -> Unit = {}
    ): Result {
        destRoot.mkdirs()
        // 先解到同目录下的暂存目录：解压过程中出现异常时不会污染项目根目录，
        // 也便于「剥掉外壳目录」后再一次性改名成项目目录。
        val staging = File(destRoot, ".import-zip-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            var files = 0
            context.contentResolver.openInputStream(zipUri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name.replace('\\', '/')
                        val out = File(staging, entryName)
                        // Zip Slip 防护：条目解析后的真实路径必须仍落在暂存目录内
                        if (out.canonicalPath != staging.canonicalPath &&
                            !out.canonicalPath.startsWith(staging.canonicalPath + File.separator)
                        ) {
                            throw IOException("压缩包内条目路径非法：$entryName")
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                            files++
                            if (files % PROGRESS_STEP == 0) onProgress("已解压 $files 个文件…")
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: throw IOException("无法读取所选压缩包（授权可能已失效）")

            // 压缩包常见形态是「项目名/…」单层包裹，剥掉它，避免导入后凭空多一层目录
            val tops = staging.listFiles()?.filterNot { it.name == "__MACOSX" }.orEmpty()
            val content = if (tops.size == 1 && tops[0].isDirectory) tops[0] else staging
            val rawName = nameHint?.substringAfterLast('/') ?: content.name
            val base = safeName(rawName.removeSuffix(".zip").removeSuffix(".ZIP"))
            val target = File(destRoot, uniqueName(destRoot, base))
            // 同分区优先改名（秒级完成），失败再退回复制
            if (!content.renameTo(target)) content.copyRecursively(target, overwrite = true)
            return Result(target, files, 0)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 读取 SAF 文档的显示名（压缩包项目命名用）。 */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun copyTree(
        context: Context,
        treeUri: Uri,
        rootDocId: String,
        target: File,
        onProgress: (String) -> Unit
    ): Result {
        target.mkdirs()
        var files = 0
        var skipped = 0

        // 先把子项读成列表再递归：避免在遍历同一个 Cursor 的过程中重入 query。
        fun walk(parentDocId: String, dir: File) {
            queryChildren(context, treeUri, parentDocId).forEach { (docId, name, mime) ->
                val clean = safeName(name)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val sub = File(dir, clean)
                    sub.mkdirs()
                    walk(docId, sub)
                } else {
                    val file = File(dir, clean)
                    val ok = runCatching {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        context.contentResolver.openInputStream(docUri)?.use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取 $name")
                    }.isSuccess
                    if (ok) {
                        files++
                        if (files % PROGRESS_STEP == 0) onProgress("已导入 $files 个文件…")
                    } else {
                        skipped++
                    }
                }
            }
        }

        walk(rootDocId, target)
        return Result(target, files, skipped)
    }

    private fun queryChildren(
        context: Context,
        treeUri: Uri,
        parentDocId: String
    ): List<Triple<String, String, String?>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = mutableListOf<Triple<String, String, String?>>()
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    out += Triple(id, name, cursor.getString(2))
                }
            }
        }
        return out
    }

    /** 清洗文件/目录名：去掉路径分隔符等非法字符，避免导入时越界或产生无法打开的名字。 */
    private fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
        return cleaned.take(120).ifBlank { "imported-project" }
    }

    /** 同名项目自动追加序号，绝不覆盖用户已有项目。 */
    private fun uniqueName(root: File, base: String): String {
        if (!File(root, base).exists()) return base
        var index = 2
        while (File(root, "$base-$index").exists()) index++
        return "$base-$index"
    }
}
