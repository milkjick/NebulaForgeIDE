package com.nebulaforge.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.nebulaforge.core.agent.AiAttachment
import com.nebulaforge.core.agent.AiAttachmentKind
import com.nebulaforge.core.agent.AiAttachmentTextExtractor
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把系统选择器返回的 `content://` / `file://` URI 变成一个可直接送模型的 [AiAttachment]。
 *
 * 三件必须做对的事：
 *  1. **复制进应用私有目录**：`content://` 的读权限只在本次 Intent 生命周期内有效，
 *     会话持久化后还要能再次分析，所以附件必须落成应用自己的文件；
 *  2. **图片压缩**：手机直出照片 5–10MB，base64 后会把上下文吃干净，统一压到长边
 *     [MAX_IMAGE_EDGE] 再编码 JPEG，既看清内容又不至于爆预算；
 *  3. **文本抽取**：源码/日志这类直接读文本，图片走多模态、二进制只带路径给命令工具。
 */
object AttachmentPreparer {

    /** 图片长边上限：再大对模型识别没有收益，只会浪费上下文。 */
    const val MAX_IMAGE_EDGE = 1280

    /** 单张图片 base64 上限（约 1.5MB 二进制）：超过就继续降采样。 */
    private const val MAX_IMAGE_BASE64 = 2_000_000

    /** 单个附件体积上限：够放下整包 APK / 数据集，又不至于把手机存储和内存拖垮。 */
    const val MAX_FILE_BYTES = 512L * 1024 * 1024

    private const val TAG = "NbAttach"

    fun dir(context: Context): File =
        File(context.filesDir, "ai-attachments").apply { if (!exists()) mkdirs() }

    fun prepare(context: Context, uri: Uri, note: String = ""): AiAttachment? {
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "attachment"
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        // 选择器给出的体积（SAF 的 SIZE 列）先拦一道：超大文件在手机上复制/解析都没意义。
        val declared = declaredSize(context, uri)
        if (declared > MAX_FILE_BYTES) {
            android.util.Log.w(TAG, "拒绝超大附件 name=$name size=${declared / 1024 / 1024}MB")
            return null
        }
        android.util.Log.i(TAG, "开始处理附件 name=$name mime=$mime declared=${declared}B")
        val target = File(dir(context), "${System.currentTimeMillis()}-${sanitize(name)}")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            target.isFile && target.length() > 0
        }.getOrDefault(false)
        if (!copied) return null

        val kind = AiAttachmentTextExtractor.kindOf(name, mime)
        val size = target.length()
        return when (kind) {
            AiAttachmentKind.IMAGE -> {
                val base64 = encodeImage(target)
                if (base64.isNullOrBlank()) {
                    // 解不出图（可能是 heic 等）→ 退化成二进制附件，交给命令工具处理。
                    AiAttachment(
                        name = name, mime = mime, kind = AiAttachmentKind.BINARY,
                        path = target.absolutePath, sizeBytes = size,
                        note = note.ifBlank { "图片无法在本机解码，请用命令工具分析该路径" }
                    )
                } else {
                    AiAttachment(
                        name = name, mime = "image/jpeg", kind = AiAttachmentKind.IMAGE,
                        path = target.absolutePath, sizeBytes = size,
                        imageBase64 = base64, note = note
                    )
                }
            }
            AiAttachmentKind.TEXT -> {
                val text = AiAttachmentTextExtractor.extract(target, name, mime)
                AiAttachment(
                    name = name, mime = mime, kind = AiAttachmentKind.TEXT,
                    path = target.absolutePath, sizeBytes = size, text = text,
                    note = if (text.isBlank()) "未能抽取文本内容" else note
                )
            }
            else -> AiAttachment(
                name = name, mime = mime, kind = kind,
                path = target.absolutePath, sizeBytes = size,
                // 压缩包 / APK / 未知二进制：把「里面有什么」抽成文本一并送模型，
                // 这样即便 AI 没调用工具，也能直接回答包内容（附件才算真的被读到）。
                text = runCatching { AiAttachmentTextExtractor.extract(target, name, mime) }.getOrDefault(""),
                note = note
            )
        }
    }

    /** 已知路径（项目内文件 / 终端输出文件）直接构造附件。 */
    fun prepareFile(context: Context, file: File, mime: String = "", note: String = ""): AiAttachment? {
        if (!file.isFile) return null
        val name = file.name
        val kind = AiAttachmentTextExtractor.kindOf(name, mime)
        return when (kind) {
            AiAttachmentKind.IMAGE -> {
                val base64 = encodeImage(file)
                if (base64.isNullOrBlank()) AiAttachment(
                    name = name, mime = mime, kind = AiAttachmentKind.BINARY,
                    path = file.absolutePath, sizeBytes = file.length(), note = note
                ) else AiAttachment(
                    name = name, mime = "image/jpeg", kind = AiAttachmentKind.IMAGE,
                    path = file.absolutePath, sizeBytes = file.length(), imageBase64 = base64, note = note
                )
            }
            AiAttachmentKind.TEXT -> AiAttachment(
                name = name, mime = mime, kind = AiAttachmentKind.TEXT,
                path = file.absolutePath, sizeBytes = file.length(),
                text = AiAttachmentTextExtractor.extract(file, name, mime), note = note
            )
            else -> AiAttachment(
                name = name, mime = mime, kind = kind,
                path = file.absolutePath, sizeBytes = file.length(),
                text = runCatching { AiAttachmentTextExtractor.extract(file, name, mime) }.getOrDefault(""),
                note = note
            )
        }
    }

    /** 清理历史附件目录（会话删除/清空时调用），避免应用私有空间无限膨胀。 */
    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    fun remove(context: Context, path: String) {
        if (path.isBlank()) return
        runCatching {
            val file = File(path)
            if (file.parentFile?.absolutePath == dir(context).absolutePath) file.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return File(uri.path ?: "").name
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    /** SAF 的 SIZE 列（拿不到时返回 0，交给复制后的实际体积兜底）。 */
    private fun declaredSize(context: Context, uri: Uri): Long = runCatching {
        if (uri.scheme == "file") return File(uri.path ?: "").length()
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fff-]"), "_").take(80).ifBlank { "attachment" }

    /** 图片按长边降采样 + JPEG 质量压缩，输出 base64（不含 data URL 前缀）。 */
    private fun encodeImage(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap: Bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull() ?: return null

        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_IMAGE_EDGE) {
            val scale = MAX_IMAGE_EDGE.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        var quality = 85
        var bytes = compress(bitmap, quality)
        while (bytes.size > MAX_IMAGE_BASE64 && quality > 40) {
            quality -= 15
            bytes = compress(bitmap, quality)
        }
        bitmap.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
}
