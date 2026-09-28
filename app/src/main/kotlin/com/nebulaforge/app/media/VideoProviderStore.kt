package com.nebulaforge.app.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在线视频「多服务商」配置：可以添加多个服务商（各自 Base URL / 模型 / 密钥 / 提交路径），
 * 在其中任选一个作为「当前使用」。AI 工具也能按名字指定服务商（`generate_video` 的 `provider` 参数）。
 *
 * 兼容旧版单服务商配置（`nebula_video_base` / `_model` / `_key` / `_path`）：列表为空时把它读成一个服务商项，
 * 保证老用户升级后配置不丢。
 *
 * 安全：密钥只存本机 SharedPreferences，界面不回显明文到日志，错误信息里也不带密钥。
 */
object VideoProviderStore {

    const val KEY_LIST = "nebula_video_providers"
    const val KEY_ACTIVE = "nebula_video_active"

    data class Provider(
        val id: String,
        val name: String,
        val base: String,
        val model: String,
        val key: String,
        val path: String = DEFAULT_PATH,
        val enabled: Boolean = true
    ) {
        /** 界面显示名：优先用户起的名字，否则退化成 Base URL。 */
        val label: String get() = name.ifBlank { base.ifBlank { "未命名" } }

        /** 是否具备发起在线请求的最小条件。 */
        val usable: Boolean get() = enabled && base.isNotBlank() && model.isNotBlank()
    }

    const val DEFAULT_PATH = "/videos/generations"

    private fun prefs(context: Context) =
        context.getSharedPreferences(VideoGenerationService.PREFS, Context.MODE_PRIVATE)

    /** 全部服务商（含从旧配置迁移出来的一项）。 */
    fun all(context: Context): List<Provider> {
        val raw = prefs(context).getString(KEY_LIST, null)
        val list = mutableListOf<Provider>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = Provider(
                        id = o.optString("id").ifBlank { o.optString("name") },
                        name = o.optString("name"),
                        base = o.optString("base"),
                        model = o.optString("model"),
                        key = o.optString("key"),
                        path = o.optString("path").ifBlank { DEFAULT_PATH },
                        enabled = o.optBoolean("enabled", true)
                    )
                    if (p.name.isNotBlank() || p.base.isNotBlank()) list.add(p)
                }
            }
        }
        if (list.isEmpty()) legacy(context)?.let { list.add(it) }
        return list
    }

    private fun legacy(context: Context): Provider? {
        val p = prefs(context)
        val base = p.getString(VideoGenerationService.KEY_BASE, "").orEmpty()
        val model = p.getString(VideoGenerationService.KEY_MODEL, "").orEmpty()
        if (base.isBlank() && model.isBlank()) return null
        return Provider(
            id = "legacy",
            name = p.getString(VideoGenerationService.KEY_PROVIDER, "").orEmpty().ifBlank { "旧配置" },
            base = base,
            model = model,
            key = p.getString(VideoGenerationService.KEY_SECRET, "").orEmpty(),
            path = p.getString(VideoGenerationService.KEY_PATH, DEFAULT_PATH).orEmpty().ifBlank { DEFAULT_PATH }
        )
    }

    /** 当前选中的服务商 id（无效则退回第一个）。 */
    fun activeId(context: Context): String {
        val saved = prefs(context).getString(KEY_ACTIVE, "").orEmpty()
        val list = all(context)
        return if (list.any { it.id == saved }) saved else list.firstOrNull()?.id.orEmpty()
    }

    fun active(context: Context): Provider? {
        val id = activeId(context)
        return all(context).firstOrNull { it.id == id }
    }

    /** 按 id 或名称解析服务商；为空表示「当前选中的」。 */
    fun resolve(context: Context, idOrName: String?): Provider? {
        val want = idOrName?.trim().orEmpty()
        if (want.isEmpty()) return active(context)
        return all(context).firstOrNull { it.id.equals(want, true) || it.name.equals(want, true) }
    }

    fun save(context: Context, list: List<Provider>, active: String) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("base", p.base).put("model", p.model)
                    .put("key", p.key).put("path", p.path).put("enabled", p.enabled)
            )
        }
        prefs(context).edit()
            .putString(KEY_LIST, arr.toString())
            .putString(KEY_ACTIVE, active)
            // 同时把「当前项」写回旧键，任何还在读旧键的代码路径都拿到一致的值
            .apply {
                val cur = list.firstOrNull { it.id == active } ?: list.firstOrNull()
                putString(VideoGenerationService.KEY_BASE, cur?.base.orEmpty())
                putString(VideoGenerationService.KEY_MODEL, cur?.model.orEmpty())
                putString(VideoGenerationService.KEY_SECRET, cur?.key.orEmpty())
                putString(VideoGenerationService.KEY_PATH, cur?.path ?: DEFAULT_PATH)
                putString(VideoGenerationService.KEY_PROVIDER, cur?.name.orEmpty())
            }
            .apply()
    }

    /** 新增或按 id/同名更新；[makeActive] 非空时同时切换当前服务商。 */
    fun upsert(context: Context, provider: Provider, makeActive: String? = null) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst {
            it.id == provider.id || (provider.name.isNotBlank() && it.name.equals(provider.name, true))
        }
        val id = if (idx >= 0) list[idx].id else provider.id.ifBlank { "p" + System.currentTimeMillis() }
        val item = provider.copy(id = id)
        if (idx >= 0) list[idx] = item else list.add(item)
        save(context, list, makeActive ?: activeId(context).ifBlank { id })
    }

    fun remove(context: Context, id: String) {
        val list = all(context).filterNot { it.id == id }
        save(context, list, list.firstOrNull()?.id.orEmpty())
    }

    fun setActive(context: Context, id: String) = save(context, all(context), id)
}
