package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.environment.TermuxGuest
import java.io.File

/**
 * Flutter 构建外壳（guest 内可执行脚本 `nb-flutter`）。
 *
 * ## 真机根因（用户截图取证）
 * 修好「No Android SDK found」之后，`flutter build apk` 往前走一格就撞上第二堵墙：
 *
 * ```
 * ProcessException: Found candidates, but lacked sufficient permissions to execute
 *   "/storage/emulated/0/NebulaForgeProjects/flutter-app/android/gradlew"
 * ```
 *
 * 原因是 Flutter 的 Android 构建**必须执行工程内的 `android/gradlew`**，而用户工程位于
 * `/storage/emulated/0`：
 *  - 挂载参数 `/dev/fuse /storage/emulated fuse rw,...,noexec` —— **noexec**；
 *  - FUSE 上文件权限固定 `0660`，`chmod +x` 不生效（沙箱实测 `chmod 755` 后仍是 `rw-rw----`）。
 *
 * 也就是说：**只要工程在 /sdcard 上，`gradlew` 就永远不可执行**，这是文件系统限制，
 * 与 SDK、模板、命令怎么写都无关。应用私有目录（`files/home`、`files/usr`，f2fs）没有 noexec，
 * Termux 用户态正是靠自己目录里的可执行文件跑起来的。
 *
 * ## 做法
 * 不下发 `flutter ...`，而是下发 `nb-flutter ...`：先把工程镜像到应用私有目录里构建，
 * 构建完再把产物（`build/app/outputs`、`build/web` 等）拷回原工程。用户看到的工程路径、
 * 产物路径都不变。
 *
 * ## 为什么做成外部脚本而不是拼进任务命令
 * 任务命令字符串会被写进 `tasks.json`、也会被「构建方式」弹窗复用，逻辑一多就要在 Kotlin
 * 字符串里塞几十个 `$`（转义噩梦、也无法单测）。做成 shim 后：命令侧只留一句
 * `command -v nb-flutter`，脚本侧可以用任意 shell 语法，并且能在沙箱里真实跑一遍。
 *
 * ## 落地约束
 *  - 只在 [BuildEnvironmentPreparer.prepare]（每次构建/任务启动都会走）里静默安装，幂等；
 *  - `@D@` 是脚本里的 `$` 占位符（Kotlin 字符串里写 `$` 需要转义，占位符更不易出错）；
 *  - shebang 必须指向 guest 里真实存在的 shell（Termux 用户态**没有** `/bin/sh`），
 *    所以按安装时的实际路径生成。
 */
object FlutterBuildShim {

    /** guest PATH 上的外壳名（`Environment.binDir` 已在构建注入的 PATH 里）。 */
    const val NAME = "nb-flutter"

    /** 脚本里的 `$` 占位符。 */
    private const val DOLLAR = "@D@"

    /** included build 补丁的版本标记（幂等判据；v2 起同时注入插件门户与依赖镜像）。 */
    private const val MIRROR_MARK = "nebula-included-build-mirror:v2"

    /** 国内依赖镜像（按顺序尝试）。 */
    private val MIRRORS = listOf(
        "https://maven.aliyun.com/repository/google",
        "https://maven.aliyun.com/repository/public",
        "https://repo.huaweicloud.com/repository/maven"
    )

    /** 为 included build 追加的插件门户块（`kotlin-dsl` / Kotlin 插件标记只存在于插件门户）。 */
    private val PLUGIN_MANAGEMENT_BLOCK = buildString {
        appendLine("// $MIRROR_MARK 由 NebulaForgeIDE 注入：该 included build 需要插件门户才能解析 kotlin-dsl / kotlin 插件标记。")
        appendLine("pluginManagement {")
        appendLine("    repositories {")
        appendLine("        maven { url = uri(\"https://maven.aliyun.com/repository/gradle-plugin\") }")
        appendLine("        gradlePluginPortal()")
        appendLine("    }")
        appendLine("}")
        appendLine()
    }

    /**
     * 安装/更新外壳脚本。
     * @return 有实际变更时返回给用户看的一句话；已是最新或无法写入时返回 null（不打扰构建）。
     */
    fun ensure(context: Context): String? {
        // Flutter 构建内部同样跑 Gradle，同样需要国内镜像 + Gradle 插件门户（init.d 脚本）。
        runCatching { GradleMavenMirrors.ensure(context) }
        // 全局 init.gradle 也要迁移到 v2：Flutter 工程根目录不是 Gradle 工程、且 Gradle 只会从
        // GRADLE_USER_HOME 加载 init 脚本 —— 老设备上残留的 v1（allprojects 注入）会让 Flutter 的
        // android/settings.gradle.kts（RepositoriesMode.FAIL_ON_PROJECT_REPOS）在配置阶段直接失败。
        runCatching { TermuxGuest.ensureGradleInit(context) }
        runCatching { patchGradlePluginRepositories(context) }
        val binDir = File(Environment.binDir(context))
        if (!binDir.isDirectory && !binDir.mkdirs()) return null
        val target = File(binDir, NAME)
        val text = script(shellFor(context))
        val current = if (target.isFile) runCatching { target.readText() }.getOrNull() else null
        if (current == text) {
            if (!target.canExecute()) runCatching { target.setExecutable(true, false) }
            return null
        }
        return runCatching {
            target.writeText(text)
            target.setExecutable(true, false)
            "已安装 Flutter 构建外壳（$NAME）：在可执行目录里构建，绕开 /storage 的 noexec"
        }.getOrNull()
    }

    /** guest 里真实存在的 shell：Termux 用户态没有 `/bin/sh`，必须用前缀内的绝对路径。 */
    /**
     * guest 的 `$HOME/.nebulaforge/build`（脚本里的状态目录）在 App 侧的真实位置。
     *
     * 真机取证：构建日志来自 pty 屏幕截取，抽水发生在**主线程**；主线程一旦被 I/O 卡住，
     * 面板就永远停在最后抓到的那一行（用户看到的「一直卡在工程镜像」），而 guest 可能早已
     * 推进到 Gradle。脚本因此把每个阶段与退出码写成文件，App 直接读文件判断真实进度。
     */
    fun stateDir(context: Context): File = File(context.filesDir, "home/.nebulaforge/build")

    /** 阶段哨兵文件：内容形如 `sync 源码同步`。 */
    fun stageFile(context: Context, projectRoot: File): File =
        File(stateDir(context), (projectRoot.name.ifBlank { "nf-project" }) + ".stage")

    /** 退出码哨兵文件：构建结束写入数字退出码（比屏幕标记更可靠）。 */
    fun rcFile(context: Context, projectRoot: File): File =
        File(stateDir(context), (projectRoot.name.ifBlank { "nf-project" }) + ".rc")

    private fun shellFor(context: Context): String {
        val bin = File(Environment.usrRoot(context), "bin")
        return listOf("bash", "sh", "dash")
            .map { File(bin, it) }
            .firstOrNull { it.isFile }
            ?.absolutePath
            ?: "/bin/sh"
    }

    /**
     * 给 Flutter SDK 里 `packages/flutter_tools/gradle` 这个 **included build** 的 settings 补上
     * **插件门户 + 国内依赖镜像**。
     *
     * 真机实测：`flutter build apk` 越过「No Android SDK found」之后连撞两堵墙：
     * ```
     * ① Plugin [id: 'org.gradle.kotlin.kotlin-dsl', version: '6.7.3'] was not found in any of the following sources
     *    Build file '.../flutter/packages/flutter_tools/gradle/build.gradle.kts' line: 7
     *
     * ② （补了插件门户后）编译它自身 :gradle:compileKotlin 时需要
     *    kotlin-compiler-embeddable-2.4.10，下载走 repo.maven.apache.org → 302 到 github.com
     *    → Connect timed out → 构建永久停在「下载中」（用户看到的就是卡死）。
     * ```
     * 该目录的 `settings.gradle.kts` 只有 `google()` + `mavenCentral()`，且工程级 init.d 注入
     * **对 included build 无效**（Gradle composite 护栏）。所以这里两处都必须就地补齐：
     *  - `pluginManagement`：`kotlin-dsl` 插件与 Kotlin 插件标记只存在于 Gradle 插件门户；
     *  - `dependencyResolutionManagement`：编译它自身所需的依赖必须走国内镜像。
     *
     * 幂等：以 [MIRROR_MARK] 版本标记判定，已注入就返回；改之前留 `.nebula-orig` 备份。
     */
    private fun patchGradlePluginRepositories(context: Context) {
        val settings = File(
            Environment.homeRoot(context),
            "flutter/packages/flutter_tools/gradle/settings.gradle.kts"
        )
        if (!settings.isFile) return
        val current = runCatching { settings.readText() }.getOrNull() ?: return
        // 幂等：以版本标记判定；已注入 v2 就直接返回（v1 只补了插件门户，见下）。
        if (current.contains(MIRROR_MARK)) return
        runCatching {
            File(settings.parentFile, settings.name + ".nebula-orig").takeIf { !it.isFile }?.writeText(current)
            val out = StringBuilder()
            // ① 插件门户：included build 解析 kotlin-dsl / kotlin 插件标记需要它（旧版缺口）。
            if (!current.contains("gradlePluginPortal()")) out.append(PLUGIN_MANAGEMENT_BLOCK)
            // ② 依赖镜像：该 included build 编译自身（:gradle:compileKotlin）需要
            //    kotlin-compiler-embeddable 等依赖——这是「补了插件门户后仍卡死」的真正原因。
            out.append(injectResolutionMirrors(current))
            settings.writeText(out.toString())
        }
    }

    /** 把国内依赖镜像插进 `dependencyResolutionManagement { repositories { … } }` 的首行。 */
    private fun injectResolutionMirrors(source: String): String {
        val anchor = source.indexOf("dependencyResolutionManagement")
        if (anchor < 0) return source
        val repos = source.indexOf("repositories {", anchor)
        if (repos < 0) return source
        val at = repos + "repositories {".length
        val lines = MIRRORS.joinToString("\n") { "        maven { url = uri(\"$it\") }" }
        val injection = "\n        // $MIRROR_MARK —— 否则 kotlin-compiler-embeddable 等依赖会走 " +
            "repo.maven.apache.org → 302 到 github.com → 真机连接超时，构建永久卡在「下载中」\n$lines"
        return source.substring(0, at) + injection + source.substring(at)
    }

    /** 生成脚本全文（`@D@` → `$`）。 */
    fun script(shellPath: String): String =
        ("#!$shellPath\n" + TEMPLATE).replace(DOLLAR, "$")

    private val TEMPLATE = """
# nb-flutter —— NebulaForge 的 Flutter 构建外壳（由 App 生成，改动会在下次构建被覆盖）
#
# 为什么需要它：Flutter 的 Android 构建**必须执行** <工程>/android/gradlew，而用户工程位于
# /storage/emulated/0 —— fuse 挂载（noexec）且文件权限固定 0660（chmod +x 不生效），
# 因此 gradlew 永远不可执行，真机报错：
#   ProcessException: Found candidates, but lacked sufficient permissions to execute
#     "/storage/emulated/0/.../<工程>/android/gradlew"
# 这里把工程镜像到应用私有目录（f2fs，可执行）里构建，结束后把产物拷回原工程。
# 工程路径、产物路径对用户保持不变。

set -u
# 构建命令通过 tee 输出时仍保留左侧命令的退出码。
# shellFor() 优先选择 Bash；不支持 pipefail 的兼容 shell 会忽略这一行。
set -o pipefail 2>/dev/null || true

nb_log() { printf '[nb-flutter] %s\n' "@D@*"; }

# ── 根治 unbound variable（用户实测：line 320 GRADLE_OPTS: unbound variable，5873ms 直接退出）──
# `set -u` 下任何未定义变量都会让构建在 Gradle 之前就崩掉。这里把常见变量**一次性预定义**，
# 以后新增引用也不会再犯同一个错；脚本还会把版本号打进日志，方便确认设备上跑的是哪一版。
: "@D@{GRADLE_OPTS:=}"
: "@D@{JAVA_HOME:=}"
: "@D@{ANDROID_HOME:=}"
: "@D@{ANDROID_SDK_ROOT:=}"
: "@D@{ANDROID_SDK:=}"
export GRADLE_OPTS JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT
nb_log "外壳版本 rev11（Gradle 阶段哨兵 / daemon 复用 + 3 worker + caching / 失败关键字与源码定位 / run→arm64 / SDK 探测）"

# ── 环境兜底（脚本是 set -u：任何未定义变量都会让构建在真正开始之前就崩掉） ──────────────
# 真机根因（用户实测日志）：`nb-flutter: line 320: GRADLE_OPTS: unbound variable`
# → 进程 5873ms 直接退出，Gradle 一步都没跑到。除了把引用改成「加默认值」的写法，这里再兜住 HOME/TMPDIR。
: "@D@{HOME:=/data/data/com.termux/files/home}"
export HOME
: "@D@{TMPDIR:=@D@HOME/.cache}"
export TMPDIR
mkdir -p "@D@TMPDIR" 2>/dev/null

# ── Android SDK 定位：内层 flutter 看不到 SDK 时只把 Linux desktop 当候选 ──────────────
# 真机现象：`No supported devices connected. ... Linux (desktop) linux-arm64`。
nb_sdk=""
for nb_s in "@D@{ANDROID_HOME:-}" "@D@{ANDROID_SDK_ROOT:-}" "@D@{ANDROID_SDK:-}" \
            "@D@{PREFIX:-}/opt/android-sdk" "@D@HOME/android-sdk" "@D@HOME/Android/Sdk" \
            /data/data/com.termux/files/usr/opt/android-sdk; do
  [ -n "@D@nb_s" ] || continue
  [ -d "@D@nb_s/platforms" ] || [ -d "@D@nb_s/platform-tools" ] || continue
  nb_sdk="@D@nb_s"; break
done
if [ -n "@D@nb_sdk" ]; then
  export ANDROID_HOME="@D@nb_sdk"
  export ANDROID_SDK_ROOT="@D@nb_sdk"
  nb_log "Android SDK：@D@nb_sdk"
  case ":@D@PATH:" in *":@D@nb_sdk/platform-tools:"*) ;; *) export PATH="@D@nb_sdk/platform-tools:@D@PATH" ;; esac
else
  nb_log "⚠ 未找到 Android SDK（内层 flutter 会只认 Linux desktop；可设 ANDROID_HOME）"
fi

# ── 阶段哨兵 / 心跳（真机根因：面板日志来自 pty「屏幕截取」，而抽水发生在 App 主线程） ──
# 取证结论（设备侧 /proc 采样）：构建期 App 主线程连同 10 个协程 worker 全部卡在**不可中断
# I/O**（内核 D 状态，18 个线程，10 分钟零唤醒）；系统侧没冻结、内存充足、shell 读写 /storage
# 正常 —— 即**应用自己**被 I/O 卡住，于是 pty 不再被抽水，guest 写满管道后整棵树停摆。
# 现象就是：面板永远停在最后抓到的那一行（用户看到「一直卡在工程镜像…」），而 guest 其实可能
# 已经推进（实测当时 dartvm+java 已在跑、Gradle 已烧了 69s CPU）。所以：
#   1) 每个阶段都写一份**文件型哨兵**，App 直接读文件判断真实进度，不依赖屏幕；
#   2) 长阶段定时打「心跳行」，让「安静」和「卡死」可区分（配合 App 侧看门狗）。
nb_state="@D@{NEBULAFORGE_STATE_DIR:-@D@HOME/.nebulaforge/build}"
mkdir -p "@D@nb_state" 2>/dev/null
nb_t0=`date +%s 2>/dev/null` || nb_t0=0
nb_el() { nb_n=`date +%s 2>/dev/null` || nb_n="@D@nb_t0"; nb_d=`expr "@D@nb_n" - "@D@nb_t0" 2>/dev/null` || nb_d=0; [ -n "@D@nb_d" ] || nb_d=0; printf '%s' "@D@nb_d"; }
nb_stage_name=none
nb_beat_pid=

nb_stage() {
  nb_stage_name="@D@1"
  { printf '%s %s\n' "@D@1" "@D@2"; } > "@D@nb_state/.stage.tmp" 2>/dev/null \
    && mv -f "@D@nb_state/.stage.tmp" "@D@nb_state/@D@nb_name.stage" 2>/dev/null
  nb_log "⏱ 阶段 @D@1/@D@2（已 @D@(nb_el)s）"
}

# ── 终止整棵构建树（真机根因：点「停止」没反应）────────────────────────────────
# 以前 App 侧只「取消协程」，而真正的构建进程是 guest 里的一整棵树
# （PTY shell → proot → flutter → dart/java），不属于 App 进程，取消协程对它毫无作用，
# 于是表现为「卡死了也停不下来」。这里以镜像工程路径为特征杀（flutter/dart/java/gradle
# 的命令行里都带该路径），再补一刀杀本脚本所在进程组；先 TERM 后 KILL。
nb_kill_tree() {
  for nb_sig in TERM KILL; do
    pkill -@D@nb_sig -f "@D@nb_work" 2>/dev/null || true
    kill -@D@nb_sig 0 2>/dev/null || true
    sleep 1
  done
  exit 130
}

nb_beat_start() {
  nb_beat_pid=
  ( while :; do sleep 20 2>/dev/null || sleep 20; \
      # 停止请求：App 写 <state>/<name>.kill，这里每 20 秒握手一次。
      if [ -f "@D@nb_state/@D@nb_name.kill" ]; then
        rm -f "@D@nb_state/@D@nb_name.kill" 2>/dev/null
        nb_log "⏹ 收到停止请求：正在终止构建进程…"
        nb_kill_tree
      fi
      # 硬超时兜底：以前构建能挂 6 小时以上都不结束（日志实测「已 22654s」）。
      # 默认 45 分钟仍无结果就判定卡死，强制终止并留下可读原因。
      nb_elapsed=`nb_el`; \
      if [ "@D@nb_elapsed" -gt "@D@{NEBULAFORGE_BUILD_TIMEOUT:-2700}" ] 2>/dev/null; then
        nb_log "⏹ 构建已超过 @D@{NEBULAFORGE_BUILD_TIMEOUT:-2700}s 仍无结果：判定卡死，强制终止"
        nb_log "  常见原因：AGP 正在联网下载 SDK 组件（如 NDK，约 1GB）。本 IDE 已默认禁用该自动下载。"
        nb_kill_tree
      fi
      nb_s=`cat "@D@nb_state/@D@nb_name.stage" 2>/dev/null`; \
      nb_log "⏱ 仍在构建（已 @D@(nb_el)s，阶段 @D@{nb_s:-?}）"; done ) 2>/dev/null &
  nb_beat_pid="@D@!"
}

nb_beat_stop() {
  [ -n "@D@nb_beat_pid" ] && kill "@D@nb_beat_pid" 2>/dev/null
  nb_beat_pid=
}

# ── Gradle 任务级实时进度（真机痛点：整段构建期阶段恒为 build，面板看不出在做什么）──────────
# 面板日志来自 pty「屏幕抽水」，而抽水发生在 App 主线程；主线程一旦被不可中断 I/O 卡住，
# 面板就永远停在最后抓到的那一行（用户看到的就是「一直卡在 build 阶段」）。这里改为**后台
# 直接读日志文件**（nb_out，本地文件，不受抽水影响），把最近出现的 Gradle 任务行
# （`> Task :app:compileDebugKotlin`、`> Task :app:processDebugResources`…）写进阶段哨兵；
# App 看门狗每 3s 读一次 → 面板滚动显示「阶段：> Task :xxx」。这样「Gradle 资源编译 →
# D8/dex → Kotlin/Java 编译 → 打包」各步都可见，也把「安静」与「卡死」区分开。
nb_prog_pid=
nb_prog_start() {
  nb_prog_pid=
  rm -f "@D@nb_state/@D@nb_name.progstop" 2>/dev/null
  ( nb_prev=""
    while :; do
      sleep 3 2>/dev/null || sleep 3
      # 结束信号：脚本收尾会 touch 这个停止旗标（见 nb_prog_stop）。
      [ -f "@D@nb_state/@D@nb_name.progstop" ] && break
      nb_cur=`grep -a '^> Task ' "@D@nb_out" 2>/dev/null | tail -n 1`
      if [ -n "@D@nb_cur" ] && [ "@D@nb_cur" != "@D@nb_prev" ]; then
        nb_prev="@D@nb_cur"
        { printf '%s\n' "@D@nb_cur"; } > "@D@nb_state/.stage.tmp" 2>/dev/null \
          && mv -f "@D@nb_state/.stage.tmp" "@D@nb_state/@D@nb_name.stage" 2>/dev/null
        nb_log "⏱ 阶段 build › @D@nb_cur"
      fi
    done ) 2>/dev/null &
  nb_prog_pid="@D@!"
}

nb_prog_stop() {
  touch "@D@nb_state/@D@nb_name.progstop" 2>/dev/null
  [ -n "@D@nb_prog_pid" ] && kill "@D@nb_prog_pid" 2>/dev/null
  nb_prog_pid=
}

# 统一的 flutter 调用：一律降优先级（nice 10）后执行。
# 真机 ANR 取证：guest 里的 proot 是 ptrace 拦截**每一条**系统调用，`flutter build`（Gradle+Kotlin
# 编译 + dex）会把整机 CPU 吃满，IDE 主线程 5s 内拿不到调度 → 系统弹「星弦IDE 无响应」。降优先级
# 后前台界面始终抢得到 CPU；缺 nice 时退回直接调用，不做无谓报错。
nb_flutter() {
  if command -v nice >/dev/null 2>&1; then
    nice -n 10 flutter "@D@@"
  else
    flutter "@D@@"
  fi
}

# Gradle 用户目录显式固定：init.d 里的镜像/插件门户配置就写在这里（Flutter 内部 Gradle 才能吃到）。
export GRADLE_USER_HOME="@D@{GRADLE_USER_HOME:-@D@HOME/.gradle}"
nb_proj="@D@PWD"

# ── 1. 选一个「真的能执行文件」的工作根（实测探针，不去猜挂载参数） ──────────────
nb_root=""
nb_cands="@D@{NEBULAFORGE_BUILD_ROOT:-} @D@{HOME:-}/.nebulaforge/build @D@{PREFIX:-}/nf-build /data/data/com.termux/files/home/.nebulaforge/build"
for nb_c in @D@nb_cands; do
  [ -n "@D@nb_c" ] || continue
  # /storage、/sdcard 是 fuse 挂载：noexec，且文件权限固定 0660（chmod 不生效）。
  # 注意 proot 会绕过 noexec 帮我们 exec（沙箱实测探针“通过”），但 Flutter 侧还会按
  # 权限位检查可执行性并直接拒掉，所以这里必须硬性排除，不能只靠探针。
  case "@D@nb_c" in
    /storage/*|/sdcard/*|/mnt/sdcard/*|/mnt/media_rw/*) continue ;;
  esac
  mkdir -p "@D@nb_c" 2>/dev/null || continue
  nb_probe="@D@nb_c/.nb-exec-probe"
  printf '#!/bin/sh\nexit 0\n' > "@D@nb_probe" 2>/dev/null || continue
  chmod 700 "@D@nb_probe" 2>/dev/null
  if "@D@nb_probe" >/dev/null 2>&1; then nb_root="@D@nb_c"; fi
  rm -f "@D@nb_probe" 2>/dev/null
  if [ -n "@D@nb_root" ]; then break; fi
done

if [ -z "@D@nb_root" ]; then
  nb_log "⚠ 找不到可执行目录，退回就地构建（若出现 gradlew 权限错误，属 /storage noexec 的已知限制）"
  nb_flutter "@D@@"
  exit @D@?
fi

nb_name=`basename "@D@nb_proj" 2>/dev/null`
[ -n "@D@nb_name" ] || nb_name=nf-project
nb_work="@D@nb_root/@D@nb_name"
mkdir -p "@D@nb_work" 2>/dev/null || { nb_log "⚠ 无法使用 @D@nb_work，退回就地构建"; nb_flutter "@D@@"; exit @D@?; }

# ── 2. 源码同步（有界 + 增量 + 可超时；唯一会大量读 /storage 的地方） ────────────────
# 旧实现每次构建都对整棵工程做 tar|tar，把 /storage(FUSE) 全量读一遍 —— 真机上正是这一步会
# 长时间卡在不可中断 I/O（内核 D），把 App 主线程与 pty 抽水一起拖死。现在：
#   1) 只同步构建需要的路径（排除 .git / 非 android 平台目录 / 构建缓存），I/O 降一个量级；
#   2) 首次全量，之后按 mtime **增量**（通常每次只有个位数文件）；
#   3) 同步放在后台子 shell 跑，前台最多等 NB_SYNC_BUDGET 秒；超时就**用现有镜像继续构建**
#      （I/O 真卡死时这是唯一能自救的退路，绝不让构建无限期挂在同步上）；
#   4) 超时/失败都写进哨兵与日志，面板立刻能看出「卡在同步」。
NB_SYNC_BUDGET="@D@{NEBULAFORGE_SYNC_BUDGET:-120}"
nb_need_mirror=1
case "@D@nb_proj" in
  /storage/*|/sdcard/*|/mnt/sdcard/*|/mnt/media_rw/*) nb_need_mirror=1 ;;
  *) nb_need_mirror=0 ;;
esac

if [ "@D@nb_need_mirror" = 0 ]; then
  nb_work="@D@nb_proj"
  nb_log "工程已在可执行文件系统（@D@nb_proj）上，就地构建，免镜像"
  nb_stage local "就地构建（免镜像）"
else
  nb_stage sync "源码同步"
  nb_sync_rc="@D@nb_state/.sync-@D@nb_name.rc"
  nb_stamp="@D@nb_work/.nb-sync-stamp"
  rm -f "@D@nb_sync_rc" 2>/dev/null
  nb_first=1
  [ -f "@D@nb_stamp" ] && nb_first=0
  if [ "@D@nb_first" = 1 ]; then nb_mode=首次全量; else nb_mode=增量; fi
  nb_log "工程镜像到可执行目录：@D@nb_work（源工程在 noexec 的 /storage 上，gradlew 无法执行；@D@nb_mode同步）"
  # 源工程刚被 `flutter clean`（没有 build/）时镜像里也清掉，保住「先清理再构建」的语义
  if [ ! -d "@D@nb_proj/build" ] && [ -d "@D@nb_work/build" ]; then rm -rf "@D@nb_work/build" 2>/dev/null; fi

  (
    nb_err=0
    if [ "@D@nb_first" = 1 ] && command -v tar >/dev/null 2>&1; then
      ( cd "@D@nb_proj" && tar -cf - \
          --exclude=./build --exclude=./.git --exclude=./.idea --exclude=./.gradle \
          --exclude=./android/.gradle --exclude=./android/.cxx --exclude=./node_modules \
          --exclude=./ios --exclude=./macos --exclude=./linux --exclude=./windows \
          --exclude=./.dart_tool/flutter_build . ) | ( cd "@D@nb_work" && tar -xf - ) || nb_err=1
    elif command -v find >/dev/null 2>&1; then
      ( cd "@D@nb_proj" 2>/dev/null || exit 9
        find . \( -name build -o -name .git -o -name .idea -o -name .gradle -o -name .cxx \
                  -o -name node_modules -o -name ios -o -name macos -o -name linux -o -name windows \
                  -o -name flutter_build -o -name .dart_tool \) -prune -o -type f -newer "@D@nb_stamp" -print \
          > "@D@nb_state/.sync-list.txt" 2>/dev/null || exit 8
        nb_n=`wc -l < "@D@nb_state/.sync-list.txt" 2>/dev/null`
        nb_log "增量同步 @D@{nb_n:-0} 个变更文件"
        while IFS= read -r nb_f; do
          [ -n "@D@nb_f" ] || continue
          nb_d=`dirname "@D@nb_f"` 2>/dev/null
          mkdir -p "@D@nb_work/@D@nb_d" 2>/dev/null
          cp -p "@D@nb_f" "@D@nb_work/@D@nb_f" 2>/dev/null || nb_err=1
        done < "@D@nb_state/.sync-list.txt"
      ) || nb_err=1
    else
      cp -R "@D@nb_proj/." "@D@nb_work/" 2>/dev/null || nb_err=1
    fi
    if [ "@D@nb_err" = 0 ]; then
      # stamp 回拨 2 秒：避免「同一秒内被改动的文件」在下次增量里漏掉
      nb_slack=`date -d '-2 seconds' '+%Y%m%d%H%M.%S' 2>/dev/null`
      if [ -n "@D@nb_slack" ]; then touch -t "@D@nb_slack" "@D@nb_stamp" 2>/dev/null; fi
      [ -f "@D@nb_stamp" ] || touch "@D@nb_stamp" 2>/dev/null
      echo 0 > "@D@nb_sync_rc" 2>/dev/null
    else
      echo 1 > "@D@nb_sync_rc" 2>/dev/null
    fi
  ) >/dev/null 2>&1 &

  nb_waited=0
  while [ ! -f "@D@nb_sync_rc" ] && [ "@D@nb_waited" -lt "@D@NB_SYNC_BUDGET" ]; do
    sleep 1 2>/dev/null || sleep 1
    nb_waited=`expr "@D@nb_waited" + 1`
    if [ "`expr "@D@nb_waited" % 20`" = 0 ]; then
      nb_log "⏱ 源码同步中（@D@nb_waited s；首次全量通常 10~40s，超过 @D@NB_SYNC_BUDGET s 将先用现有镜像继续）"
    fi
  done
  if [ -f "@D@nb_sync_rc" ]; then
    if [ "`cat "@D@nb_sync_rc" 2>/dev/null`" = 0 ]; then
      nb_log "源码同步完成（@D@nb_waited s），开始构建"
    else
      nb_log "⚠ 源码同步报错，沿用上次镜像继续"
    fi
  else
    nb_log "⚠ 源码同步超时（@D@nb_waited s）——多半卡在 /storage 的 I/O 上；本次先用现有镜像继续构建"
  fi
fi
cd "@D@nb_work" 2>/dev/null || { nb_flutter "@D@@" ; exit @D@?; }

# ── 3. android/ 原生骨架自愈（模板只生成 Dart 层）+ Gradle 守护进程关闭 ──────────────
nb_stage platform "生成/检查 android 原生骨架"
nb_needs_platform=0
case "@D@{1:-}" in
  build)
    case "@D@{2:-}" in apk|appbundle) nb_needs_platform=1 ;; esac ;;
esac
if [ "@D@nb_needs_platform" = 1 ] && [ ! -f android/app/build.gradle ] && [ ! -f android/app/build.gradle.kts ]; then
  nb_log "缺少 android/ 原生骨架（模板只生成 Dart 层），先用 flutter create 生成…"
  nb_flutter create --platforms=android . || nb_log "⚠ flutter create 失败，继续尝试构建"
fi
if [ -f android/gradle.properties ]; then
  if ! grep -q '^org.gradle.daemon=' android/gradle.properties; then
    printf 'org.gradle.daemon=true\n' >> android/gradle.properties
  fi
  # 真机 ANR（系统弹「星弦IDE 无响应」）：Gradle 默认按 CPU 核数铺开 worker，而 guest 里的
  # proot 是 ptrace 拦截**每一条**系统调用，构建期整机 CPU 会被吃满，IDE 主线程被饿死 →
  # 系统判定无响应。这里把并行度压到 2 并关闭 parallel，让 UI 始终抢得到 CPU。
  if ! grep -q '^org.gradle.workers.max=' android/gradle.properties; then
    printf 'org.gradle.workers.max=2\n' >> android/gradle.properties
  fi
  if ! grep -q '^org.gradle.parallel=' android/gradle.properties; then
    printf 'org.gradle.parallel=false\n' >> android/gradle.properties
  fi
  # 强制 plain 控制台：输出被 `tee` 接管（非 tty）时 Gradle 默认可能不逐条打印任务行，
  # 面板就看不到「资源编译 / Kotlin 编译 / 打包」这些真实子步骤。plain 保证每个
  # `> Task :app:xxx` 都成行输出，既是详细进度，也供 nb_prog_start 回填阶段哨兵。
  if ! grep -q '^org.gradle.console=' android/gradle.properties; then
    printf 'org.gradle.console=plain\n' >> android/gradle.properties
  fi
  # 真机卡死两点直接对策：Kotlin 编译改**进程内**（不再 fork 一个在 proot 里可能握不上手的
  # 编译守护进程）；HTTP 超时压短（网络黑洞时快速失败，而不是零 CPU 死等）。
  # 真机回归（2.12.90）：Flutter 模板自己写进 android/gradle.properties 的是
  #   org.gradle.jvmargs=-Xmx8G -XX:MaxMetaspaceSize=4G -XX:+HeapDumpOnOutOfMemoryError ...
  # 这里以前是「已存在就完全不碰」，于是 8G 堆 + 4G 元空间原样生效 —— 真机上守护进程刚起来就被
  # 低内存杀手干掉，日志表现为 `Gradle build daemon disappeared unexpectedly`（Flutter 构建失败的
  # 直接现象之一）。所以改成**无论是否存在都钳制**：对齐 IDE 自身构建已验证可用的档位
  # （2G 堆 / 512M 元空间），并关掉 OOM 堆转储（触发时会把几个 G 的 dump 写进用户存储）。
  if grep -q '^org.gradle.jvmargs=' android/gradle.properties 2>/dev/null; then
    sed -i 's/-Xmx[0-9]*[kKmMgG]*/-Xmx2048m/g; s/-XX:MaxMetaspaceSize=[0-9]*[kKmMgG]*/-XX:MaxMetaspaceSize=512m/g; s/-XX:+HeapDumpOnOutOfMemoryError/-XX:-HeapDumpOnOutOfMemoryError/g' android/gradle.properties 2>/dev/null
  else
    printf 'org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8\n' >> android/gradle.properties
  fi
  if ! grep -q '^kotlin.compiler.execution.strategy=' android/gradle.properties; then
    printf 'kotlin.compiler.execution.strategy=in-process\n' >> android/gradle.properties
  fi
  if ! grep -q 'http.connectionTimeout' android/gradle.properties; then
    printf 'systemProp.org.gradle.internal.http.connectionTimeout=15000\nsystemProp.org.gradle.internal.http.socketTimeout=20000\n' >> android/gradle.properties
  fi
  # 提速（真机实测：Flutter 构建主要慢在「配置 + 重复编译」上）：
  #  * caching：重复构建命中构建缓存（改一行 Dart 再构建能省掉整段 AGP 任务）—— **这是大头**。
  #  * daemon：连续构建复用同一个 JVM，第二次起不必重付「JVM 启动 + 全量配置」的时间。
  #
  # 真机根因（用户实测「构建还是很慢」）：上面那段先写了 `org.gradle.parallel=false`，而这里
  # 当初的判定用的是 `grep -q 'org.gradle.parallel='`（**少了行首锚点 ^**）→ 条件永远为假，
  # 于是 `org.gradle.caching=true` **从来没有被写进 gradle.properties**：构建缓存一直是关的，
  # 每次构建都在重复做之前已经做过的事，这就是「慢」的主要来源。
  # 现在改成「按 key 强制设值」（存在就替换、不存在才追加），幂等且不会自相矛盾。
  #
  # 注意：`parallel` / `workers.max` 上面**刻意**设成了保守值（proot 逐条拦截 syscall，
  # 并行度拉满会把整机 CPU 吃满，IDE 主线程被饿死 → 系统弹 ANR），所以这里只改「缓存 +
  # 守护进程」，**不动并行度**，避免把已经修好的 ANR 又改回来。
  nb_setprop() {
    if grep -q "^@D@1=" android/gradle.properties 2>/dev/null; then
      sed -i "s|^@D@1=.*|@D@1=@D@2|" android/gradle.properties 2>/dev/null
    else
      printf '%s=%s\n' "@D@1" "@D@2" >> android/gradle.properties
    fi
  }
  # ── 并行度/守护进程：从「最保守」改回「够快又不炸」────────────────────────────
  # 2.12.x 曾把 workers.max 压到 1 并关掉 daemon，理由是旧日志里出现过
  # `Gradle build daemon disappeared unexpectedly` 与 ANR。但真机取证（files/logs/run.log
  # 与 `dumpsys activity exit-info`）显示：
  #   * 那几次「daemon 消失」的真正原因是**宿主进程被系统停掉**
  #     （reason=10 USER REQUESTED … iAwareF[SystemManager]），不是 daemon 不可靠；
  #   * 关掉 daemon + 单 worker 之后，每次构建都要重付「JVM 启动 + 全量配置」，再加上串行
  #     执行，单次 assembleDebug 实测 >6 分钟；用户等不到结束就点了「停止」
  #     （run.log: FINISH exit= cancelled=true message=已停止），体感正是「一直卡在 build」。
  # 本机 8 核 / 16 GB（构建期间 MemAvailable ≈ 8.8 GB），所以改为 daemon 复用 + 3 worker +
  # 2 GB 堆：能明显提速，又不至于把 IDE 主线程饿死；parallel 仍关（Flutter 模块图基本是
  # 链式的，开了收益很小，却最容易把 CPU 打满）。
  nb_setprop org.gradle.caching true
  nb_setprop org.gradle.daemon true
  nb_setprop org.gradle.workers.max 3
  nb_setprop org.gradle.parallel false
  nb_setprop org.gradle.jvmargs "-Xmx2048m -XX:MaxMetaspaceSize=512m -XX:-HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
  # guest 里没有可用的 inotify。
  nb_setprop org.gradle.vfs.watch false
  # ── 真机卡死根因（2.12.95 用户实测：Flutter 构建永久停在 build 阶段）──────────────
  # 日志铁证：
  #   Checking the license for package NDK (Side by side) 28.2.13676358 ... accepted.
  #   Preparing "Install NDK (Side by side) 28.2.13676358 v.28.2.13676358"
  # 之后再无输出、任务永不结束；设备上只留下 android-sdk/ndk/<版本>/.installer 空壳
  # （实测 11K，完整 NDK 应有 2~4GB）。
  # 原因：Flutter 新模板 app/build.gradle.kts 写 `ndkVersion = flutter.ndkVersion`
  # （新版 Flutter SDK 默认 28.2），AGP 发现该 NDK 未安装就**自动去 dl.google.com 下载
  # 约 1GB**，境内网络下这几乎不可能完成 → 永久挂起。
  # 而纯 Dart/Flutter 工程**根本不需要 NDK**：so 由 Flutter 预编译提供，strip 走 SDK
  # build-tools 里的 llvm-strip。所以这里两刀切断：
  #   ① android.builder.sdkDownload=false —— AGP 不再自动安装任何 SDK 组件；
  #   ② 注释掉工程里的 ndkVersion 声明 —— AGP 连「需要哪个 NDK」都不会去问。
  nb_setprop android.builder.sdkDownload false
  nb_disable_ndk() {
    for nb_f in android/app/build.gradle.kts android/app/build.gradle; do
      [ -f "@D@nb_f" ] || continue
      if grep -q "^[[:space:]]*ndkVersion[[:space:]]*=" "@D@nb_f" 2>/dev/null; then
        awk '/^[[:space:]]*ndkVersion[[:space:]]*=/ { print "// ndkVersion：已由星弦 IDE 注释（纯 Flutter 工程无需 NDK，避免 AGP 联网下载约 1GB）"; next } { print }' "@D@nb_f" > "@D@nb_f.nbtmp" 2>/dev/null \
          && mv -f "@D@nb_f.nbtmp" "@D@nb_f" 2>/dev/null \
          && nb_log "已注释 ndkVersion 并关闭 AGP 的 SDK 自动下载（防止构建卡在 NDK 安装）"
      fi
    done
  }
  nb_disable_ndk
  # ── 占位 NDK（真机验证得出的最终方案，2.12.98）──────────────────────────────────
  # 只注释工程里的 ndkVersion 不够：Flutter 的 Gradle 插件在**配置期**会把它强制设回
  # （值随 Flutter 版本变，实测 28.2.13676358），AGP 于是校验该 NDK 是否可用，真机报：
  #   [CXX1101] NDK at <sdk>/ndk/28.2.13676358 did not have a source.properties file
  # （此时已经不再联网下载了 —— sdkDownload=false 生效了，但构建依旧失败。）
  # 纯 Flutter 工程根本不做 native 编译（so 由 Flutter 预编译提供），真 NDK 约 1GB 且
  # 境内下载必卡死。AGP 只校验 source.properties 这一个文件的存在与格式，因此给每个
  # 「被要求的 NDK 版本」补一个结构合法的占位目录（不含任何工具链）。
  nb_placeholder_ndk() {
    nb_sdk="@D@HOME/android-sdk"
    [ -d "@D@nb_sdk" ] || return 0
    nb_root="@D@nb_sdk/ndk"
    nb_list=""
    [ -d "@D@nb_root" ] && nb_list=`ls "@D@nb_root" 2>/dev/null`
    if [ -z "@D@nb_list" ]; then
      # ndk 目录还不存在（AGP 尚未建空壳）：从 Flutter SDK 插件里问出它将声明的版本号
      nb_list=`grep -rhoE '2[0-9]\.[0-9]+\.[0-9]+' "@D@HOME/flutter/packages/flutter_tools/gradle" 2>/dev/null | head -1`
    fi
    [ -n "@D@nb_list" ] || return 0
    for nb_v in @D@nb_list; do
      nb_d="@D@nb_root/@D@nb_v"
      mkdir -p "@D@nb_d" 2>/dev/null || continue
      [ -f "@D@nb_d/source.properties" ] && continue
      printf 'Pkg.Desc = Android NDK\nPkg.Revision = %s\n' "@D@nb_v" > "@D@nb_d/source.properties" 2>/dev/null \
        && nb_log "占位 NDK 已就绪：@D@nb_v（仅满足 AGP 校验；不做 native 编译，故无需真 NDK 的约 1GB 下载）"
    done
  }
  nb_placeholder_ndk
  # ── NDK 工具链替身（2.12.102 真机验证）────────────────────────────────────────
  # 占位 NDK 只骗过 AGP 的「校验」，但 :app:stripDebugDebugSymbols 会真的去执行
  #   <ndk>/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
  # 官方 NDK 只发布 linux-x86_64 / darwin-* 主机工具（**没有 linux-aarch64 版**，已核对
  # Google 官方仓库 repository2-3.xml），arm64 设备上 execve 直接失败，构建以
  #   A problem occurred starting process 'command .../llvm-strip'
  # 告终（真机日志原文见 nb-flutter.log）。纯 Flutter 工程不做 native 编译，strip 只为
  # 减体积，所以这里按 NDK 的真实目录形态补一套**可执行**工具：
  #   ① guest 里若真有 strip/llvm-strip（用户装过 binutils/llvm），软链过去 —— 真剥离；
  #   ② 否则落一个「原样复制」的替身脚本（aarch64 + shebang，proot 下实测可执行成功）。
  nb_ndk_toolchain() {
    nb_root="@D@HOME/android-sdk/ndk"
    [ -d "@D@nb_root" ] || return 0
    for nb_d in "@D@nb_root"/*; do
      [ -d "@D@nb_d" ] || continue
      nb_bin="@D@nb_d/toolchains/llvm/prebuilt/linux-x86_64/bin"
      mkdir -p "@D@nb_bin" 2>/dev/null || continue
      nb_real=""
      for nb_c in "@D@PREFIX/bin/llvm-strip" "@D@PREFIX/bin/strip" /usr/bin/llvm-strip /usr/bin/strip; do
        [ -x "@D@nb_c" ] && nb_real="@D@nb_c" && break
      done
      if [ -n "@D@nb_real" ]; then
        ln -sf "@D@nb_real" "@D@nb_bin/llvm-strip" 2>/dev/null
        nb_log "NDK 工具链：llvm-strip 已接入真实工具 @D@nb_real"
      else
        cat > "@D@nb_bin/llvm-strip" <<'NBSTRIP'
#!/data/data/com.termux/files/usr/bin/sh
# 星弦 IDE 自建 NDK 工具链：llvm-strip 替身。
# aarch64 设备无法执行官方 NDK 的 x86_64 主机工具；纯 Flutter 工程无需在此剥离符号，
# 故按 AGP 传参（--strip-unneeded -o <out> <in>）把输入落成输出并返回 0。
out=""
in=""
prev=""
for a in "@D@@"; do
  [ "@D@prev" = "-o" ] && out="@D@a"
  case "@D@a" in
    -*) ;;
    *) [ -f "@D@a" ] && in="@D@a" ;;
  esac
  prev="@D@a"
done
if [ -n "@D@out" ]; then
  if [ -n "@D@in" ]; then
    ln -f "@D@in" "@D@out" 2>/dev/null || cp -f "@D@in" "@D@out" 2>/dev/null
  else
    : > "@D@out"
  fi
fi
exit 0
NBSTRIP
        chmod 755 "@D@nb_bin/llvm-strip" 2>/dev/null
        nb_log "NDK 工具链替身已就绪：@D@nb_d（strip 不再依赖 x86_64 官方 NDK 工具）"
      fi
      ln -sf llvm-strip "@D@nb_bin/llvm-objcopy" 2>/dev/null
      ln -sf llvm-strip "@D@nb_bin/llvm-ar" 2>/dev/null
    done
  }
  nb_ndk_toolchain

  # Flutter 新模板通过 flutter.compileSdkVersion / flutter.targetSdkVersion
  # 自动跟随 SDK 默认值；新版 Flutter 可能要求 android-36，而移动端内置 SDK
  # 只预装较低但完整的平台。若默认平台缺失，Gradle 会在编译 Java 前直接失败。
  # 仅改写 Flutter 的默认占位，不覆盖用户已经写死的数字配置。
  nb_select_android_platform() {
    nb_sdk="@D@{ANDROID_HOME:-@D@HOME/android-sdk}"
    nb_best=0
    for nb_p in "@D@nb_sdk"/platforms/android-*; do
      [ -f "@D@nb_p/android.jar" ] || continue
      nb_v=`basename "@D@nb_p" 2>/dev/null | sed 's/^android-//'`
      case "@D@nb_v" in ''|*[!0-9]*) continue ;; esac
      [ "@D@nb_v" -gt "@D@nb_best" ] 2>/dev/null && nb_best="@D@nb_v"
    done
    [ "@D@nb_best" -gt 0 ] 2>/dev/null || return 0
    nb_changed=0
    for nb_f in android/app/build.gradle.kts android/app/build.gradle; do
      [ -f "@D@nb_f" ] || continue
      nb_before=`grep -c 'flutter\\.compileSdkVersion\\|flutter\\.targetSdkVersion' "@D@nb_f" 2>/dev/null || true`
      sed -i \\
        -e "s/flutter\\.compileSdkVersion/@D@nb_best/g" \\
        -e "s/flutter\\.targetSdkVersion/@D@nb_best/g" \\
        "@D@nb_f" 2>/dev/null || continue
      nb_after=`grep -c 'flutter\\.compileSdkVersion\\|flutter\\.targetSdkVersion' "@D@nb_f" 2>/dev/null || true`
      if [ "@D@nb_before" -gt "@D@nb_after" ] 2>/dev/null; then
        nb_changed=1
        nb_log "已改写 Flutter Android 配置：@D@nb_f（剩余默认引用 @D@nb_after）"
      fi
    done
    if [ "@D@nb_changed" -eq 0 ] 2>/dev/null; then
      nb_log "警告：未找到 flutter.compileSdkVersion/targetSdkVersion，未执行平台替换"
    else
      nb_log "Flutter 默认 Android 平台已兼容为 android-@D@nb_best（SDK 中存在完整 android.jar）"
    fi
  }
  nb_select_android_platform
  nb_log "Gradle 提速属性已生效：caching=true、daemon=true（空闲 5 分钟退出）、workers.max=3、parallel=false、堆 2G"

fi

# ── 3b. 不让构建去下载 Gradle 发行包（Flutter 构建失败的真根因） ───────────────────
# 真机取证（同一台设备、同一天）：
#  * android/ 骨架由 `flutter create` 生成，模板**写死**官方源
#      distributionUrl=https\://services.gradle.org/distributions/gradle-<版本>-all.zip
#    → 境内下载约 140s 后断流：
#      Exception in thread "main" java.net.SocketException: Software caused connection abort
#        at org.gradle.wrapper.Download.download(Download.java:44)
#      Gradle task assembleDebug failed with exit code 1
#  * **只换国内镜像仍然不可靠**：Android 工程那次构建日志停在
#      Downloading https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.9-bin.zip
#    之后再无任何输出、任务永不结束（用户看到的就是「构建卡住、一直运行中」）。
#  * 同一工程改走 IDE 内置 gradle（guest 的 usr/bin/gradle，本地已解包）→ BUILD SUCCESSFUL in 2m 5s。
#  结论：真机上**根本不应该去下载发行包**。这里把 android/gradlew 换成转发脚本：
#    有内置 gradle → 直接 exec gradle 参数透传（零下载）；
#    没有内置 gradle → 回退原 wrapper（gradlew.real），并把 distributionUrl 换国内镜像 + 放宽超时。
#  兼容性：镜像只换域名不换版本号；内置 gradle 的版本由 IDE 提供（实测能编 AGP 8.x 工程）。
# ── 3b-0. 把工程声明的 Gradle 发行**包**真正落到本地（不是只换镜像） ─────────────────
# 只换镜像不够：真机日志停在
#   Downloading https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.9-bin.zip
# 之后再无任何输出、任务永不结束 —— 这是 wrapper 自带下载器的问题（`networkTimeout` 默认 10s，
# 本机网络「握手成功但读极慢」会被直接判死/静默重试）。这里改由外壳自己取包：
#   * 先在本地找（应用侧已就绪的 /storage 快照、HOME 缓存），完全没有再用 curl 下载（连接超时 +
#     重试，远比 wrapper 的 10s 判定宽容）；
#   * 落盘目录算法与 Gradle 的 PathAssembler 完全一致（md5(distributionUrl) → base36 25~26 位）；
#   * 解包后写 `<dist>.zip.ok`，于是 `gradlew` 认为「已安装」，构建不再从网络拉 140MB。
# 有 python3 才做（hash/解包都用它）；没有就静默跳过，退回原来的内置 Gradle 方案。
nb_provision_dist() {
  nb_p=android/gradle/wrapper/gradle-wrapper.properties
  [ -f "@D@nb_p" ] || return 0
  nb_du=`grep -m1 '^distributionUrl=' "@D@nb_p" 2>/dev/null | cut -d= -f2- | tr -d '\\'`
  case "@D@nb_du" in http*) ;; *) return 0 ;; esac
  command -v python3 >/dev/null 2>&1 || return 0
  nb_zipname=`printf '%s' "@D@nb_du" | sed 's|.*/||'`
  nb_dist="@D@{nb_zipname%.zip}"
  [ -n "@D@nb_dist" ] || return 0
  nb_gu="@D@{GRADLE_USER_HOME:-@D@HOME/.gradle}"
  nb_hash=`printf '%s' "@D@nb_du" | python3 -c 'import hashlib,sys;n=int.from_bytes(hashlib.md5(sys.stdin.buffer.read()).digest(),"big");print("".join("0123456789abcdefghijklmnopqrstuvwxyz"[(n//(36**i))%36] for i in range(25,-1,-1)).lstrip("0") or "0")' 2>/dev/null`
  [ -n "@D@nb_hash" ] || return 0
  nb_dd="@D@nb_gu/wrapper/dists/@D@nb_dist/@D@nb_hash"
  [ -f "@D@nb_dd/@D@nb_dist.zip.ok" ] && return 0
  mkdir -p "@D@nb_dd" 2>/dev/null || return 0
  if [ ! -s "@D@nb_dd/@D@nb_zipname" ]; then
    nb_src=
    for nb_c in "@D@HOME/.gradle-dist/@D@nb_zipname" "/storage/emulated/0/NebulaForge/toolchain/gradle-dist/@D@nb_zipname"; do
      [ -s "@D@nb_c" ] && nb_src="@D@nb_c" && break
    done
    if [ -n "@D@nb_src" ]; then
      nb_log "复用本地已就绪的 Gradle 发行包 @D@nb_zipname"
      cp "@D@nb_src" "@D@nb_dd/@D@nb_zipname" 2>/dev/null
    elif command -v curl >/dev/null 2>&1; then
      # 官方源在本机网络下会中途断流（SocketException: Software caused connection abort），
      # 先探测可达镜像再下载；**必须带 --max-time**：早先无总超时时网络半死会让这一步无限挂起，
      # 面板上就表现为「一直运行中、没有任何输出」。
      nb_dl="@D@nb_du"
      nb_alt="https://mirrors.aliyun.com/macports/distfiles/gradle/@D@nb_zipname"
      if curl -s -o /dev/null -m 12 -r 0-1000 -w '%{http_code}' "@D@nb_alt" 2>/dev/null | grep -qE '^(200|206)$'; then
        nb_dl="@D@nb_alt"
      fi
      nb_log "下载 Gradle 发行包 @D@nb_zipname（有界超时 180s，替代 wrapper 的 10s 判死）…"
      curl -L --connect-timeout 15 --retry 2 --retry-delay 2 --max-time 180 -o "@D@nb_dd/@D@nb_zipname.part" "@D@nb_dl" 2>/dev/null || true
      [ -s "@D@nb_dd/@D@nb_zipname.part" ] && mv "@D@nb_dd/@D@nb_zipname.part" "@D@nb_dd/@D@nb_zipname"
    fi
  fi
  [ -s "@D@nb_dd/@D@nb_zipname" ] || { nb_log "⚠ 未能取得 Gradle 发行包（离线或镜像不可达），本次回退内置 Gradle"; return 0; }
  # 解包不能只依赖 python3（Termux bootstrap 里可能没有）：依次退到 unzip、JDK 的 jar。
  nb_unzip() {
    if command -v python3 >/dev/null 2>&1; then
      python3 -c 'import zipfile,sys;zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "@D@1" "@D@2" 2>/dev/null && return 0
    fi
    if command -v unzip >/dev/null 2>&1; then
      ( cd "@D@2" && unzip -q -o "@D@1" ) 2>/dev/null && return 0
    fi
    if [ -x "@D@JAVA_HOME/bin/jar" ]; then
      ( cd "@D@2" && "@D@JAVA_HOME/bin/jar" xf "@D@1" ) 2>/dev/null && return 0
    fi
    return 1
  }
  nb_unzip "@D@nb_dd/@D@nb_zipname" "@D@nb_dd" || { nb_log "⚠ 发行包解包失败（python3/unzip/jar 都不可用）"; return 0; }
  chmod -R u+x "@D@nb_dd"/gradle-*/bin 2>/dev/null
  : > "@D@nb_dd/@D@nb_dist.zip.ok"
  nb_log "Gradle 发行版已本地化：@D@nb_zipname（wrapper 不必再联网下载）"
}
nb_fix_wrapper() {
  nb_gw=android/gradlew
  [ -f "@D@nb_gw" ] || return 0
  nb_provision_dist
  [ -f android/gradlew.real ] || cp android/gradlew android/gradlew.real 2>/dev/null || return 0
  cat > "@D@nb_gw" <<'NBFW'
#!/bin/sh
# NebulaForge 生成：优先执行**工程自己的 wrapper** —— 它的 Gradle 版本与工程匹配（模板 AGP 8.x
# 必须配 Gradle 8.x，而 IDE 内置的是 Gradle 9.x，混用会直接报插件不兼容），其发行包已由外壳落到
# 本地，因此也不会联网。只有 wrapper 真的不可用时才退回 IDE 内置 Gradle。
# 原始 wrapper 保留在同目录 gradlew.real。
nb_real="@D@(dirname "@D@0")/gradlew.real"
nb_init_file="@D@HOME/nb-init.gradle"
# 必须用 bash 执行 gradlew.real：Gradle 官方 wrapper 的 shebang 是 #!/usr/bin/env bash，
# 内部使用数组 JVM_OPTS=("$@")、case (5) 等 bash 专有语法。早先用 sh(dash) 执行会立刻失败：
#     gradlew.real: 154: Syntax error: "(" unexpected
#   Gradle task assembleDebug failed with exit code 2   ← 真机实测 1007ms 即失败，
# 看起来像「构建秒退」，实际是外壳用错了 shell。这里优先 bash，bash 缺席才退回 sh。
nb_run_real() {
  # Flutter 会把 -q 放在参数首位；它会吞掉任务阶段和进度，设备端最终只剩一行「开始构建」。
  # 在注入 init 脚本前剥掉这个开关，其他参数保持原样透传。
  if [ "@D@{1:-}" = "-q" ]; then shift; fi
  if command -v bash >/dev/null 2>&1; then
    if [ -f "@D@nb_init_file" ]; then
      exec bash "@D@nb_real" -I "@D@nb_init_file" "@D@@"
    fi
    exec bash "@D@nb_real" "@D@@"
  fi
  if [ -f "@D@nb_init_file" ]; then
    exec sh "@D@nb_real" -I "@D@nb_init_file" "@D@@"
  fi
  exec sh "@D@nb_real" "@D@@"
}
if [ -f "@D@nb_real" ]; then
  chmod 755 "@D@nb_real" 2>/dev/null
  nb_run_real "@D@@"
fi
if command -v gradle >/dev/null 2>&1; then
  if [ -f "@D@nb_init_file" ]; then
    exec gradle -I "@D@nb_init_file" "@D@@"
  fi
  exec gradle "@D@@"
fi
exit 1
NBFW
  chmod 755 "@D@nb_gw" 2>/dev/null
  nb_log "android/gradlew 已接管：优先工程 wrapper（发行包已本地化），必要时回退内置 Gradle"
  # 回退路径（PATH 上没有 gradle）也要尽量可用：镜像 + 放宽超时。
  nb_p=android/gradle/wrapper/gradle-wrapper.properties
  [ -f "@D@nb_p" ] || return 0
  nb_ver=`sed -n 's|.*gradle-\([0-9][0-9.]*\)-\(bin\|all\)\.zip.*|\1|p' "@D@nb_p" 2>/dev/null | head -1`
  nb_kind=`sed -n 's|.*gradle-[0-9][0-9.]*-\(bin\|all\)\.zip.*|\1|p' "@D@nb_p" 2>/dev/null | head -1`
  [ -n "@D@nb_ver" ] || return 0
  [ -n "@D@nb_kind" ] || nb_kind=all
  for nb_m in https://mirrors.aliyun.com/macports/distfiles/gradle https://mirrors.cloud.tencent.com/gradle; do
    nb_u="@D@nb_m/gradle-@D@nb_ver-@D@nb_kind.zip"
    if command -v curl >/dev/null 2>&1; then
      nb_code=`curl -s -o /dev/null -m 15 -r 0-1000 -w '%{http_code}' "@D@nb_u" 2>/dev/null`
      case "@D@nb_code" in 200|206) ;; *) continue ;; esac
    fi
    sed -i "s|^distributionUrl=.*|distributionUrl=@D@nb_u|" "@D@nb_p" 2>/dev/null || continue
    grep -q '^networkTimeout=' "@D@nb_p" || printf 'networkTimeout=120000\n' >> "@D@nb_p"
    nb_log "回退路径：Gradle 发行包改用国内镜像 @D@nb_u"
    return 0
  done
  nb_log "⚠ 国内镜像都不可达，回退路径保持原 distributionUrl"
}
nb_stage wrapper "Gradle 免下载接管"
nb_fix_wrapper

# ── 4. 真正构建（降优先级执行，保证 IDE 界面不被构建饿死） ─────────────────────────
# 构建前卫生处理（与 TaskDefinition 的 gradle 命令保持同一套，改一处要同步改另一处）：
# 上一次半死的构建会留下 aapt2 / Gradle 守护进程，直接导致下一次构建报
# 「AAPT2 ... Daemon startup failed」（= 用户说的「编译一次后再次编译会失败」）。
#  aapt[2]-[0-9] / Gradle[D]aemon：pkill -f 匹配整条命令行，本脚本自己命令行里也含这些
#  字面量，括号拆开可避免自杀；
#  aapt2 守护进程的 socket/lock/log：pkill 在 /proc 被过滤的沙箱里完全失明（实测），
#  所以再清一遍瞬态状态做兜底；顺带清 Gradle daemon 的 registry.bin（锁超时就是它）。
if command -v pkill >/dev/null 2>&1; then
  pkill -f 'aapt[2]-[0-9]' 2>/dev/null
  pkill -f 'Gradle[D]aemon' 2>/dev/null
fi
for nb_t in "@D@{TMPDIR:-/tmp}" "@D@HOME/.android"; do
  [ -d "@D@nb_t" ] && rm -rf "@D@nb_t"/aapt2-* 2>/dev/null
done
for nb_d in "@D@{GRADLE_USER_HOME:-@D@HOME/.gradle}"/daemon/*; do
  [ -d "@D@nb_d" ] && rm -f "@D@nb_d"/registry.bin 2>/dev/null
done
# ── 3d. 出网探测：无网时 Gradle 会**零 CPU 无限等待**依赖解析（已用 /proc 取证） ──────
# 交叉验证：agent 的 proot 能出网（repo1.maven.org 200 / pub.dev 200），而 shell(uid=2000)
# 通道全部 6s 超时；构建进程里 java 的 utime+stime 六秒纹丝不动 = 纯死等，不是慢。
# 所以这里把「无声卡住」换成「快速失败 + 说清缺什么」：
#   1) Gradle HTTP 连接/读超时压到 15s/20s；2) 挂本地离线仓库；3) pub 先走离线缓存。
nb_net_ok=""
if command -v curl >/dev/null 2>&1; then
  nb_stage net "探测外网（决定能否在线解析依赖）"
  for nb_h in https://repo1.maven.org/maven2/ https://maven.aliyun.com/repository/public/ https://pub.dev/; do
    nb_c=`curl -s -o /dev/null -m 4 -w '%{http_code}' "@D@nb_h" 2>/dev/null`
    case "@D@nb_c" in 2*|3*|4*) nb_net_ok=1; break ;; esac
  done
  if [ -n "@D@nb_net_ok" ]; then
    nb_log "✔ 外网可用：Gradle/pub 可在线解析依赖"
  else
    nb_log "⚠ 外网不可用（3 个源 6s 内均无响应）→ 已启用离线优先 + 快速失败："
    nb_log "  · Gradle 连接/读超时 15s/20s：解析不到会立刻报错，不再无限卡住"
    nb_log "  · 已挂本地离线仓库 file:///sdcard/nebula-offline-repo（AGP 8.2.0 / Kotlin 1.9.20 等）"
    nb_log "  · pub 走离线缓存，缺包会写明缺哪个"
  fi
fi
# 真机提速根因（用户实测「构建太慢」）：这里以前在 GRADLE_OPTS 里硬写
#   -Dorg.gradle.daemon=false
# 这是**系统属性**，优先级高于下面刚写进 android/gradle.properties 的
#   org.gradle.daemon=true
# 于是守护进程永远被禁：每次构建都要重付「JVM 启动 + 全量配置」，日志恒定出现
#   To honour the JVM settings for this build a single-use Daemon process will be forked.
#   Daemon will be stopped at the end of the build
# 并且 org.gradle.caching=true 的收益也被每次冷启动吃掉（缓存索引要重新加载）。
# 现在只保留 HTTP 超时（网络黑洞时快速失败），把守护进程的开关交给 gradle.properties，
# 让「连续构建复用同一个 JVM」真正生效。万一守护进程起不来，脚本尾部有自动 --no-daemon 兜底。
export GRADLE_OPTS="@D@{GRADLE_OPTS:-} -Dorg.gradle.internal.http.connectionTimeout=15000 -Dorg.gradle.internal.http.socketTimeout=20000"
nb_init="@D@HOME/nb-init.gradle"
cat > "@D@nb_init" <<'NBGI'
// NebulaForge 生成：给工程补「离线仓库 + 插件门户镜像」，不改动用户工程文件。
//
// 【真机事故 · 必修】早期版本无条件往**所有** settings 的 pluginManagement 注入匿名
// `maven { url ... }`（Gradle 里它的默认名就是 'maven'），并 allprojects 注入工程级仓库。
// Flutter 的 android/settings.gradle.kts 用 includeBuild(<sdk>/packages/flutter_tools/gradle)
// 引入 Flutter 插件，而那个 included build 自己的 settings 声明了
//   repositoriesMode = FAIL_ON_PROJECT_REPOS
// 于是注入立刻被判违规，构建在**配置阶段**就失败（nb-flutter.log 原文）：
//   Error resolving plugin [id: 'dev.flutter.flutter-plugin-loader', version: '1.0.0']
//   > Build was configured to prefer settings repositories over project repositories
//     but repository 'maven' was added by settings file 'settings.gradle.kts'
// 三条护栏：
//   ① 复合构建（included build，gradle.parent 非空）完全不注入 —— 交给它自己的 settings；
//   ② 工程级仓库只在目标 settings 不是严格模式时注入；
//   ③ 仓库显式命名，报错信息里能一眼看出是谁加的。
def nebulaStrict = false
gradle.settingsEvaluated { s ->
  if (s.gradle.parent != null) return
  try {
    def mode = s.dependencyResolutionManagement.repositoriesMode.get()
    nebulaStrict = (mode != org.gradle.api.initialization.resolve.RepositoriesMode.PREFER_PROJECT)
  } catch (Throwable ignored) { }
  if (nebulaStrict) return
  try {
    s.pluginManagement.repositories {
      maven { it.name = 'nebulaOfflineRepo'; it.url = 'file:///sdcard/nebula-offline-repo' }
      maven { it.name = 'nebulaGradlePluginPortal'; it.url = 'https://maven.aliyun.com/repository/gradle-plugin' }
    }
  } catch (Throwable ignored) { }
}
if (gradle.parent == null) {
  allprojects { p ->
    if (!nebulaStrict) {
      try {
        p.repositories {
          maven { it.name = 'nebulaOfflineRepo'; it.url = 'file:///sdcard/nebula-offline-repo' }
        }
      } catch (Throwable ignored) { }
    }
  }
}
// ── 纯 Flutter 工程的 native 处理（2.12.102 真机验证）───────────────────────────
// ① 官方 NDK 不提供 linux-aarch64 主机工具，AGP 的 :app:stripDebugDebugSymbols 在 arm64
//    设备上必然失败（execve 起不来 x86_64 的 llvm-strip）。外壳已在占位 NDK 里补了
//    aarch64 可执行的 llvm-strip 替身，见 nb_ndk_toolchain。
// ② 替身不剥离调试符号，native 库会膨胀（真机实测 APK 666MB）；改用压缩方式打包后回到
//    222MB（真机实测）。「不剥离 + 压缩打包」既稳又省，且全部经 init 脚本注入，
//    不改动用户工程文件。
if (gradle.parent == null) {
  gradle.beforeProject { p ->
    p.plugins.withId('com.android.application') {
      try { p.android.packaging.jniLibs.useLegacyPackaging = true } catch (Throwable t) {
        try { p.android.packagingOptions.jniLibs.useLegacyPackaging = true } catch (Throwable ignored2) { }
      }
    }
  }
}
NBGI
if command -v flutter >/dev/null 2>&1 && [ "@D@{1:-}" = build ]; then
  # 提速：package_config.json 已存在说明依赖已解析过，直接跳过这次探测（原先固定最多等 120s）。
  if [ -f .dart_tool/package_config.json ] && [ .dart_tool/package_config.json -nt pubspec.yaml ]; then
    nb_log "pub 依赖已就绪（跳过离线探测）"
  elif timeout 15 flutter pub get --offline >/dev/null 2>&1; then
    nb_log "pub 依赖已就绪（离线缓存命中）"
  else
    nb_log "⚠ 离线 pub 未命中（缓存不全）：构建带 --no-pub 直跑，缺包会明确报出来"
  fi
fi
nb_stage build "执行 flutter @D@*"
nb_log "构建日志（完整、含 Gradle 报错）会写到：@D@nb_proj/nb-flutter.log"
nb_beat_start
# 真机取证：面板只抓到 flutter 的进度行（`BUILD FAILED in 23s`），而**真正的报错原因**
# （FAILURE: What went wrong / 缺 SDK / 缺依赖…）被刷掉，无法定位。这里把整份输出同时
# 写进工程目录的 nb-flutter.log（shell/uid=2000 也能读，App 之外也能取），失败时再自动
# 摘出关键行贴到面板，省去人工翻日志。
nb_out="@D@nb_proj/nb-flutter.log"
touch "@D@nb_out" 2>/dev/null || nb_out="@D@nb_state/@D@nb_name.flutter.log"
nb_prog_start

# 真正跑构建的那条命令。抽成函数是为了下面的「守护进程兜底」能原样重跑一遍：
# 函数用「调用时传入的参数」，所以这里必须以 `nb_run_build "$@"` 调用，$1/$* 才仍是脚本参数。
nb_run_build() {
  case "@D@{1:-}" in
    build)
      case "$*" in
        *target-platform*|*--split-per-abi*) nb_flutter "$@" --no-pub ;;
        # 提速：debug 默认打包 arm64+armeabi-v7a+x86_64 三套 native 产物，本机只需 arm64。
        *) nb_flutter "$@" --no-pub --target-platform android-arm64 ;;
      esac ;;
    # 设备内没有可连的 adb 目标：`flutter run` 必然报 "No supported devices connected"
    # （真机实测它只会把 Linux desktop 当候选）。改为构建 APK —— 那正是装机要的产物。
    run|install)
      nb_log "ℹ 设备内无法 flutter run（无 adb 目标设备）：自动改为 flutter build apk --debug"
      # 只打 arm64：与上面的 build 分支保持一致。以前这里漏了 --target-platform，
      # 于是「运行/安装」这条路会打包 arm64+armeabi-v7a+x86_64 三套 native 产物，
      # 打包时间与 APK 体积都白白多几倍（真机上就是「运行比构建还慢」）。
      nb_flutter build apk --debug --no-pub --target-platform android-arm64 ;;
    *) nb_flutter "@D@@" ;;
  esac
}

nb_run_build "$@" 2>&1 | tee "@D@nb_out"
nb_ec=@D@{PIPESTATUS[0]}
nb_prog_stop
nb_beat_stop

# ── 守护进程兜底（为什么需要） ────────────────────────────────────────────────
# 为了「第二次构建不再重付 JVM 启动 + 全量配置」，上面会把 org.gradle.daemon 设成 true。
# 但个别受限环境（proot / 无 /proc 权限 / 只读 HOME）守护进程可能起不来，Gradle 会直接报
# "Could not start the Gradle daemon"。为了让「提速」永远不会变成「构建彻底跑不动」，
# 这里识别到该错误就**自动去掉守护进程重跑一次**（慢一点，但一定出产物）。
if [ "@D@nb_ec" != 0 ] &&
   grep -q -E "Could not start the Gradle daemon|Unable to start the daemon|Daemon startup failed|Could not connect to the Gradle daemon" "@D@nb_out" 2>/dev/null; then
  nb_log "⚠ Gradle 守护进程启动失败：自动改为 --no-daemon 重跑一次"
  if grep -q '^org.gradle.daemon=' android/gradle.properties 2>/dev/null; then
    sed -i 's|^org.gradle.daemon=.*|org.gradle.daemon=false|' android/gradle.properties
  else
    printf 'org.gradle.daemon=false\n' >> android/gradle.properties
  fi
  nb_beat_start
  nb_prog_start
  nb_run_build "$@" 2>&1 | tee "@D@nb_out"
  nb_ec=@D@{PIPESTATUS[0]}
  nb_prog_stop
  nb_beat_stop
fi
if [ "@D@nb_ec" != 0 ]; then
  nb_log "✗ 构建失败（exit=@D@nb_ec）。自动摘出失败原因："
  nb_hit=`grep -n -E "FAILURE: Build failed|What went wrong|> Task .*FAILED|Execution failed for task|error:|Error:|Exception|Could not|No such file|not found|SDK location" "@D@nb_out" 2>/dev/null | head -20`
  if [ -n "@D@nb_hit" ]; then printf '%s\n' "@D@nb_hit"; else nb_log "（未匹配到常见关键字，见下方末尾 40 行）"; fi
  # 源码定位行（Dart/Kotlin/Java/C++ 等）：把带 `文件:行:列` 的行单独摘出来，按**原始格式**打印。
  # 面板的问题匹配器（ProblemMatcher）会把这些行解析成「文件 / 行号 / 列号 / 原因」并可点击跳转，
  # 不需要用户自己去几万行日志里找。这里最多给 30 条，覆盖一次构建的编译错误足够。
  nb_locs=`grep -a -E "^e: file://|^w: file://|^[A-Za-z0-9_./-]+\.(dart|kt|kts|java|c|cc|cpp|h|hpp|xml|gradle|json):[0-9]+:[0-9]+|File \"[^\"]+\", line [0-9]+" "@D@nb_out" 2>/dev/null | head -30`
  if [ -n "@D@nb_locs" ]; then
    nb_log "------ 源码定位（文件:行:列，可直接在编辑器中跳转）------"
    printf '%s\n' "@D@nb_locs"
  fi
  nb_log "------ 日志末尾 40 行 ------"
  tail -40 "@D@nb_out" 2>/dev/null
  nb_log "------ 环境自检（早期失败多半在这里）------"
  nb_log "ANDROID_HOME=@D@{ANDROID_HOME:-未设} ANDROID_SDK_ROOT=@D@{ANDROID_SDK_ROOT:-未设}"
  nb_log "JAVA_HOME=@D@{JAVA_HOME:-未设}"
  if command -v gradle >/dev/null 2>&1; then
    nb_log "gradle=@D@(command -v gradle)（IDE 内置；仅当工程 wrapper 不可用时才回退到它）"
  else
    nb_log "⚠ PATH 上没有 gradle：工程 wrapper 不可用时无路可退"
  fi
  # wrapper 与发行包自检：这两条直接决定「能不能不联网构建」。
  # gradlew.real 是 Gradle 官方脚本（shebang=bash，内部有数组语法），必须用 bash 执行 —— 早先用
  # sh 执行会 `Syntax error: "(" unexpected` 并以 exit 2 秒退；发行包没本地化时 wrapper 会自己
  # 去联网拉 136MB（本机网络下会断流/卡死）。
  if [ -f android/gradlew.real ]; then
    if bash -n android/gradlew.real 2>/dev/null; then
      nb_log "android/gradlew.real 语法 OK（以 bash 执行）"
    else
      nb_log "⚠ android/gradlew.real 语法校验失败，wrapper 可能无法执行"
    fi
  else
    nb_log "⚠ android/gradlew.real 不存在，转发脚本将回退内置 Gradle"
  fi
  nb_dok=`ls -d "@D@{GRADLE_USER_HOME:-@D@HOME/.gradle}"/wrapper/dists/*/*/*.zip.ok 2>/dev/null | head -3`
  if [ -n "@D@nb_dok" ]; then
    nb_log "已本地化的 Gradle 发行版："; printf '%s
' "@D@nb_dok"
  else
    nb_log "⚠ wrapper/dists 下没有已安装的发行版 → wrapper 会尝试联网下载 136MB（本机网络下会断流）"
  fi
  if [ -f android/local.properties ]; then
    nb_log "android/local.properties："; sed -n '1,6p' android/local.properties 2>/dev/null
  else
    nb_log "⚠ android/local.properties 不存在"
  fi
  nb_log "完整日志：@D@nb_out"
fi
printf '%s\n' "@D@nb_ec" > "@D@nb_state/@D@nb_name.rc" 2>/dev/null

# ── 5. 产物回传（用户的工程路径不变，产物照旧出现在工程里） ────────────────────────
nb_stage artifact "产物回传"
if [ "@D@nb_proj" != "@D@nb_work" ]; then
  nb_back_ok=1
  if [ -d build/app/outputs ]; then
    mkdir -p "@D@nb_proj/build/app/outputs" 2>/dev/null || nb_back_ok=0
    cp -R build/app/outputs/. "@D@nb_proj/build/app/outputs/" 2>/dev/null || nb_back_ok=0
  fi
  if [ -d build/web ]; then
    mkdir -p "@D@nb_proj/build/web" 2>/dev/null || nb_back_ok=0
    cp -R build/web/. "@D@nb_proj/build/web/" 2>/dev/null || nb_back_ok=0
  fi
  if [ -f pubspec.lock ]; then cp pubspec.lock "@D@nb_proj/" 2>/dev/null || nb_back_ok=0; fi
  if [ -f .dart_tool/package_config.json ]; then
    mkdir -p "@D@nb_proj/.dart_tool" 2>/dev/null || nb_back_ok=0
    cp .dart_tool/package_config.json "@D@nb_proj/.dart_tool/" 2>/dev/null || nb_back_ok=0
  fi
  if [ ! -d "@D@nb_proj/android" ] && [ -d android ]; then cp -R android "@D@nb_proj/" 2>/dev/null; fi
  if [ "@D@nb_ec" = 0 ]; then
    if [ "@D@nb_back_ok" = 1 ]; then
      nb_log "构建完成，产物已回传：@D@nb_proj/build/"
    else
      nb_log "⚠ 产物回传失败（工程目录不可写？），本次产物在：@D@nb_work/build/"
    fi
  fi
fi

nb_stage done "完成（exit=@D@nb_ec）"
exit "@D@nb_ec"
""".trimStart()
}
