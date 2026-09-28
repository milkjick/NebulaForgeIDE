package com.nebulaforge.core.projectmodel

import org.json.JSONObject
import java.io.File

/** Project-scoped toolchain selection stored in .nebulaforge/project.json. */
data class ProjectToolchainConfig(
    val jdkMajor: Int = 17,
    val gradleVersion: String? = null,
    val androidSdk: String? = null,
    val buildToolsVersion: String? = null,
    val ndkVersion: String? = null
) {
    fun write(projectRoot: File) {
        val dir = File(projectRoot, ".nebulaforge").apply { mkdirs() }
        val file = File(dir, "project.json")
        val base = if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() } else JSONObject()
        base.put("toolchain", JSONObject().apply {
            put("jdkMajor", jdkMajor)
            put("gradleVersion", gradleVersion ?: JSONObject.NULL)
            put("androidSdk", androidSdk ?: JSONObject.NULL)
            put("buildToolsVersion", buildToolsVersion ?: JSONObject.NULL)
            put("ndkVersion", ndkVersion ?: JSONObject.NULL)
        })
        file.writeText(base.toString(2) + "\n")
    }

    companion object {
        fun read(projectRoot: File): ProjectToolchainConfig {
            val file = File(projectRoot, ".nebulaforge/project.json")
            if (!file.isFile) return ProjectToolchainConfig()
            val root = runCatching { JSONObject(file.readText()) }.getOrElse { return ProjectToolchainConfig() }
            val o = root.optJSONObject("toolchain") ?: return ProjectToolchainConfig()
            return ProjectToolchainConfig(
                jdkMajor = o.optInt("jdkMajor", 17),
                gradleVersion = o.optString("gradleVersion").takeIf { it.isNotBlank() && it != "null" },
                androidSdk = o.optString("androidSdk").takeIf { it.isNotBlank() && it != "null" },
                buildToolsVersion = o.optString("buildToolsVersion").takeIf { it.isNotBlank() && it != "null" },
                ndkVersion = o.optString("ndkVersion").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }
}
