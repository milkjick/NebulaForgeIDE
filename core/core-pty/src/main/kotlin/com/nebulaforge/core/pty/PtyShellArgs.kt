package com.nebulaforge.core.pty

import java.io.File

/**
 * PTY 里喂给 shell 的启动参数。
 *
 * ## 为什么需要它
 * embedded 前缀里的 bash 是 **Termux 补丁版**：交互式启动会 source
 * 编译期前缀 `/data/data/com.termux/files/usr/etc/bash.bashrc`，登录式启动会 source
 * `.../etc/profile`。而宿主命名空间下 `/data/data/com.termux` 是别的应用的私有目录
 * （不可穿越 → **EACCES，而不是 ENOENT**），于是每次执行都会先吐一行：
 * ```
 * bash: /data/data/com.termux/files/usr/etc/bash.bashrc: Permission denied
 * ```
 * 这行会被上层当成命令输出展示给用户（终端、构建日志、AI 工具调用结果里都刺眼），
 * 并且让「环境变量到底从哪来」变得不可预测（rc 读到了 / 读不到，行为不同）。
 *
 * 实测（设备上 app uid 复现）：
 * - `bash -i -c ...`            → 报 bash.bashrc Permission denied
 * - `bash -lc ...`              → 报 profile Permission denied
 * - `bash -c ...`               → 干净
 * - `bash --norc --noprofile -i`→ 干净，且 PATH 仍按传入环境生效
 *
 * ## 注意
 * GNU 长选项必须排在短选项/`-c` **之前**，否则 bash 直接报 `--: invalid option` 并退出
 * （`-i -c 'x' --norc` 是错的，`--norc --noprofile -i` 才对）。
 *
 * 非 bash 的外层 shell（例如回退用的 `/system/bin/sh` = mksh）不认识长选项，
 * 因此只在确认为 bash 时才追加，解析失败一律按非 bash 处理 —— 宁可少加参数，
 * 也不能让外层 shell 起不来。
 */
object PtyShellArgs {

    fun forShell(shell: String): Array<String> =
        if (isBash(shell)) arrayOf(shell, "--norc", "--noprofile", "-i") else arrayOf(shell, "-i")

    /** 仅供 ProcessBuilder 之类直接构造 argv 的调用方使用（放在命令与 `-c` 之前）。 */
    fun bashLongOptions(shell: String): List<String> =
        if (isBash(shell)) listOf("--norc", "--noprofile") else emptyList()

    /** 以 canonical 名称判定：前缀里的 `sh` 常常是指向 bash 的软链，必须解析后再比。 */
    fun isBash(shell: String): Boolean = runCatching {
        File(shell).canonicalFile.name == "bash"
    }.getOrDefault(false)
}
