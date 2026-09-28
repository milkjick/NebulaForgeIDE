package com.nebulaforge.stack.web

import android.content.Context
import com.nebulaforge.core.projectmodel.*
import java.io.File

class WebFrontendProjectType(private val context: Context) : ProjectType {
    override val id = "web-frontend"
    override val displayNameKey = "web_frontend"
    override fun detect(projectRoot: File): Boolean {
        val pkg = File(projectRoot, "package.json")
        if (!pkg.isFile) return false
        val text = runCatching { pkg.readText() }.getOrDefault("")
        return Regex("\\\"scripts\\\"\\s*:").containsMatchIn(text) &&
            (File(projectRoot, "src").isDirectory || File(projectRoot, "vite.config.ts").isFile || File(projectRoot, "vite.config.js").isFile || File(projectRoot, "next.config.js").isFile)
    }
    override fun createBuildSystem(): BuildSystem = NpmBuildSystem(context)
    override fun createRunConfiguration() = NpmRunConfiguration()
    override fun requiredLanguageServiceIds(): List<String> = listOf("typescript")
    override fun templateIds(): List<String> = listOf("web-vite", "react-vite", "next-react", "svelte-vite", "angular")
}

class WebBackendProjectType(private val context: Context) : ProjectType {
    override val id = "web-backend"
    override val displayNameKey = "web_backend"
    override fun detect(projectRoot: File): Boolean {
        val pkg = File(projectRoot, "package.json")
        if (!pkg.isFile) return false
        val text = runCatching { pkg.readText() }.getOrDefault("")
        return text.contains("express") || text.contains("fastify") || text.contains("koa") || File(projectRoot, "server.js").isFile || File(projectRoot, "server.ts").isFile
    }
    override fun createBuildSystem(): BuildSystem = NpmBuildSystem(context)
    override fun createRunConfiguration() = NpmRunConfiguration()
    override fun requiredLanguageServiceIds(): List<String> = listOf("typescript")
    override fun templateIds(): List<String> = listOf("node-express", "node-nest", "typescript-express")
}

/**
 * TypeScript 独立工程类型（tsconfig.json 驱动，不依赖任何前端/服务端框架）。
 *
 * 与 [WebFrontendProjectType] / [WebBackendProjectType] 的分工（注册顺序决定识别优先级，
 * 本类型必须在 Web* 之前注册）：
 *  - 只认领「有 tsconfig.json，且没有框架特征」的工程；
 *  - 带 vite / next / angular / svelte / nuxt / astro 配置的工程仍归 [WebFrontendProjectType]；
 *  - package.json 含 express / fastify / koa / nest 的工程仍归 [WebBackendProjectType]。
 *
 * 这层排除是必须的：WebFrontendProjectType 的判定条件是「有 scripts 且有 src/ 目录」，
 * 而任何 TypeScript 工程都长这样，若不排除，新建的 TS 空项目会被前端类型抢走，
 * 于是构建任务按 Vite 链路执行，而项目里根本没有 vite —— 一建出来就编译不过。
 *
 * 识别命中后：构建走 npm（脚本里是 `tsc`），运行走 npm start / npm run dev，
 * 语言服务复用 typescript-language-server（见 LanguageServiceRegistry 的 "typescript"）。
 */
class TypeScriptProjectType(private val context: Context) : ProjectType {
    override val id = "typescript"
    override val displayNameKey = "standalone_typescript_name"

    override fun detect(projectRoot: File): Boolean {
        if (!File(projectRoot, "tsconfig.json").isFile) return false

        // 前端框架工程 → web-frontend。
        val frontendMarkers = listOf(
            "vite.config.ts", "vite.config.js", "vite.config.mts", "vite.config.mjs",
            "next.config.js", "next.config.mjs", "next.config.ts",
            "svelte.config.js", "nuxt.config.ts", "astro.config.mjs", "angular.json"
        )
        if (frontendMarkers.any { File(projectRoot, it).isFile }) return false

        // Nest CLI 工程（node-nest 模板）自带 nest-cli.json：构建命令是 `nest build`，
        // 不是裸 `tsc`，必须留给 web-backend。
        if (File(projectRoot, "nest-cli.json").isFile) return false

        // 服务端框架依赖 / 入口文件 → web-backend。
        val text = File(projectRoot, "package.json").takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()
        if (listOf("express", "fastify", "koa", "nestjs", "@nestjs").any { text.contains(it) }) return false
        if (File(projectRoot, "server.ts").isFile || File(projectRoot, "server.js").isFile) return false

        return true
    }

    override fun createBuildSystem(): BuildSystem = NpmBuildSystem(context)
    override fun createRunConfiguration() = NpmRunConfiguration()
    override fun requiredLanguageServiceIds(): List<String> = listOf("typescript")
    override fun templateIds(): List<String> = listOf("typescript-empty", "typescript-lib", "typescript-node-cli")
}
