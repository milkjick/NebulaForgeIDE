package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File

/**
 * 公共存储里的原生 Gradle 工程：镜像到应用私有目录（f2fs，可执行）再执行原命令。
 *
 * 真机取证（2.12.98，勿回退以下两条）：
 * 1) Gradle 打印 `BUILD SUCCESSFUL` 之后，旧脚本还要把 `build/` 与 **`.gradle/`** 整体回拷到
 *    /storage（FUSE）。`.gradle` 是数百个碎文件，逐个写 FUSE 要几分钟 —— 实测「Gradle 结束 →
 *    外壳退出」空档 375s / 398s，面板就停在 `BUILD SUCCESSFUL` 一动不动，用户看到的是
 *    「编译构建一直卡在这里」。现在收尾**只回传模块产物**（顶层 build 与各模块下的 outputs 目录），
 *    并跳过 `.gradle` 缓存。
 * 2) 退出码在 Gradle 结束的**第一时间**写进哨兵文件（和 Flutter 外壳同一套 state 目录下的
 *    `.rc` 文件），面板据文件判结束，不再依赖收尾同步是否跑完，也不会被屏幕抽水卡住。
 */
object NativeBuildShim {
    const val NAME = "nb-gradle"

    fun ensure(context: Context): String? {
        val bin = File(Environment.binDir(context))
        if (!bin.isDirectory && !bin.mkdirs()) return null
        val target = File(bin, NAME)
        val text = script(shellFor(context))
        if (target.isFile && runCatching { target.readText() }.getOrNull() == text) {
            runCatching { target.setExecutable(true, false) }
            return null
        }
        return runCatching {
            target.writeText(text)
            target.setExecutable(true, false)
            "已安装原生 Gradle 私有镜像外壳（$NAME）"
        }.getOrNull()
    }

    private fun shellFor(context: Context): String =
        listOf("bash", "sh", "dash")
            .map { File(Environment.usrRoot(context), "bin/$it") }
            .firstOrNull { it.isFile }?.absolutePath ?: "/bin/sh"

    private fun script(shell: String): String = """
#!$shell
set -u
src="${'$'}{NEBULA_SOURCE_ROOT:-${'$'}PWD}"
case "${'$'}src" in
  /storage/*|/sdcard/*|/mnt/sdcard/*|/mnt/media_rw/*) ;;
  *) exec sh -c "${'$'}*" -- ;;
esac
name="${'$'}{NEBULA_PROJECT_NAME:-$(basename "${'$'}src")}"
root="${'$'}{NEBULA_NATIVE_ROOT:-${'$'}HOME/.nebulaforge/native}"
work="${'$'}root/${'$'}name"
state="${'$'}HOME/.nebulaforge/build"
mkdir -p "${'$'}state" 2>/dev/null
rm -f "${'$'}state/${'$'}name.rc" 2>/dev/null
nb_stage() { printf '%s\n' "${'$'}1" > "${'$'}state/${'$'}name.stage" 2>/dev/null || true; }
mkdir -p "${'$'}work" || { printf '[nebula] 无法创建原生工程镜像：%s\n' "${'$'}work"; exit 125; }
nb_stage "sync 原生工程镜像"
printf '[nebula] 阶段：sync 原生工程镜像\n'
# Keep the mirror usable after an interrupted build and avoid copying generated caches.
if command -v tar >/dev/null 2>&1; then
  (cd "${'$'}src" && tar -cf - --exclude=./build --exclude=./.gradle --exclude=./.git --exclude=./.idea .) |
    (cd "${'$'}work" && tar -xf -) || { printf '[nebula] 原生工程镜像失败：%s\n' "${'$'}src"; exit 125; }
else
  printf '[nebula] 缺少 tar，无法镜像原生工程\n'; exit 125
fi
cd "${'$'}work" || exit 125
nb_stage "build 原生 Gradle 构建"
printf '[nebula] 阶段：build 原生 Gradle 构建\n'
sh -c "${'$'}1" --
rc=${'$'}?
# 退出码先落盘：面板据此立即结束，收尾同步再慢也不会再表现成「卡住」。
printf '%s\n' "${'$'}rc" > "${'$'}state/${'$'}name.rc" 2>/dev/null || true
nb_stage "sync 原生构建产物"
printf '[nebula] 阶段：sync 原生构建产物\n'
# 只回传模块产物；**不回拷 .gradle**（数百碎文件写 FUSE 要几分钟，且对用户毫无价值）。
nb_copied=0
nb_sync_rc=0
for nb_out in build/outputs */build/outputs; do
  [ -d "${'$'}nb_out" ] || continue
  nb_dir=${'$'}{nb_out%/outputs}
  mkdir -p "${'$'}src/${'$'}nb_dir" 2>/dev/null
  rm -rf "${'$'}src/${'$'}nb_out" 2>/dev/null
  if cp -R "${'$'}nb_out" "${'$'}src/${'$'}nb_dir/" 2>/dev/null; then
    nb_copied=1
  else
    nb_sync_rc=1
  fi
done
if [ "${'$'}nb_copied" = 0 ] && [ -d build ]; then
  rm -rf "${'$'}src/build" 2>/dev/null
  if cp -R build "${'$'}src/" 2>/dev/null; then nb_copied=1; else nb_sync_rc=1; fi
fi
if [ "${'$'}nb_copied" = 0 ]; then
  printf '[nebula] 本次任务没有可回传的构建产物目录（可能是清理/检查类任务）\n'
elif [ "${'$'}nb_sync_rc" != 0 ]; then
  printf '[nebula] ⚠ 构建产物回传不完整，私有镜像仍在：%s\n' "${'$'}work"
else
  printf '[nebula] 构建产物已回传：%s\n' "${'$'}src"
fi
exit "${'$'}rc"
"""
}
