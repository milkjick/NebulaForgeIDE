package com.nebulaforge.core.exec

import java.io.File

/**
 * 「把命令放进 proot guest 执行」的注入点。
 *
 * ## 为什么放在这里
 * `core-exec` 处于依赖链底层（`core-environment` 依赖 `core-exec`），不能反向依赖
 * `TermuxGuest`，否则形成模块环。因此由上层（app 启动时）注入一个包装器，
 * 把 proot/前缀对齐的知识留在 `core-environment`。
 *
 * ## 为什么必须有它
 * 内置 userland 里大量工具**不是 ELF 而是带 Termux shebang 的脚本**
 * （`gradle` / `sdkmanager` / `dart` / `flutter` / `composer` / `mvn` / `clang` 包装脚本…），
 * shebang 写死 `/data/data/com.termux/files/usr/bin/sh`，`bash.bashrc`/`profile` 也按该前缀查找。
 * 在宿主机（App 私有目录前缀）直接执行会得到
 * `bash.bashrc: Permission denied` / `bad interpreter: ... Permission denied`。
 * 结果就是「装好了 gradle 却一律探测失败 / 构建跑不起来」。
 */
object GuestRuntime {

    /** (命令, 环境变量, 工作目录) -> 包装后的整行命令；返回 null 表示 guest 不可用。 */
    @Volatile
    private var wrapper: ((String, Map<String, String>, File) -> String?)? = null

    fun install(fn: (String, Map<String, String>, File) -> String?) {
        wrapper = fn
    }

    /** 返回 null 表示 guest 不可用，调用方应按普通命令执行（保持宿主机语义）。 */
    fun wrap(command: String, env: Map<String, String>, workingDir: File): String? =
        runCatching { wrapper?.invoke(command, env, workingDir) }.getOrNull()
}
