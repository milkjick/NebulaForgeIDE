package com.nebulaforge.core.agent

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 附件类型：决定 UI 展示与「怎么喂给模型」。 */
enum class AiAttachmentKind { IMAGE, TEXT, ARCHIVE, BINARY }

/**
 * 一条用户附件。
 *
 * 送模型的方式分三类：
 *  - IMAGE：走多模态（[imageBase64] → data URL），三大协议都支持；
 *  - TEXT ：抽取正文后作为文本块拼进提问（任何模型都能读）；
 *  - BINARY/ARCHIVE：不进模型，只给路径 + 元数据，由模型按需调用 run_command 分析
 *    （例如 `unzip -l`、`ffprobe`、`aapt2 dump badging`），避免把二进制垃圾塞进上下文。
 *
 * [imageBase64] **不持久化**：会话 JSON 里存 base64 会让会话文件膨胀到几十 MB，
 * 而历史轮次的图片本来也不需要重复发送（只有当轮需要）。
 */
data class AiAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String = "",
    val kind: AiAttachmentKind = AiAttachmentKind.BINARY,
    val path: String = "",
    val sizeBytes: Long = 0,
    val text: String = "",
    val imageBase64: String = "",
    val note: String = ""
) {
    val isImage: Boolean get() = kind == AiAttachmentKind.IMAGE

    val sizeLabel: String get() = when {
        sizeBytes >= 1024L * 1024 -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
        sizeBytes >= 1024 -> "%.0f KB".format(sizeBytes / 1024.0)
        else -> "$sizeBytes B"
    }

    /** 输入框上方的小 chip 文案。 */
    val chipLabel: String get() = when (kind) {
        AiAttachmentKind.IMAGE -> "图 $name · $sizeLabel"
        AiAttachmentKind.TEXT -> "文 $name · ${text.length} 字"
        AiAttachmentKind.ARCHIVE -> "包 $name · $sizeLabel"
        AiAttachmentKind.BINARY -> "文件 $name · $sizeLabel"
    }

    /** 拼进提问正文的文本块（图片不拼文本，走多模态）。 */
    fun promptBlock(maxChars: Int = 40_000): String = buildString {
        append("\n\n【附件：").append(name).append("】")
        append(" 类型=").append(kind.name).append(" 体积=").append(sizeLabel)
        if (path.isNotBlank()) append(" 路径=").append(path)
        append('\n')
        when (kind) {
            AiAttachmentKind.TEXT -> {
                if (text.isNotBlank()) append(text.take(maxChars))
                else append("（未能抽取文本，可改用 run_command 分析该路径）")
            }
            AiAttachmentKind.IMAGE -> append("（图片已随本条消息一起提交，请直接看图回答）")
            else -> {
                // ★ 压缩包 / APK 抽出来的条目清单必须真的拼进来。若这里不拼，
                //   「已抽取」就只是个假动作，用户看到的仍是「附件传了等于没传」。
                if (text.isNotBlank()) {
                    append("（以下是该")
                        .append(if (kind == AiAttachmentKind.ARCHIVE) "压缩包/安装包" else "二进制文件")
                        .append("的可读内容摘要）\n")
                    append(text.take(maxChars))
                } else {
                    append(
                        "（二进制/压缩包未能抽取内容，需要时请用 run_command 分析该路径，例如 `unzip -l`、" +
                            "`aapt2 dump badging`、`ffprobe`、`strings | head`）"
                    )
                }
            }
        }
        if (note.isNotBlank()) append("\n备注：").append(note)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("mime", mime).put("kind", kind.name)
        .put("path", path).put("sizeBytes", sizeBytes).put("text", text).put("note", note)

    companion object {
        fun fromJson(o: JSONObject) = AiAttachment(
            id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = o.optString("name").ifBlank { "附件" },
            mime = o.optString("mime"),
            kind = runCatching { AiAttachmentKind.valueOf(o.optString("kind", "BINARY")) }.getOrDefault(AiAttachmentKind.BINARY),
            path = o.optString("path"),
            sizeBytes = o.optLong("sizeBytes"),
            text = o.optString("text"),
            imageBase64 = "",
            note = o.optString("note")
        )
    }
}

/**
 * 附件文本抽取（纯本地，不联网）。
 *
 * 覆盖三类常见场景：
 *  1. 源码/文本/配置 → 直接按 UTF-8 读取（超长截断，保留头部与尾部）；
 *  2. PDF / 未知二进制 → 抽取可读 ASCII 串（对 log、jar 清单、PDF 正文常常已经够用）；
 *  3. 完全不行的 → 返回空串，由上层提示「改用命令分析」。
 */
object AiAttachmentTextExtractor {

    val TEXT_EXT: Set<String> = setOf(
        "txt", "md", "markdown", "rst", "json", "json5", "yaml", "yml", "xml", "html", "htm", "csv", "tsv",
        "log", "ini", "conf", "cfg", "toml", "properties", "gradle", "kts", "pro", "mk", "cmake", "env",
        "kt", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs",
        "rb", "php", "swift", "lua", "sh", "bash", "zsh", "bat", "ps1", "sql", "diff", "patch", "gitignore"
    )

    val ARCHIVE_EXT: Set<String> = setOf("zip", "jar", "apk", "aar", "tar", "gz", "tgz", "xz", "7z", "rar", "bz2", "zst")

    val IMAGE_EXT: Set<String> = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String, mime: String = ""): AiAttachmentKind {
        val ext = extension(name)
        val m = mime.lowercase()
        return when {
            m.startsWith("image/") || ext in IMAGE_EXT -> AiAttachmentKind.IMAGE
            ext in ARCHIVE_EXT -> AiAttachmentKind.ARCHIVE
            ext in TEXT_EXT || m.startsWith("text/") ||
                m.contains("json") || m.contains("xml") || m.contains("yaml") || m.contains("csv") -> AiAttachmentKind.TEXT
            else -> AiAttachmentKind.BINARY
        }
    }

    /** 抽取可读文本；返回空串表示抽取失败（上层应提示改用命令分析）。 */
    fun extract(file: File, name: String, mime: String = "", maxChars: Int = 40_000): String {
        if (!file.isFile) return ""
        val kind = kindOf(name, mime)
        return when (kind) {
            AiAttachmentKind.TEXT -> runCatching { readTextCapped(file, maxChars) }.getOrDefault("")
            AiAttachmentKind.IMAGE -> ""
            // 压缩包 / APK / JAR：优先给「条目清单」。以前这里只扫 ASCII 串，被压缩后的条目名
            // 基本捞不到东西，模型对包内容一无所知 —— 用户体感就是「附件传了等于没传」。
            AiAttachmentKind.ARCHIVE -> runCatching { zipEntries(file) }
                .getOrElse { runCatching { asciiRuns(file, 24, 4_000) }.getOrDefault("") }
            AiAttachmentKind.BINARY -> runCatching {
                if (extension(name) == "pdf") asciiRuns(file, 24, maxChars / 2) else asciiRuns(file, 32, 8_000)
            }.getOrDefault("")
        }
    }

    private fun readTextCapped(file: File, maxChars: Int): String {
        // 旧实现 file.readText() 会把整个文件读进堆：手机上遇到几百 MB 的日志/包直接 OOM
        // （表现就是「附件一加就崩/没反应」）。改成按块读，够 maxChars 就停，
        // 但继续数完总长度，让提示里的「共 N 字符」仍然准确。
        val head = StringBuilder()
        val chunk = CharArray(16 * 1024)
        var total = 0L
        file.bufferedReader().use { reader ->
            while (head.length < maxChars) {
                val n = reader.read(chunk)
                if (n <= 0) break
                total += n
                head.appendRange(chunk, 0, minOf(n, maxChars - head.length))
            }
            while (true) {
                val n = reader.read(chunk)
                if (n <= 0) break
                total += n
            }
        }
        if (head.length < maxChars) return head.toString()
        return head.toString() + "\n…（文件共 $total 字符，其余已省略）…"
    }

    /**
     * 压缩包 / APK / JAR 的条目清单（含体积），最多 [limit] 条。
     *
     * 有了这份清单，模型不用跑命令就能回答「这个包里有啥」「APK 里 dex / 资源 / so 是否齐全」，
     * 附件才算真的被「读到」。列不出来（非 zip 格式）时抛异常，由调用方退化到 ASCII 串。
     */
    private fun zipEntries(file: File, limit: Int = 120): String {
        val sb = StringBuilder()
        java.util.zip.ZipFile(file).use { zip ->
            var shown = 0
            val entries = zip.entries()
            while (entries.hasMoreElements() && shown < limit) {
                val entry = entries.nextElement()
                sb.append(entry.name)
                if (!entry.isDirectory) sb.append(" (").append(entry.size).append("B)")
                sb.append('\n')
                shown++
            }
            val total = zip.size()
            if (total > shown) sb.append("…（共 ").append(total).append(" 个条目，其余已省略）\n")
        }
        return sb.toString().trim()
    }

    /** 从二进制里抽取连续可读 ASCII 串（PDF 正文、jar 内文件名、日志碎片都能捞到）。 */
    private fun asciiRuns(file: File, minRun: Int, maxChars: Int): String {
        val bytes = file.readBytes()
        val sb = StringBuilder()
        val run = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val printable = c == 0x09 || c == 0x0A || (c in 0x20..0x7E)
            if (printable) {
                run.append(c.toChar())
            } else {
                if (run.length >= minRun) {
                    sb.append(run).append('\n')
                    if (sb.length >= maxChars) return sb.toString().take(maxChars)
                }
                run.setLength(0)
            }
        }
        if (run.length >= minRun) sb.append(run).append('\n')
        return sb.toString().trim().take(maxChars)
    }
}
