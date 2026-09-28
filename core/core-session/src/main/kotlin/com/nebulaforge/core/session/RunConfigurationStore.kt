package com.nebulaforge.core.session

import android.content.Context
import com.nebulaforge.core.projectmodel.ProjectTypeRegistry
import com.nebulaforge.core.projectmodel.RunConfigurationDefaults
import com.nebulaforge.core.projectmodel.RunConfigurationSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Project-local persistent Run Configuration registry. */
class RunConfigurationStore(private val context: Context) {
    private val _all = MutableStateFlow(loadAll())
    val all: StateFlow<List<RunConfigurationSpec>> = _all.asStateFlow()

    fun forProject(projectRoot: File): List<RunConfigurationSpec> {
        val path = projectRoot.canonicalPath
        val existing = _all.value.filter { it.projectPath == path }
        if (existing.isNotEmpty()) return existing
        val type = ProjectTypeRegistry.detect(projectRoot) ?: return emptyList()
        val defaults = RunConfigurationDefaults.forProject(projectRoot, type)
        if (defaults.isNotEmpty()) {
            _all.value = (_all.value + defaults).distinctBy { it.id to it.projectPath }
            persist(projectRoot, _all.value.filter { it.projectPath == path })
        }
        return defaults
    }

    fun upsert(spec: RunConfigurationSpec): RunConfigurationSpec {
        val normalized = spec.copy(
            id = spec.id.ifBlank { UUID.randomUUID().toString() },
            updatedAt = System.currentTimeMillis()
        )
        _all.value = (_all.value.filterNot { it.id == normalized.id && it.projectPath == normalized.projectPath } + normalized)
        persist(File(normalized.projectPath), _all.value.filter { it.projectPath == normalized.projectPath })
        return normalized
    }

    fun delete(spec: RunConfigurationSpec) {
        _all.value = _all.value.filterNot { it.id == spec.id && it.projectPath == spec.projectPath }
        persist(File(spec.projectPath), _all.value.filter { it.projectPath == spec.projectPath })
    }

    private fun projectFile(root: File) = File(root, ".nebulaforge/run-configurations.json")

    private fun persist(root: File, records: List<RunConfigurationSpec>) = runCatching {
        val file = projectFile(root)
        file.parentFile?.mkdirs()
        val a = JSONArray()
        records.forEach { r ->
            a.put(JSONObject().apply {
                put("id", r.id); put("name", r.name); put("projectPath", r.projectPath); put("typeId", r.typeId); put("mode", r.mode)
                put("arguments", JSONArray(r.arguments)); put("environment", JSONObject(r.environment));
                put("workingDirectory", r.workingDirectory ?: JSONObject.NULL); put("deviceId", r.deviceId ?: JSONObject.NULL)
                put("port", r.port ?: JSONObject.NULL); put("createdAt", r.createdAt); put("updatedAt", r.updatedAt)
            })
        }
        val tmp = File(file.parentFile, "run-configurations.json.tmp")
        tmp.writeText(a.toString(2))
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
    }

    private fun loadAll(): List<RunConfigurationSpec> {
        val roots = File(com.nebulaforge.core.environment.Environment.projectsDir(context)).listFiles().orEmpty().filter { it.isDirectory }
        return roots.flatMap { root -> loadProject(root) }
    }

    private fun loadProject(root: File): List<RunConfigurationSpec> = runCatching {
        val file = projectFile(root)
        if (!file.isFile) return@runCatching emptyList()
        val a = JSONArray(file.readText())
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val args = o.optJSONArray("arguments")?.let { arr -> buildList { for (j in 0 until arr.length()) add(arr.optString(j)) } } ?: emptyList()
                val env = o.optJSONObject("environment")?.let { obj -> buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } } } ?: emptyMap()
                add(RunConfigurationSpec(
                    o.optString("id"), o.optString("name"), o.optString("projectPath", root.absolutePath), o.optString("typeId"),
                    o.optString("mode", "run"), args, env, o.optString("workingDirectory").takeIf { it.isNotBlank() },
                    o.optString("deviceId").takeIf { it.isNotBlank() }, if (o.isNull("port")) null else o.optInt("port"),
                    o.optLong("createdAt"), o.optLong("updatedAt")
                ))
            }
        }
    }.getOrDefault(emptyList())
}
