package com.nebulaforge.app.build

import android.content.Context
import java.io.File

/**
 * 构建产物的「另一条出口」：只**打包到本地文件路径**。
 *
 * 用户需求原话：「构建编译成功后可以自动安装或者打包到本地文件路径」。
 * 安装（见 [InstallApkDialog]）解决「装到这台机器」，但很多时候用户要的是**拿到文件本身**：
 * 发给同事、塞进聊天、拷到电脑、存档留版本。以前构建完只能安装，装不了就没有别的去处。
 *
 * 这里刻意不做异步/进度条：产物已在本地，拷贝是纯 IO，一两秒的事，失败也只返回 null。
 */
object ApkPackager {

    private const val PREFS = "nebula_install_prompt"
    private const val KEY_AUTO_INSTALL = "auto_install_after_build"

    /** 是否「构建成功后自动安装」（用户在安装弹窗里勾过）。 */
    fun autoInstallEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_INSTALL, false)

    fun setAutoInstall(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_INSTALL, enabled).apply()
    }

    /**
     * 把 APK 复制到本机可访问的目录，返回落盘文件（全失败返回 null）。
     *
     * 目标顺序：
     *  1. `/storage/emulated/0/NebulaForgeIDE/dist/` —— 用户在文件管理器里直接能看到；
     *  2. `<项目>/dist/` —— 跟着项目走，便于随源码一起归档；
     *  3. 应用外部目录 —— 无存储权限时的兜底（用户不好找，但至少不丢）。
     */
    fun export(context: Context, apk: File, projectRoot: String? = null): File? {
        if (!apk.isFile || apk.length() <= 0L) return null
        val targets = ArrayList<File>(3)
        targets.add(File("/storage/emulated/0/NebulaForgeIDE/dist"))
        projectRoot?.takeIf { it.isNotBlank() }?.let { targets.add(File(File(it), "dist")) }
        context.getExternalFilesDir(null)?.let { targets.add(File(it, "dist")) }
        for (dir in targets) {
            val out = File(dir, apk.name)
            val ok = runCatching {
                dir.mkdirs()
                if (out.exists()) out.delete()
                apk.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }.isSuccess && out.length() == apk.length()
            if (ok) return out
        }
        return null
    }
}
