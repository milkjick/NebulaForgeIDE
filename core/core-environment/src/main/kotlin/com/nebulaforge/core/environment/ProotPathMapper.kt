package com.nebulaforge.core.environment

import android.content.Context
import java.io.File

/**
 * 容器（proot guest）路径 ↔ 宿主机路径 的双向映射器。
 *
 * ## 为什么必须有它
 * 构建/编译错误里的路径来自**编译进程**，而编译进程跑在 proot guest 里；
 * 编辑器要跳转的文件却在**宿主机**路径空间。二者并不总是同一个字符串：
 *
 * | 逻辑位置 | 宿主机（App 进程可见） | guest（proot 内可见） |
 * |---|---|---|
 * | Termux 前缀 | `/data/user/0/<pkg>/files/usr` | `/data/data/com.termux/files/usr` |
 * | HOME | `/data/user/0/<pkg>/files/home` | `/data/data/com.termux/files/home` |
 * | apt 缓存 | `/data/user/0/<pkg>/files/cache` | `/data/data/com.termux/cache` |
 * | 项目目录 | `/storage/emulated/0/...` | **同名**（`-b /storage` 原样绑定） |
 * | App files | `/data/user/0/<pkg>/files/...` | **同名**（`-b <files>` 原样绑定） |
 *
 * 前三行需要重写前缀，后两行不需要 —— 见 [TermuxGuest.bindArgs] 的绑定表。
 * 不区分这两种情况时，典型症状是「Gradle 报错文件路径点不动」或「点了跳到不存在的位置」。
 *
 * ## 另一个用途：编译器输出的是**相对路径**
 * `gcc`/`javac`/`kotlinc` 常用相对路径（`src/main.c:12:5: error: ...`）。
 * [resolve] 会依次尝试：guest 前缀还原 → 相对项目根 → 相对工作目录，返回宿主机绝对文件。
 */
object ProotPathMapper {

    /** (宿主机前缀, guest 前缀)。顺序即匹配优先级：更长的前缀必须在前，避免子串误判。 */
    private fun mappings(context: Context): List<Pair<String, String>> {
        val files = context.filesDir.absolutePath
        return listOf(
            File(files, "usr").absolutePath to TermuxGuest.GUEST_PREFIX,
            File(files, "home").absolutePath to TermuxGuest.GUEST_HOME,
            File(files, "cache").absolutePath to TermuxGuest.GUEST_CACHE
        )
    }

    /** 宿主机路径 → guest 路径（前缀重写；无法映射时原样返回）。 */
    fun toGuest(context: Context, hostPath: String): String {
        mappings(context).forEach { (host, guest) ->
            if (isUnder(hostPath, host)) return guest + hostPath.removePrefix(host)
        }
        return hostPath
    }

    /** guest 路径 → 宿主机路径（前缀重写；无法映射时原样返回）。 */
    fun toHost(context: Context, guestPath: String): String {
        mappings(context).forEach { (host, guest) ->
            if (isUnder(guestPath, guest)) return host + guestPath.removePrefix(guest)
        }
        return guestPath
    }

    /**
     * 统一「同一文件的不同写法」：
     * - `file:///...` URI → 路径
     * - `/sdcard/...` → `/storage/emulated/0/...`（真机 `/sdcard` 只是软链，编译器可能输出其一）
     * - guest 前缀 → 宿主机前缀
     */
    fun canonicalize(context: Context, raw: String): String {
        var path = raw.trim()
        if (path.startsWith("file://")) path = path.removePrefix("file://")
        // 去掉 `path(line, col)` 之类尾部：只保留到扩展名或最后一个路径分隔前的合法段
        path = path.replace("\\", "/")
        path = toHost(context, path)
        if (path == "/sdcard" || path.startsWith("/sdcard/")) {
            path = path.replaceFirst("/sdcard", "/storage/emulated/0")
        }
        return path
    }

    /**
     * 把一条「可能来自编译输出」的路径解析成宿主机上的真实文件。
     *
     * @param projectRoot 项目根（用于相对路径兜底）
     * @param cwd 构建进程工作目录（通常是项目根）
     * @return 存在的宿主文件；找不到返回 null（调用方据此决定「不跳转」而不是跳到错误位置）
     */
    fun resolve(context: Context, raw: String, projectRoot: File?, cwd: File? = null): File? {
        if (raw.isBlank()) return null
        val canonical = canonicalize(context, raw)
        val direct = File(canonical)
        if (direct.isAbsolute && direct.isFile) return direct
        if (direct.isAbsolute && !direct.isFile) {
            // 路径已绝对但文件不存在：不再猜测，避免跳到「看起来像」的错误位置。
            return null
        }
        val relative = raw.trim().replace("\\", "/")
        listOfNotNull(projectRoot, cwd).forEach { base ->
            val candidate = File(base, relative)
            if (candidate.isFile) return candidate
        }
        return null
    }

    /** 判断 [path] 是否位于 [prefix] 之下（按路径段比较，`/a/bc` 不算在 `/a/b` 内）。 */
    fun isUnder(path: String, prefix: String): Boolean {
        val p = prefix.trimEnd('/')
        if (path == p) return true
        if (!path.startsWith(p)) return false
        return path.length > p.length && path[p.length] == '/'
    }
}
