package com.nebulaforge.stack.web

import com.nebulaforge.core.projectmodel.RunConfiguration
import java.io.File
import org.json.JSONObject

private fun script(projectRoot: File, name: String): String? = runCatching {
    val o = JSONObject(File(projectRoot, "package.json").readText())
    o.optJSONObject("scripts")?.optString(name)?.takeIf { it.isNotBlank() }
}.getOrNull()

class NpmRunConfiguration : RunConfiguration {
    override val id = "npm-run"
    override fun command(projectRoot: File, mode: String): String {
        val name = when (mode) { "dev" -> "dev"; "start" -> "start"; else -> "start" }
        return if (script(projectRoot, name) != null) "npm run $name" else "npm run dev"
    }
    override fun supports(mode: String) = mode in setOf("run", "dev", "start")
}
