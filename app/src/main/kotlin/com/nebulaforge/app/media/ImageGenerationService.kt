package com.nebulaforge.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 图片生成服务（文生图）：**在线接口优先（可配置）→ 失败或无配置则本地兜底**。
 *
 * 与 [VideoGenerationService] 同构，理由也一样：在线服务商由用户指定，端点/密钥没配好时
 * 功能不能空转 —— 本机兜底至少产出一张带描述的图，并在结果里如实说明是兜底产物。
 *
 * 配置（图片面板 / 也可复用视频服务商）：
 * ```
 * nebula_image_providers = [{id,name,base,model,key,path}]   // OpenAI 兼容图片端点
 * nebula_image_active    = <id>
 * ```
 * 未单独配置时**自动复用视频服务商**（国内多数服务商同一 base+密钥同时提供视频与图片接口），
 * 路径固定走 `/images/generations`。错误信息**绝不包含密钥**。
 */
object ImageGenerationService {

    const val PREFS = "nebula_image_prefs"
    const val KEY_PROVIDERS = "nebula_image_providers"
    const val KEY_ACTIVE = "nebula_image_active"
    const val DEFAULT_PATH = "/images/generations"

    data class Provider(
        val id: String = java.util.UUID.randomUUID().toString().replace("-", "").take(8),
        val name: String = "",
        val base: String = "",
        val model: String = "",
        val key: String = "",
        val path: String = DEFAULT_PATH
    ) {
        val label: String get() = name.ifBlank { base.ifBlank { "未命名" } }
        val usable: Boolean get() = base.isNotBlank() && model.isNotBlank()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Provider> {
        val raw = prefs(context).getString(KEY_PROVIDERS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Provider(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    base = o.optString("base"),
                    model = o.optString("model"),
                    key = o.optString("key"),
                    path = o.optString("path").ifBlank { DEFAULT_PATH }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("base", p.base)
                    .put("model", p.model).put("key", p.key).put("path", p.path)
            )
        }
        prefs(context).edit().putString(KEY_PROVIDERS, arr.toString()).apply()
    }

    fun upsert(context: Context, p: Provider) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        save(context, list)
        prefs(context).edit().putString(KEY_ACTIVE, p.id).apply()
    }

    fun active(context: Context): Provider? {
        val id = prefs(context).getString(KEY_ACTIVE, null)
        all(context).firstOrNull { it.id == id && it.usable }?.let { return it }
        all(context).firstOrNull { it.usable }?.let { return it }
        // 没配图片端点 → 复用视频服务商（同一 base/密钥，路径换成图片端点）。
        val v = VideoProviderStore.active(context) ?: return null
        return Provider(name = v.name, base = v.base, model = v.model, key = v.key, path = DEFAULT_PATH)
    }

    fun onlineConfigured(context: Context): Boolean = active(context)?.usable == true

    fun providerLabel(context: Context): String =
        active(context)?.label?.takeIf { it.isNotBlank() } ?: "未配置（本地兜底）"

    /** 默认产物：项目下 `nebula-media/image-<时间戳>.png`。 */
    fun resolveOutput(raw: String, projectRoot: String?): File {
        if (raw.isNotBlank()) {
            val file = File(raw)
            return if (file.isAbsolute) file else File(projectRoot ?: ".", raw)
        }
        val dir = if (projectRoot != null) File(projectRoot, "nebula-media") else File("/data/local/tmp")
        return File(dir, "image-${System.currentTimeMillis()}.png")
    }

    /** 生成一张图。返回给模型/界面看的结果文本（含真实产物路径）。 */
    fun generate(
        context: Context,
        projectRoot: String?,
        arguments: JSONObject,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): String {
        val prompt = arguments.optString("prompt").ifBlank { arguments.optString("text") }.trim()
        if (prompt.isEmpty()) return "缺少 prompt：请描述想画的画面"
        val size = arguments.optString("size").ifBlank { "1024x1024" }
        val out = resolveOutput(arguments.optString("output"), projectRoot)
        out.parentFile?.mkdirs()

        val provider = active(context)
        if (provider != null && provider.usable) {
            onProgress(0.1f, "在线生成图片（${provider.label}）…")
            val err = runCatching { requestOnline(provider, prompt, size, out) }.exceptionOrNull()
            if (err == null && out.isFile && out.length() > 1024) {
                return "已生成图片：${out.absolutePath}（在线 ${provider.label}，${out.length() / 1024} KB）"
            }
            onProgress(0.4f, "在线图片服务不可用（${err?.message?.take(70) ?: "产物异常"}），本地兜底…")
        } else {
            onProgress(0.2f, "未配置在线图片服务，本地兜底…")
        }
        renderLocal(prompt, out)
        return "已生成图片：${out.absolutePath}（本地兜底 = 描述海报；配好在线图片服务即可出真实画面）"
    }

    /** OpenAI 兼容：POST {base}{path}，优先 b64_json，其次 URL 下载。 */
    private fun requestOnline(provider: Provider, prompt: String, size: String, output: File) {
        val body = JSONObject()
            .put("model", provider.model)
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", size)
            .put("response_format", "b64_json")
            .toString()
        val text = postJson(provider, body)
        val root = JSONObject(text.trim())
        val item = root.optJSONArray("data")?.optJSONObject(0)
        val b64 = item?.optString("b64_json").orEmpty().ifBlank { root.optString("b64_json") }
        if (b64.isNotBlank()) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (bytes.size < 512) throw IllegalStateException("返回图片数据异常小（${bytes.size} 字节）")
            output.writeBytes(bytes)
            return
        }
        val url = item?.optString("url").orEmpty().ifBlank { root.optString("url") }
        if (url.isNotBlank()) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
            }
            if (conn.responseCode !in 200..299) throw IllegalStateException("下载图片失败 HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> output.outputStream().use { input.copyTo(it) } }
            return
        }
        throw IllegalStateException("响应里没有图片数据（前 100 字：${text.take(100)}）")
    }

    private fun postJson(provider: Provider, body: String): String {
        val base = provider.base.trimEnd('/')
        val path = provider.path.ifBlank { DEFAULT_PATH }
        val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (provider.key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${provider.key}")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code：${text.take(110)}")
        return text
    }

    /** 本地兜底：把描述渲染成一张 1024×1024 的说明海报（绝不假装是模型画的图）。 */
    private fun renderLocal(prompt: String, output: File) {
        val size = 1024
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                Color.parseColor("#0F172A"), Color.parseColor("#1E3A5F"), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7DD3FC"); textSize = 44f; isFakeBoldText = true
        }
        canvas.drawText("AI 文生图（本地兜底）", 64f, 120f, title)

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 46f
        }
        var y = 220f
        wrap(prompt, 28).take(14).forEach { line ->
            canvas.drawText(line, 64f, y, body)
            y += 62f
        }
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8"); textSize = 30f
        }
        canvas.drawText("配置在线图片服务（/images/generations）后可出真实画面", 64f, size - 70f, note)
        output.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun wrap(text: String, perLine: Int): List<String> {
        val out = mutableListOf<String>()
        val clean = text.replace('\n', ' ')
        var i = 0
        while (i < clean.length) {
            out += clean.substring(i, minOf(i + perLine, clean.length))
            i += perLine
        }
        return out
    }
}
