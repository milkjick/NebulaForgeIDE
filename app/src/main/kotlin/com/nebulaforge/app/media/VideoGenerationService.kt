package com.nebulaforge.app.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 视频生成服务：**在线接口优先（可配置）→ 失败或无配置则本地硬编兜底**。
 *
 * 为什么这样分层：
 * - 用户明确要求「先做本地兜底 + 在线接口预留」：在线服务商还没定，先把调用通道与配置项留出来，
 *   真机上没网 / 没额度 / 服务商限流时也能立刻出片，功能不空转。
 * - 本地路径用 [VideoComposer]（MediaCodec 硬编 H.264 + MediaMuxer），零外部依赖。
 *
 * 配置项（设置面板 / 视频面板可改）：
 * ```
 * nebula_video_base   = https://api.example.com/v1      // OpenAI 兼容或第三方视频端点前缀
 * nebula_video_model  = kling-v1 / wanx2.1-t2v / ...    // 服务商模型名
 * nebula_video_key    = <密钥>                            // 仅存本机 SharedPreferences，不进日志
 * nebula_video_path   = /videos/generations              // 可选：自定义提交路径
 * ```
 */
object VideoGenerationService {

    const val PREFS = "nebula_video_prefs"
    const val KEY_BASE = "nebula_video_base"
    const val KEY_MODEL = "nebula_video_model"
    const val KEY_SECRET = "nebula_video_key"
    const val KEY_PATH = "nebula_video_path"
    const val KEY_PROVIDER = "nebula_video_provider"

    /** 当前选中的在线服务是否可用（判断存在与否，绝不回显密钥本身）。 */
    fun onlineConfigured(context: Context): Boolean = VideoProviderStore.active(context)?.usable == true

    /** 当前在线服务名称，仅用于界面显示。 */
    fun providerLabel(context: Context): String =
        VideoProviderStore.active(context)?.label?.takeIf { it.isNotBlank() } ?: "未配置（走本地硬编）"

    /**
     * 生成一段视频。
     *
     * 参数（与 AI 工具 `generate_video` 完全一致）：
     * - `images`：图片路径，逗号分隔 → 图生视频
     * - `text`：文案，按行切字幕卡 → 文生视频
     * - `output`：输出路径（缺省写到 `<项目>/nebula-media/video-<时间戳>.mp4`）
     * - `seconds_per_image` / `width` / `height` / `fps` / `title`
     *
     * 返回给调用方（模型/界面）的可读结果文本。
     */
    fun compose(
        context: Context,
        projectRoot: String?,
        arguments: JSONObject,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): String {
        val text = arguments.optString("text").trim()
        val imagePaths = splitPaths(arguments)
        require(text.isNotEmpty() || imagePaths.isNotEmpty()) {
            "需要 text（文生视频）或 images（图生视频）至少一个参数"
        }
        val spec = VideoComposer.Spec(
            width = arguments.optInt("width", 1280),
            height = arguments.optInt("height", 720),
            fps = arguments.optInt("fps", 24),
            secondsPerImage = if (arguments.has("seconds_per_image")) arguments.optDouble("seconds_per_image", 3.0) else 3.0,
            title = arguments.optString("title").takeIf { it.isNotBlank() }
        )

        val output = resolveOutput(arguments.optString("output"), projectRoot)
        output.parentFile?.mkdirs()

        // ---- 在线优先（已配置才尝试；任何失败都回落到本地，并把原因写进结果）----
        // 服务商可按名字指定（provider 参数）：不指定就用界面里选中的那个
        val wanted = arguments.optString("provider").trim()
        val provider = VideoProviderStore.resolve(context, wanted)
        val onlineNote = if (provider != null && provider.usable) {
            onProgress(0.02f, "尝试在线视频服务（${provider.label}）…")
            val failure = runCatching { requestOnline(provider, text, imagePaths, output) }
                .exceptionOrNull()?.message
            if (failure == null) {
                return "在线视频服务「${provider.label}」已生成：${output.absolutePath}（${output.length() / 1024} KB）"
            }
            "在线服务「${provider.label}」不可用（$failure），已回落本地硬编。"
        } else if (wanted.isNotEmpty()) {
            "没找到可用的在线服务「$wanted」，已回落本地硬编。已配置服务商：" +
                VideoProviderStore.all(context).joinToString("、") { it.label }.ifBlank { "（无）" }
        } else {
            "未配置在线视频服务，走本地硬编。"
        }

        // ---- 本地兜底 ----
        onProgress(0.05f, "本地合成中…")
        val result = if (imagePaths.isNotEmpty()) {
            val files = imagePaths.map { File(it) }.filter { it.isFile }
            require(files.isNotEmpty()) { "图片路径都不存在：${imagePaths.joinToString("、")}" }
            VideoComposer.imagesToMp4(files, output, spec) { onProgress(0.05f + 0.95f * it, "本地合成中…") }
        } else {
            VideoComposer.textToMp4(text, output, spec) { onProgress(0.05f + 0.95f * it, "本地合成中…") }
        }
        return buildString {
            append(onlineNote).append('\n')
            append("已生成：").append(result.file.absolutePath).append('\n')
            append("参数：").append(spec.w).append('x').append(spec.h)
            append(" · ").append(spec.fps).append("fps")
            append(" · ").append(result.durationMs / 1000).append(" 秒")
            append(" · ").append(result.frames).append(" 帧")
            append(" · 编码器 ").append(result.codec)
            append(" · ").append(result.file.length() / 1024).append(" KB")
        }
    }

    private fun splitPaths(arguments: JSONObject): List<String> {
        arguments.optJSONArray("images")?.let { array: JSONArray ->
            return (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }
        }
        return arguments.optString("images").split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 默认产物目录：项目下 `nebula-media/`；无项目时落到应用私有目录。 */
    fun resolveOutput(raw: String, projectRoot: String?): File {
        if (raw.isNotBlank()) {
            val file = File(raw)
            return if (file.isAbsolute) file else File(projectRoot ?: ".", raw)
        }
        val dir = if (projectRoot != null) File(projectRoot, "nebula-media") else File("/data/local/tmp")
        return File(dir, "video-${System.currentTimeMillis()}.mp4")
    }

    /**
     * 在线接口（预留）：POST `{base}{path}`，OpenAI 兼容风格入参，响应里找视频 URL 再下载。
     *
     * 不同服务商字段名差异大，这里把常见几种都试一遍（`data[0].url` / `data[0].video_url` /
     * `output.url` / `url`）；拿不到 URL 就抛出原因，让上层回落本地。
     * 注意：错误信息里**不含密钥**。
     */
    private fun requestOnline(
        provider: VideoProviderStore.Provider,
        prompt: String,
        imagePaths: List<String>,
        output: File
    ) {
        val base = provider.base.trimEnd('/')
        val model = provider.model
        val secret = provider.key
        val path = provider.path.ifBlank { VideoProviderStore.DEFAULT_PATH }

        val payload = JSONObject()
            .put("model", model)
            .put("prompt", prompt.ifBlank { "根据图片生成一段短视频" })
        if (imagePaths.isNotEmpty()) {
            payload.put("image", imagePaths.first())
        }
        val body = payload.toString()

        val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (secret.isNotBlank()) setRequestProperty("Authorization", "Bearer $secret")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code：${text.take(120)}")

        val url = findVideoUrl(text) ?: throw IllegalStateException("响应里没有视频地址（前 120 字：${text.take(120)}）")
        download(url, output, secret)
    }

    private fun findVideoUrl(text: String): String? {
        val root = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        val candidates = mutableListOf<String?>()
        root.optJSONArray("data")?.optJSONObject(0)?.let {
            candidates += it.optString("url")
            candidates += it.optString("video_url")
        }
        root.optJSONObject("output")?.let { candidates += it.optString("url") }
        candidates += root.optString("url")
        return candidates.firstOrNull { !it.isNullOrBlank() }
    }

    private fun download(url: String, output: File, secret: String) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 240_000
            if (secret.isNotBlank() && url.contains("api")) setRequestProperty("Authorization", "Bearer $secret")
        }
        if (conn.responseCode !in 200..299) throw IllegalStateException("下载视频失败 HTTP ${conn.responseCode}")
        conn.inputStream.use { input -> output.outputStream().use { input.copyTo(it) } }
        if (output.length() < 1024) throw IllegalStateException("下载到的视频文件异常小（${output.length()} 字节）")
    }
}
