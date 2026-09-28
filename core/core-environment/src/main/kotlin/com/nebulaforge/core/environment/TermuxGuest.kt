package com.nebulaforge.core.environment

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Termux 前缀对齐运行时（guest runtime）。
 *
 * ## 为什么需要它
 * 内置 userland 是 Termux bootstrap，其 apt/dpkg/bash/profile 等**在编译期就写死了**
 * `/data/data/com.termux/files/usr` 前缀（apt 的目录宏、二进制 RUNPATH、脚本 shebang）。
 * 本 App 的私有目录是 `/data/user/0/<pkg>/files/usr`，两者不可能相等，
 * 直接搬过去运行的结果就是：
 *   - `bash.bashrc/profile` 读取被拒（Permission denied）；
 *   - apt 读不到自己的配置/数据库 → 测试镜像时全部 "bad"；
 *   - dpkg/apt 拒绝以 root 运行（Termux 的补丁），所以也不能用 `proot -0` 绕过。
 *
 * ## 解决方式
 * 用 [proot] 建立「用户态 chroot」：把 App 私有前缀**绑定挂载**到 Termux 期望的
 * `/data/data/com.termux/files/usr`，并以**真实 App uid**（不加 `-0`）运行。
 * 这样 apt/dpkg/python/node 都认为自己跑在正常 Termux 里，`apt-get install` 可正常安装。
 *
 * 已在本机实测通过：`apt-get update` → `apt-get install -y python` → `python3 -V` = 3.14.6。
 *
 * ## 必要绑定
 * - `usr`      → `/data/data/com.termux/files/usr`（前缀对齐，核心）
 * - `filesDir` → 自身宿主路径（让被重写成宿主路径的 shebang 在 guest 内也能解析）
 * - `cache`    → `/data/data/com.termux/cache`（Termux 的 apt 下载缓存目录在 prefix 之外）
 * - `/system`、`/apex`（bionic linker 与 libc 所在）、`/dev`、`/proc`、`/sys`、`/storage`（项目目录）
 */
object TermuxGuest {

    /** guest 内 Termux 前缀（与 Termux 自身完全一致，脚本/二进制都按它找路径）。 */
    const val GUEST_PREFIX = "/data/data/com.termux/files/usr"
    const val GUEST_HOME = "/data/data/com.termux/files/home"
    const val GUEST_CACHE = "/data/data/com.termux/cache"

    private const val PROOT_ASSET_DIR = "bootstrap/proot"

    /**
     * Termux 软件源（探测择优，含官方源兜底）。
     *
     * ## 2026-09 修正：华为云已不再提供 termux 仓库
     * `mirrors.huaweicloud.com/termux/apt/termux-main/dists/stable/Release` 现在返回
     * **HTTP 200 + text/html**（华为云官网页面）。旧版 [probe] 只看状态码 200，于是把它判成
     * 「最快可达」写进 sources.list，apt 拿到 HTML 当索引 →
     *   E: Clearsigned file isn't valid, got 'NOSPLIT'
     *   E: The repository ... is not signed.
     *   E: Unable to locate package git
     * 这正是「清空数据后所有组件都装不上」的直接原因（清空数据 → 必须重装全部组件 → 撞上坏源）。
     * 现在列表已移除该地址，且 [probe] 会校验响应体真的是 PGP 签名索引。
     */
    private val MIRRORS = listOf(
        // 顺序即探测优先级；[probe] 会跳过「返回网页 / 无签名索引」的失效镜像。
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
        "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
        "https://mirror.nju.edu.cn/termux/apt/termux-main",
        "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
        "https://mirrors.cloud.tencent.com/termux/apt/termux-main",
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main"
    )

    /** guest 内拿不到活动网络 DNS 时的兜底（国内可达优先，全部为 IPv4）。 */
    private val FALLBACK_DNS = listOf("223.5.5.5", "119.29.29.29", "8.8.8.8", "1.1.1.1")

    fun usrRoot(context: Context): File = File(Environment.usrRoot(context))
    fun prootBinary(context: Context): File = File(usrRoot(context), "bin/proot")
    fun rootfs(context: Context): File = File(context.filesDir, "termuxfs")
    fun aptCache(context: Context): File = File(context.filesDir, "cache")

    private fun sourcesList(context: Context): File = File(usrRoot(context), "etc/apt/sources.list")

    /** proot 是否已就绪（内核文件 + 前缀均已安装）。 */
    fun isReady(context: Context): Boolean =
        Environment.isBootstrapInstalled(context) && prootBinary(context).canExecute()

    /**
     * 幂等地准备 guest 运行时：目录骨架 + proot 二进制 + 悬空软链修复。
     * 安装 bootstrap 之后必须调用（重复调用无副作用）。
     *
     * @return 本次修复的悬空软链数量
     */
    fun ensureSetup(context: Context, log: (String) -> Unit = {}): Int {
        val files = context.filesDir
        val usr = usrRoot(context)

        // 1) guest rootfs 骨架：必须真实存在，proot 的绑定才能挂到已有路径上。
        val guestDirs = listOf(
            "data/data/com.termux/files/usr",
            "data/data/com.termux/files/home",
            "data/data/com.termux/cache",
            "dev", "proc", "sys", "storage", "tmp",
            // 许多第三方脚本（如 Android 官方 sdkmanager）用 `#!/bin/sh` 或 `#!/usr/bin/env bash`。
            // 最小 rootfs 里不存在这两个路径，若不补链，脚本会在 ENOENT 处直接失败。
            "bin", "usr",
            // Android bionic linker 启动时会**按绝对路径**读取 /linkerconfig/ld.config.txt；
            // rootfs 里没有它就会每 exec 一次打一行
            //   WARNING: linker: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"
            // 详见 [ensureLinkerConfig]。
            "linkerconfig"
        )
        guestDirs.forEach { rel ->
            val d = File(rootfs(context), rel)
            if (!d.isDirectory) d.mkdirs()
            makeAccessible(d)
        }
        // 把 /bin/sh、/bin/bash、/usr/bin 指向 guest 前缀（Termux）里的真实文件。
        // 目标为 guest 内绝对路径，proot 解析后落到已绑定挂载的 $PREFIX 上。
        ensureGuestLink(File(rootfs(context), "bin/sh"), "$GUEST_PREFIX/bin/sh")
        ensureGuestLink(File(rootfs(context), "bin/bash"), "$GUEST_PREFIX/bin/bash")
        ensureGuestLink(File(rootfs(context), "usr/bin"), "$GUEST_PREFIX/bin")
        // 2) apt 缓存目录（Termux 的 Dir::Cache 指向 prefix 之外）。
        File(aptCache(context), "apt/archives/partial").mkdirs()
        // 3) proot 运行所需的可写临时目录（PROOT_TMP_DIR）。
        val prootTmp = File(files, "tmp")
        if (!prootTmp.isDirectory) prootTmp.mkdirs()
        makeAccessible(prootTmp)
        File(usr, "tmp").mkdirs()

        // 4) 安装/更新 proot 组件（幂等：大小不一致才覆盖）。
        installProotAssets(context, usr) { log(it) }

        // 5) 修复历史遗留悬空软链（早期版本把绝对目标解析到 staging 目录，提交后全部失效，
        //    其中 etc/apt/trusted.gpg.d/*.gpg 悬空会直接导致 apt 无法验签 → 所有镜像 "bad"）。
        val repaired = repairDanglingLinks(usr)
        if (repaired > 0) log("已修复 $repaired 个悬空软链（含 apt 密钥环）")

        // 6) guest 内 DNS 配置（详见 ensureGuestDns 的说明）：
        //    缺这一步时 guest 里「能连 IP、不能解析域名」，apt/curl 一律失败。
        ensureGuestDns(context)

        // 7) 补一份最小 linker 配置（详见 ensureLinkerConfig 的说明）：
        //    缺这一步虽不影响运行，但每条命令都会往 stderr 打一行 linker 警告，
        //    既污染终端，也会被探测逻辑当成"版本号/错误"显示到设置页。
        ensureLinkerConfig(context)

        // 8) 各语言包管理器的国内镜像（npm / pip / Maven-Gradle / Cargo，详见 ensureMirrorConfigs）：
        //    真机上「工具链装不上」最常见的成因不是源挂了，而是包管理器默认域名在境内不可达。
        ensureMirrorConfigs(context)
        return repaired
    }

    /**
     * 校验并**就地升级** GRADLE_USER_HOME 下的全局 `init.gradle`（幂等）。
     *
     * ## 为什么单独暴露、还要在构建前调用
     * `init.gradle` 是应用**自动生成**的兼容层，它的正确性随版本变化。真机事故：
     * v1 的 `allprojects { repositories { … } }` 与 Flutter / 新版 Android 模板的
     * `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` 冲突，构建在**配置阶段**
     * 直接失败（`BUILD FAILED in 30s`）：
     * ```
     * Initialization script '…/.gradle/init.gradle' line: 4
     * Build was configured to prefer settings repositories over project repositories
     * but repository 'maven' was added by initialization script '…/init.gradle'
     * ```
     * 而这个文件过去是「已存在就不再写」（[writeIfMissing]），于是**老设备永远升不上去**，
     * 表现成「工具链装好了、换个版本还是编译不过」。
     *
     * 因此这里提供构建前必跑的入口：内容不一致就原子替换（[writeIfChanged]），
     * 让修复真正落到用户已建好的工程上。
     *
     * @return 需要展示给用户的一行说明；无需变更时返回 null（不产生噪音）。
     */
    fun ensureGradleInit(context: Context): String? {
        val file = File(File(context.filesDir, "home"), ".gradle/init.gradle")
        val before = runCatching { file.readText() }.getOrNull()
        if (before == GRADLE_INIT_TEXT) return null
        writeIfChanged(file, GRADLE_INIT_TEXT)
        return if (before.isNullOrBlank()) {
            null // 首次写入属于正常初始化，不算「自愈」，不刷构建前言
        } else {
            "已修复 Gradle 全局 init 脚本：旧版会向工程级仓库注入镜像，导致 Flutter / 新模板工程配置阶段失败"
        }
    }

    /**
     * 在 guest rootfs 内落一份**最小** `/linkerconfig/ld.config.txt`。
     *
     * ## 为什么必须做
     * Android 的 bionic linker（`/system/bin/linker64`）在每次 exec 一个动态链接 ELF 时，
     * 都会按**绝对路径**读取 `/linkerconfig/ld.config.txt`（内核命令行传给它的参数）。
     * 该路径在宿主机上由 init 在启动阶段生成，而 proot 的 `-r <rootfs>` 会把 guest 进程
     * 看到的绝对路径重定向到 rootfs 下——rootfs 里没有这个文件，linker 就退回默认配置并打印：
     *
     *   WARNING: linker: Warning: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"
     *
     * Termux 里几乎全是动态链接的 ELF（proot/bash/python/node…），因此这行警告会在**每条命令**
     * 前刷一遍。后果不是"跑不起来"，而是：
     *   - 终端/构建输出被噪声淹没；
     *   - [ToolchainManager] 探测时取输出首行当版本号，于是设置页把这条警告当成 Python 的"版本"。
     *
     * ## 为什么不直接绑定宿主的 /linkerconfig
     * 实测 `proot -b /linkerconfig:/linkerconfig` 会失败：
     *   `proot warning: can't sanitize binding "/linkerconfig": Permission denied`
     * （该目录本体不可 stat/遍历，SELinux 亦限制），且警告依旧存在。
     *
     * ## 为什么写"最小"配置而不是照抄宿主的 144KB 配置
     * 宿主的 ld.config.txt 定义了 namespace 隔离与 `permitted.paths`，其中并不包含
     * `/data/data/com.termux/files/usr/lib`；原样搬进 guest 会让 linker 拒绝从 Termux 前缀
     * 加载/搜索共享库（`libtalloc`、`libpython` 等），把"一条警告"升级成"真的跑不起来"。
     * 只声明 `dir.*` 搜索目录、不声明任何 namespace，等价于过去的默认行为，既消除警告又不改变
     * 库解析语义。已实测：补入后 `python3 -V` = 3.14.6、`node -v` = v24.18.0，输出干净无警告。
     */
    fun ensureLinkerConfig(context: Context) {
        val dir = File(rootfs(context), "linkerconfig")
        if (!dir.isDirectory) dir.mkdirs()
        makeAccessible(dir)
        val file = File(dir, "ld.config.txt")
        // 幂等：内容一致就不重写（避免每次启动都 touch 文件、破坏 proot 的 stat 缓存）。
        if (file.isFile && runCatching { file.readText() }.getOrNull() == LINKER_CONFIG_MINIMAL) return
        runCatching { file.writeText(LINKER_CONFIG_MINIMAL) }
    }

    /**
     * 最小可用的 linker 配置。
     *
     * 刻意**不包含** `[system]`/`[vendor]` 等 namespace 段：一旦声明 namespace，未列入
     * `permitted.paths` 的目录（含 Termux 前缀）就会被 linker 拒之门外，反而破坏库解析。
     */
    private const val LINKER_CONFIG_MINIMAL = """# NebulaForge: minimal linker configuration for the embedded proot rootfs.
# Only declares library search dirs; intentionally declares NO namespaces so that
# Termux prefix libraries stay resolvable. See TermuxGuest.ensureLinkerConfig.
dir.system = /system/bin/
dir.system = /system/xbin/
dir.system = /system/system_ext/bin/
dir.system = /system/product/bin/
dir.vendor = /odm/bin/
dir.vendor = /vendor/bin/
"""

    /**
     * 在 guest rootfs 内写入 `/etc/resolv.conf` 与 `/etc/hosts`。
     *
     * ## 为什么必须做
     * Android 设备**没有** `/etc/resolv.conf`：应用侧域名解析是由 netd 的 dnsproxyd 套接字代理完成的。
     * 而 proot 用户态 chroot 里的进程无法走这条通路，于是 guest 内表现为：
     *   - `apt-get update` → `Something wicked happened resolving '...' (7 - No address associated with hostname)`
     *   - `curl` → `(6) Could not resolve host` / 直接连接超时（20s）
     * 只要在 guest 内落一份可用的 resolv.conf，bionic 就会直接向这些 DNS 发起查询。
     *
     * ## 实测结论（本机）
     * 删除 `/etc/resolv.conf` 后 guest 内 `curl https://www.baidu.com` → 20s 超时；
     * 补回后同一命令 → HTTP 200，`apt-get update` 正常。
     *
     * DNS 取值优先用**当前活动网络**的服务器（避免换网络后硬编码 DNS 不可达），取不到时回退公共 DNS。
     */
    fun ensureGuestDns(context: Context) {
        val etc = File(rootfs(context), "etc")
        if (!etc.isDirectory) etc.mkdirs()
        makeAccessible(etc)

        // 活动网络 DNS 优先，但**必须**再补公共 DNS 兜底：真机实测遇到过运营商 DNS 只在
        // 应用进程内可用（netd dnsproxyd 代理），guest 内直连查询直接报
        // `Something wicked happened resolving '...' (7 - No address associated with hostname)`，
        // 表现为 apt/curl 全挂、看起来像「下载源挂了」。bionic 会按顺序逐个尝试 nameserver，
        // 多写几个即可自愈。
        val servers = (networkDnsServers(context) + FALLBACK_DNS).distinct().take(4)
        val desired = servers.joinToString("") { "nameserver $it\n" }

        val resolv = File(etc, "resolv.conf")
        // 内容不一致就重写：换网络后 DNS 会变，只有「缺文件才写」会一直用旧 DNS。
        if (!resolv.isFile || runCatching { resolv.readText() }.getOrNull() != desired) {
            runCatching { resolv.writeText(desired) }
        }
        resolv.setReadable(true, false)

        val hosts = File(etc, "hosts")
        if (!hosts.isFile) hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        hosts.setReadable(true, false)
    }

    /**
     * 读取当前活动网络的 DNS 服务器（IPv4）。
     *
     * 需要 ACCESS_NETWORK_STATE；缺失或未联网时返回空，由调用方回退 [FALLBACK_DNS]。
     * 只取 IPv4：最小 rootfs 里没有配置 IPv6 路由，先保证可用性。
     */
    private fun networkDnsServers(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return@runCatching emptyList()
        val network = cm.activeNetwork ?: return@runCatching emptyList()
        cm.getLinkProperties(network)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }
            .filter { it.isNotBlank() && ':' !in it }
            .distinct()
    }.getOrDefault(emptyList())

    private fun installProotAssets(context: Context, usr: File, log: (String) -> Unit) {
        val targets = mapOf(
            "proot" to File(usr, "bin/proot"),
            "termux-chroot" to File(usr, "bin/termux-chroot"),
            "libtalloc.so.2" to File(usr, "lib/libtalloc.so.2"),
            "libandroid-shmem.so" to File(usr, "lib/libandroid-shmem.so"),
            "loader" to File(usr, "libexec/proot/loader"),
            "loader32" to File(usr, "libexec/proot/loader32")
        )
        targets.forEach { (asset, dest) ->
            val bytes = runCatching {
                context.assets.open("$PROOT_ASSET_DIR/$asset").use { it.readBytes() }
            }.getOrNull() ?: return@forEach
            val upToDate = dest.isFile && dest.length() == bytes.size.toLong()
            if (!upToDate) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                dest.setReadable(true, false)
                dest.setExecutable(true, false)
                log("已安装 ${dest.name}")
            }
        }
    }

    /**
     * 修复指向已删除 staging 目录的悬空软链，改指向最终前缀下的同名文件。
     *
     * 早期 [BootstrapInstaller] 在 staging 目录中创建软链时，把绝对目标按 staging 解析，
     * 提交（rename）后目标消失 → 悬空。这里按「basename 在最终前缀内查找」的方式重建。
     */
    fun repairDanglingLinks(usr: File): Int {
        var repaired = 0
        val stack = ArrayDeque<File>()
        stack.addLast(usr)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                val target = readlinkCompat(child)
                if (target == null) {
                    if (child.isDirectory) stack.addLast(child)
                    continue
                }
                if (!target.contains(".bootstrap-staging")) continue
                val name = target.substringAfterLast('/')
                val replacement = findByName(usr, name)
                if (replacement != null && replacement.absolutePath != child.absolutePath && !isLink(replacement)) {
                    if (createSymlinkCompat(child, replacement.absolutePath)) repaired++
                } else if (replacement == null) {
                    // 目标文件确实缺失：删掉悬空链，避免后续 stat/open 出现 ENOENT 噪声。
                    child.delete()
                    repaired++
                }
            }
        }
        return repaired
    }

    private fun findByName(root: File, name: String, depth: Int = 0): File? {
        if (depth > 6) return null
        val children = root.listFiles() ?: return null
        children.firstOrNull { it.name == name && !isLink(it) }?.let { return it }
        for (c in children) {
            if (c.isDirectory) findByName(c, name, depth + 1)?.let { return it }
        }
        return null
    }

    private fun isLink(file: File): Boolean = readlinkCompat(file) != null

    private fun readlinkCompat(file: File): String? {
        if (!file.exists() && !file.parentFile.exists()) return null
        return try {
            val osClass = Class.forName("android.system.Os")
            val readlink = osClass.getMethod("readlink", String::class.java)
            readlink.invoke(null, file.absolutePath) as? String
        } catch (t: Throwable) {
            // 反射失败（极少见）时退化为「绝对路径与规范路径不一致即视为链接」的粗判。
            runCatching {
                val canonical = file.canonicalPath
                if (canonical != file.absolutePath) canonical else null
            }.getOrNull()
        }
    }

    private fun createSymlinkCompat(link: File, target: String): Boolean = try {
        link.delete()
        val osClass = Class.forName("android.system.Os")
        osClass.getMethod("symlink", String::class.java, String::class.java)
            .invoke(null, target, link.absolutePath)
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * 幂等地在 guest rootfs 内创建一个**绝对目标**软链（目标按 guest 内路径解析）。
     * 已存在真实文件/目录时不覆盖，避免误伤用户数据。
     */
    private fun ensureGuestLink(link: File, target: String) {
        val current = readlinkCompat(link)
        if (current == target) return
        if (current != null) link.delete()
        else if (link.exists()) return
        link.parentFile?.mkdirs()
        createSymlinkCompat(link, target)
    }

    private fun makeAccessible(dir: File) {
        dir.setReadable(true, false)
        dir.setExecutable(true, false)
        dir.setWritable(true, false)
    }

    // ---------------------------------------------------------------- 命令构造

    /** proot 的路径绑定参数（顺序无关，全部为「宿主:guest」或「同名绑定」）。 */
    fun bindArgs(context: Context): List<String> {
        val usr = usrRoot(context).absolutePath
        val files = context.filesDir.absolutePath
        val cache = aptCache(context).absolutePath
        return listOf(
            "-b", "$usr:$GUEST_PREFIX",
            // HOME 与 `-w` 的工作目录必须显式绑定：上面那条绑定只覆盖 usr 子树，
            // guest 内的 /data/data/com.termux/files/home 会落到宿主真实路径（不存在）→
            // proot chdir 失败，命令实际在宿主 cwd 下运行（相对路径、$HOME、profile 全部错位）。
            "-b", "${File(files, "home").absolutePath}:$GUEST_HOME",
            // 宿主路径原样可见：被重写成宿主路径的 shebang（如 apt-key）在 guest 内同样可解析。
            "-b", files,
            "-b", "$cache:$GUEST_CACHE",
            "-b", "/system", "-b", "/apex",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/storage"
        )
    }

    /**
     * proot **进程自身**启动时必需的环境变量（宿主视角的值，不是 guest 内的值）。
     *
     * ## 为什么必须单独抽出来
     * proot 在启动阶段（还没进入 rootfs 之前）就要读这些变量。若它们只写在内层 guest 脚本里
     * export，等于「进程已经挂了才设置」——真机实测症状（`TerminalSessionManager` 之外的路径）：
     *
     *   CANNOT LINK EXECUTABLE proot: library "libtalloc.so.2" not found
     *   proot warning: can't canonicalize /data/data/com.termux/files/usr/tmp/: Permission denied
     *   proot warning: Unable to create temp directory for f2fs bug probe: Permission denied
     *   proot error: execve("/data/data/com.termux/files/usr/bin/bash"): Permission denied
     *
     * 逐条对应：缺 LD_LIBRARY_PATH → 找不到 libtalloc；缺 PROOT_TMP_DIR → 退回 TMPDIR（guest 路径）
     * → 无法 canonicalize、绑定全部失效 → 后续 execve 落在宿主真实路径上 → Permission denied。
     *
     * 因此凡是以「宿主 shell 命令行」形式调用 proot 的地方（见 [guestCommandLine]）都必须先 export 它。
     */
    fun hostProotEnv(context: Context): Map<String, String> {
        val usr = usrRoot(context).absolutePath
        val tmp = File(context.filesDir, "tmp").apply { if (!isDirectory) mkdirs() }
        return linkedMapOf(
            // 宿主侧只写宿主真实路径：LD_LIBRARY_PATH 由 Android linker 在宿主机上解析，
            // 写入 guest 前缀只会多一个无意义的查找目录（虽然无害，但会污染诊断输出）。
            "LD_LIBRARY_PATH" to "$usr/lib",
            "PROOT_TMP_DIR" to tmp.absolutePath,
            "PROOT_LOADER" to File(usr, "libexec/proot/loader").absolutePath,
            // Android 应用进程本身带 seccomp 过滤器，proot 的 seccomp 加速（PTRACE_ACCEL）在这种
            // 环境下会直接失败 —— 现象就是「终端一打开 proot 报错退出、敲键盘没反应」。
            // 显式禁用加速，走纯 ptrace 路径（官方推荐做法）。
            "PROOT_NO_SECCOMP" to "1"
        )
    }

    /** proot 进程自身需要的环境变量 + guest 侧约定变量（二者可共存，不会互相破坏）。 */
    fun guestEnv(context: Context): Map<String, String> {
        val usr = usrRoot(context).absolutePath
        return hostProotEnv(context) + mapOf(
            // 宿主优先、guest 兜底：两条路径指向同一批文件，proot 内外都能正确加载。
            "LD_LIBRARY_PATH" to "$usr/lib:$GUEST_PREFIX/lib",
            "PREFIX" to GUEST_PREFIX,
            "TERMUX_PREFIX" to GUEST_PREFIX,
            "TERMUX_MAIN_PACKAGE_FORMAT" to "debian",
            "HOME" to GUEST_HOME,
            "TMPDIR" to "$GUEST_PREFIX/tmp",
            "PATH" to "$GUEST_PREFIX/bin:$GUEST_PREFIX/bin/applets",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            // Go 模块代理：境内直连 proxy.golang.org 基本必超时（goproxy.cn 为官方推荐镜像）。
            "GOPROXY" to "https://goproxy.cn,direct",
            "GOSUMDB" to "sum.golang.google.cn"
        )
    }

    /**
     * guest 内这些变量由 proot 运行时自身决定，调用方不得覆盖（覆盖会让 loader/库解析错位）。
     */
    private val PROTECTED_ENV_KEYS = setOf(
        "PATH", "LD_LIBRARY_PATH", "HOME", "PREFIX", "TERMUX_PREFIX", "TMPDIR",
        "PROOT_TMP_DIR", "PROOT_LOADER"
    )

    /**
     * 把「工具链解析出来的环境」并入 guest 环境，返回可直接交给 pty/进程的环境表。
     *
     * ## 为什么需要它
     * [guestEnv] 只描述 **proot 运行时自身**需要的变量（前缀、PATH、HOME…）。
     * 而构建/运行还需要工具链解析出的 `JAVA_HOME` / `ANDROID_HOME` / `GRADLE_USER_HOME` /
     * `PATH`（含 build-tools、platform-tools、cmdline-tools）。此前 `TerminalSessionManager`
     * 在 guest 分支里**只传了 guestEnv**，把 `Environment.buildTerminalEnv()` 的结果整个丢掉，
     * 于是 guest 终端里 `JAVA_HOME`/`ANDROID_HOME` 全部缺失 —— 表现为「gradle 装了但构建仍失败」。
     *
     * ## 路径为什么能直接用宿主绝对路径
     * `filesDir` 被原样绑定进 guest（见 [bindArgs]），`usr` 绑定到 [GUEST_PREFIX]，
     * 而 `binDir()` = `files/usr/bin` 恰与 `$GUEST_PREFIX/bin` 是同一目录，
     * `android-sdk`/`.gradle` 都在 `files/home` 下。因此工具链给出的宿主路径在 guest 内同样有效。
     *
     * ## 合并规则
     * - `PATH`：guest 前缀优先（保证 apt 装的 `python`/`node`/`gradle` 命中），再拼调用方 PATH；
     * - `LD_LIBRARY_PATH`：宿主 usr/lib 优先，再 guest 前缀 lib 与调用方值；
     * - 其余键：调用方值覆盖 guest 默认值，但 [PROTECTED_ENV_KEYS] 里的 proot 关键变量不许被覆盖。
     */
    fun guestEnvWith(context: Context, extraEnv: Map<String, String> = emptyMap()): Map<String, String> {
        val usr = usrRoot(context).absolutePath
        val guest = guestEnv(context).toMutableMap()
        val callerPath = extraEnv["PATH"].orEmpty()
        guest["PATH"] = listOf("$GUEST_PREFIX/bin", "$GUEST_PREFIX/bin/applets", callerPath)
            .filter { it.isNotBlank() }.joinToString(":")
        guest["LD_LIBRARY_PATH"] = listOf("$usr/lib", "$GUEST_PREFIX/lib", extraEnv["LD_LIBRARY_PATH"].orEmpty())
            .filter { it.isNotBlank() }.joinToString(":")
        extraEnv.forEach { (k, v) -> if (k !in PROTECTED_ENV_KEYS) guest[k] = v }
        return guest
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 把一段命令包装成「在 guest（proot 前缀对齐运行时）里执行」的**宿主 shell 单行命令**。
     *
     * ## 为什么必须这么做
     * 内置 userland 里大量工具不是 ELF，而是**带 Termux shebang 的脚本**
     * （gradle / sdkmanager / dart / flutter / composer / mvn / clang 包装脚本 …），
     * 其 shebang 写死 `/data/data/com.termux/files/usr/bin/sh`，
     * `bash.bashrc`/`profile` 也按该前缀查找。若在宿主机（App 私有目录前缀）直接执行，
     * 会得到：
     *   - `bash: /data/data/com.termux/files/usr/etc/bash.bashrc: Permission denied`
     *   - `bad interpreter: /data/data/com.termux/files/usr/bin/sh: Permission denied`
     * 于是「装了 gradle 却探测失败 / sdkmanager 不可执行」。
     * 进入 proot guest 后，前缀被绑定到 Termux 期望的位置，上述工具即可正常工作。
     *
     * @param extraEnv 调用方上下文变量（JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME …）。
     *                 这些宿主绝对路径在 guest 内同样可见（filesDir 是绑定挂载），可直接沿用。
     */
    fun guestCommandLine(
        context: Context,
        inner: String,
        extraEnv: Map<String, String> = emptyMap(),
        cwdHost: File? = null
    ): String {
        // 统一走 guestEnvWith：避免「命令行包装」与「pty 会话」两条路径各写一份合并逻辑而漂移。
        val guest = guestEnvWith(context, extraEnv)

        val exports = guest.entries.joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
        val script = "export $exports; $inner"
        val proot = prootBinary(context).absolutePath
        val prootLine = (listOf(proot) + prootArgs(context, script, cwdHost))
            .joinToString(" ") { shellQuote(it) }
        // 关键：PROOT_* / LD_LIBRARY_PATH 必须在**宿主 shell** 里前置导出（proot 启动时读取），
        // 不能只写进内层 guest 脚本；否则 proot 自身起不来（见 hostProotEnv 的实测症状清单）。
        val hostExports = hostProotEnv(context).entries
            .joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
        return "export $hostExports; $prootLine"
    }

    /**
     * 把一段 guest 脚本包成 proot 命令行参数（不含可执行文件本身）。
     * @param login 是否以登录 shell 运行（会读 profile；内部脚本建议 false，避免 bootstrap second-stage 噪声）
     */
    fun prootArgs(context: Context, script: String, cwdHost: File? = null, login: Boolean = false): List<String> {
        val args = mutableListOf("-r", rootfs(context).absolutePath)
        args += bindArgs(context)
        val work = cwdHost?.takeIf { it.isDirectory }?.absolutePath ?: GUEST_HOME
        args += listOf("-w", work, "$GUEST_PREFIX/bin/bash")
        args += if (login) listOf("-l", "-c", script) else listOf("-c", script)
        return args
    }

    /** 交互式终端：proot + 交互 bash（供终端页使用）。 */
    fun terminalArgs(context: Context, cwdHost: String?): List<String> {
        val args = mutableListOf("-r", rootfs(context).absolutePath)
        args += bindArgs(context)
        args += listOf("-w", cwdHost ?: GUEST_HOME, "$GUEST_PREFIX/bin/bash", "-i")
        return args
    }

    // ---------------------------------------------------------------- 软件源

    /**
     * 探测并写入一个真实可达的 Termux 源，返回实际使用的源。
     *
     * 之所以要主动写：bootstrap 自带的 sources.list 指向 packages-cf，在部分网络下不可达；
     * 而 Termux 的 `pkg`/`termux-change-repo` 交互流程在自动化安装里不可用。
     */
    fun ensureMirror(context: Context, force: Boolean = false): String {
        val file = sourcesList(context)
        if (!force && file.isFile) {
            val current = file.readText().lineSequence()
                .firstOrNull { it.trim().startsWith("deb ") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)
            if (current != null && MIRRORS.contains(current) && probe(current)) return current
        }
        val chosen = MIRRORS.firstOrNull { probe(it) } ?: MIRRORS.first()
        file.parentFile?.mkdirs()
        file.writeText("deb $chosen stable main\n")
        return chosen
    }

    /**
     * 源可用性探测：**必须验证拿到的是真正的 apt 索引**，不能只看 HTTP 状态码。
     *
     * 血泪教训（2026-09 真机）：华为云的 termux 路径已下线，请求返回 `200 OK` +
     * `text/html`（官网页面）。旧实现只判 `code in 200..399`，于是把该源判为「最快可达」
     * 并写进 sources.list；apt 把 HTML 当 Release 解析 → `Clearsigned file isn't valid,
     * got 'NOSPLIT'` → 仓库未签名 → 包索引全空 → `Unable to locate package git`。
     * 用户侧表现就是「清空数据后工具链一个都装不上」。
     *
     * 因此这里做三重校验：状态码 + content-type 非 html + 响应体含签名索引特征。
     * 只读前 1KB（Release 的头部即含 Origin/Suite/PGP 头），不下载整个索引。
     */
    fun probe(baseUrl: String): Boolean = try {
        val conn = URL("$baseUrl/dists/stable/Release").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        val type = (conn.contentType ?: "").lowercase()
        val head = if (code in 200..399 && !type.contains("html")) {
            // 注意：不能用 InputStream.readNBytes（API 33+），minSdk 24 要手写 read(ByteArray)。
            val buf = ByteArray(1024)
            val n = conn.inputStream.use { it.read(buf) }
            if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
        } else {
            ""
        }
        conn.disconnect()
        val looksLikeIndex = head.contains("-----BEGIN PGP SIGNED MESSAGE-----") ||
            (head.contains("Origin:") && head.contains("Suite:")) ||
            head.contains("Package:")
        code in 200..399 && !type.contains("html") && looksLikeIndex
    } catch (t: Throwable) {
        false
    }

    // ---------------------------------------------------------------- 安装命令

    /**
     * 生成 apt 安装脚本。关键点：
     *  - 用 `apt-get` 而不是 `pkg`（`pkg` 会走 Termux 的交互式仓库选择）；
     *  - **不能**用 `proot -0`：Termux 的 apt/dpkg 被打了「拒绝 root」补丁；
     *  - 单连接 + 重试：Android 后台网络受限（standby 防火墙）时减少并发失败；
     *  - `apt-get update` 与 install 分开，便于把「源不可达」与「包不存在」区分开上报。
     */
    /**
     * 给 guest 里的各语言包管理器写入国内镜像（幂等：文件已存在且非空就**不覆盖**）。
     *
     * ## 为什么必须做
     * 「工具链装不上」在真机上十有八九不是软件源挂了，而是包管理器默认指向海外域名：
     *  - npm → registry.npmjs.org（TypeScript / ts-node / tsx / typescript-language-server 全靠它）
     *  - pip → pypi.org（Python 依赖）
     *  - Gradle/Maven → repo.maven.apache.org（在设备上构建 Android/Java 项目时的依赖下载）
     *  - Cargo → crates.io（Rust 依赖）
     * 这些域名在境内真机上经常几十秒无响应，表现就是「安装一直转圈 → 超时 → FAILED」。
     *
     * ## 为什么直接写宿主侧文件
     * `files/home` 就是 guest 的 `$HOME`（proot 绑定挂载），落盘即可被 guest 里的
     * npm/pip/gradle/cargo 读到；不必进 proot 执行脚本，因此不受 apt/dpkg 锁影响，也不怕安装中断。
     */
    private fun ensureMirrorConfigs(context: Context) {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        writeIfMissing(File(home, ".npmrc"), NPMRC_TEXT)
        writeIfMissing(File(home, ".pip/pip.conf"), PIP_CONF_TEXT)
        writeIfMissing(File(home, ".m2/settings.xml"), MAVEN_SETTINGS_TEXT)
        writeIfMissing(File(home, ".cargo/config.toml"), CARGO_CONFIG_TEXT)
        // init.gradle 必须能**覆盖升级**（原因见 GRADLE_INIT_TEXT 的 v2 说明）：旧版内容是
        // 「无条件往工程级仓库注入」，会让 Flutter / 新版 Android 模板工程在配置阶段直接 BUILD FAILED，
        // 而 writeIfMissing 遇到已存在的文件就永不更新 —— 于是修好的脚本永远到不了老设备上，
        // 用户看到的就是「换了版本还是编译不过」。
        writeIfChanged(File(home, ".gradle/init.gradle"), GRADLE_INIT_TEXT)
    }

    private fun writeIfMissing(file: File, text: String) {
        if (file.isFile && file.length() > 0L) return
        file.parentFile?.mkdirs()
        runCatching { file.writeText(text) }
    }

    /**
     * 「内容变了就重写」—— 用于**必须能升级**的全局脚本（目前是 Gradle 的 init.gradle）。
     *
     * 为什么不能沿用 [writeIfMissing]：全局脚本是**由应用版本决定**的驱动/兼容层，
     * 一旦某个版本写坏了（真机实例：v1 的 allprojects 注入与 FAIL_ON_PROJECT_REPOS 冲突，
     * Flutter/新模板工程在配置阶段直接 BUILD FAILED），用「已存在就不动」的语义
     * 会导致后续版本**永远修不好老设备**：脚本还在，构建就一直失败。
     * 这里用「内容不同即原子替换」，让升级真正生效，同时保持幂等（内容相同不写盘、不抖动 mtime）。
     */
    private fun writeIfChanged(file: File, text: String) {
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { runCatching { tmp.delete() } }
    }

    /** npm：TypeScript 工具链与 npm 全局包走 npmmirror（阿里）。 */
    private val NPMRC_TEXT = """
        registry=https://registry.npmmirror.com
        # 二进制包（如 esbuild）的下载域名也指向镜像，避免 postinstall 阶段卡住
        disturl=https://npmmirror.com/mirrors/node
        electron_mirror=https://npmmirror.com/mirrors/electron/
        sass_binary_site=https://npmmirror.com/mirrors/node-sass/
        puppeteer_download_host=https://npmmirror.com/mirrors
        fetch-timeout=120000
    """.trimIndent() + "\n"

    /** pip：Python 依赖走清华 pypi（带宽稳定、同步及时）。 */
    private val PIP_CONF_TEXT = """
        [global]
        index-url = https://pypi.tuna.tsinghua.edu.cn/simple
        trusted-host = pypi.tuna.tsinghua.edu.cn
        timeout = 60
        disable-pip-version-check = true
    """.trimIndent() + "\n"

    /** Maven：Java 依赖走阿里云公共仓库（顺带兜底 jcenter 旧坐标）。 */
    private val MAVEN_SETTINGS_TEXT = """
        <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
          <mirrors>
            <mirror>
              <id>aliyun-public</id>
              <name>Aliyun Maven Public</name>
              <url>https://maven.aliyun.com/repository/public</url>
              <mirrorOf>central</mirrorOf>
            </mirror>
          </mirrors>
        </settings>
    """.trimIndent() + "\n"

    /** Cargo：Rust 依赖走 rsproxy（字节镜像）。 */
    private val CARGO_CONFIG_TEXT = """
        [source.crates-io]
        replace-with = "rsproxy-sparse"

        [source.rsproxy]
        registry = "https://rsproxy.cn/crates.io-index"

        [source.rsproxy-sparse]
        registry = "sparse+https://rsproxy.cn/index/"

        [registries.rsproxy]
        index = "https://rsproxy.cn/crates.io-index"

        [net]
        git-fetch-with-cli = true
    """.trimIndent() + "\n"

    /**
     * Gradle 全局仓库镜像（init.gradle）。
     *
     * 为什么不是 .m2/settings.xml：Gradle **不读** Maven 的 settings.xml 镜像配置，
     * 它只认仓库声明。设备上构建 Android 工程时，依赖要从 google()/mavenCentral() 拉，
     * 境内经常超时；这里通过 init.gradle 把阿里云镜像插到所有工程仓库之前，
     * 官方仓库仍作为兜底（镜像缺某个 artifact 时可回退）。
     */
    private val GRADLE_INIT_TEXT = """
        // 由 NebulaForgeIDE 写入（gradle-init v2）—— 自动生成，请勿手改；应用启动时按需重写。
        //
        // 【v2 必修 · 真机事故】v1 写的是 allprojects { repositories { … } }，即往**工程级**仓库插。
        // Flutter 工程（android/settings.gradle.kts）与新版 Android 模板在 settings 里声明了
        //   repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        // 只要有脚本往 project 加仓库，构建就在**配置阶段**直接失败（真机原文）：
        //   Initialization script '/data/data/com.termux/files/home/.gradle/init.gradle' line: 4
        //   Build was configured to prefer settings repositories over project repositories
        //   but repository 'maven' was added by initialization script '…/init.gradle'
        // v2 因此改成「只在 settings 级注入」：settings 级仓库在两种仓库模式下都合法，
        // 且对新老工程一致生效；工程级注入只在确认目标是老工程（PREFER_PROJECT / 老 Gradle）时兜底。
        def NEBULA_MIRRORS = [
            'https://maven.aliyun.com/repository/public',
            'https://maven.aliyun.com/repository/google',
            'https://maven.aliyun.com/repository/gradle-plugin',
        ]
        def nebulaAddMirrors = { repos, prefix ->
            NEBULA_MIRRORS.eachWithIndex { u, i -> repos.maven { it.name = prefix + i; it.url = u } }
        }
        def nebulaHasIncludeBuild = { dir ->
            ['settings.gradle', 'settings.gradle.kts'].any { n ->
                def f = new File(dir, n)
                f.isFile() && f.text.contains('includeBuild(')
            }
        }
        // 插件仓库必须早于 settings 脚本求值，否则 plugins {} 用的仓库已经定下来了。
        // 整段包 try：Gradle < 6.0 没有 beforeSettings，老工程不能因为这段脚本而挂掉。
        try {
            gradle.beforeSettings { settings ->
                if (settings.gradle.parent != null) return
                try {
                    // 复合构建（Flutter 用 includeBuild(<flutter>/packages/flutter_tools/gradle)）：
                    // 插件仓库交给工程自己声明，别插手 —— 否则 included build 会以「仓库被 settings
                    // 文件加入」为由在配置阶段失败。
                    if (!nebulaHasIncludeBuild(settings.rootDir)) {
                        settings.pluginManagement.repositories {
                            nebulaAddMirrors(delegate, 'nebulaMirror')
                            gradlePluginPortal()
                        }
                    }
                } catch (Throwable ignored) { }
                try {
                    settings.dependencyResolutionManagement.repositories {
                        nebulaAddMirrors(delegate, 'nebulaMirror')
                    }
                } catch (Throwable ignored) { }   // Gradle < 6.8：没有 settings 级仓库 API，跳过
            }
        } catch (Throwable ignored) { }
        // 老工程兜底：只有确认**不是**「settings 优先」模式时，才往工程级仓库插镜像。
        gradle.settingsEvaluated { settings ->
            if (settings.gradle.parent != null) return
            try {
                def mode = null
                try { mode = settings.dependencyResolutionManagement.repositoriesMode.get() } catch (Throwable ignored) { }
                if (mode == null || mode == org.gradle.api.initialization.resolve.RepositoriesMode.PREFER_PROJECT) {
                    settings.gradle.allprojects { p ->
                        try { nebulaAddMirrors(p.repositories, 'nebulaMirror') } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable ignored) { }
        }
    """.trimIndent() + "\n"

    fun installScript(packages: List<String>, reinstall: Boolean = false): String {
        val pkgList = packages.joinToString(" ")
        // --reinstall：bootstrap 自带的库即使"版本号已满足"，文件也可能**是旧的**。
        // 真机取证：bootstrap 的 libc++_shared.so（1,374,336 字节）不含
        //   _ZNSt6__ndk113__hash_memoryEPKvm
        // 于是 apt 装上的 android-tools（aarch64 原生 adb）启动即报
        //   CANNOT LINK EXECUTABLE "adb": cannot locate symbol "_ZNSt6__ndk113__hash_memoryEPKvm"
        // 而仓库最新 libc++ 里的同名 .so（1,423,696 字节）**含**该符号。apt 不会升级"已安装"的
        // libc++，只能显式 --reinstall，这正是「adb 装上了却永远不能执行」的根因。
        val reinstallFlag = if (reinstall) "--reinstall " else ""
        return buildString {
            // ① 先等其它 apt/dpkg 退出：dpkg 前端锁同一时刻只允许一个持有者。
            //    判据用 /proc/<pid>/comm（进程名，不含参数）而不是命令行匹配——命令行匹配会把
            //    本脚本自己（bash -c "...apt-get install..."）也算成占用者，直接自锁死循环。
            append("i=0; while [ \$i -lt 600 ]; do busy=; ")
            append("for d in /proc/[0-9]*; do comm=\$(cat \$d/comm 2>/dev/null); ")
            append("case \"\$comm\" in apt-get|apt|dpkg|dpkg-deb|dpkg-trigger) busy=\"\$busy \${d#/proc/}(\$comm)\";; esac; done; ")
            append("[ -z \"\$busy\" ] && break; ")
            append("[ \$((i % 10)) -eq 0 ] && echo \"等待其它 apt/dpkg 进程释放 dpkg 锁：\$busy\"; ")
            append("sleep 2; i=\$((i+2)); done; ")
            append("if [ \$i -ge 600 ]; then echo \"等待 dpkg 前端锁超时：仍有 apt/dpkg 在运行（\$busy）\"; echo \"__EXIT__=100\"; exit 100; fi; ")
            append("export DEBIAN_FRONTEND=noninteractive; ")
            append("apt-get update 2>&1 | tail -n 3; ")
            // 移动网络下两个常见坑：① DNS/连接优先走 IPv6 黑洞时表现为反复 `Ign:` 重试、
            // 速率从几百 kB/s 抖到 0；② 并发连接被基站侧限流。强制 IPv4 + 单连接 + 有限超时 + 重试。
            append("apt-get -o Acquire::ForceIPv4=true -o Acquire::https::max_connections=1 -o Acquire::http::Pipeline-Depth=0 ")
            append("-o Acquire::Retries=5 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 install ")
            append("$reinstallFlag-y $pkgList; ")
            // ② 把 apt-get 的真实退出码作为脚本退出码传播出去。
            //    原来最后一句是 echo，脚本恒以 0 结束，安装失败只能靠「装完再探测一次」兜住，
            //    错误信息也就偏离了真正原因（apt 的报错被吞掉）。
            append("code=\$?; echo \"__EXIT__=\$code\"; exit \$code")
        }
    }
}
