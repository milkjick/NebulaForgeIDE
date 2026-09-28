package com.nebulaforge.app.build

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nebulaforge.app.device.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「构建成功 → 选择安装方式」的**持久化待办**。
 *
 * ## 为什么必须落盘（真机取证）
 *
 * 用户报「Flutter 构建成功后安装弹窗没有弹出」。取证链条：
 *  1. `files/logs/run.log`：`FINISH exit=0` + `ARTIFACT …/build/app/outputs/apk/debug/app-debug.apk`
 *     —— 构建本身是成功的，IDE 也确实定位到了产物（旧实现在这一步之后就该弹窗）；
 *  2. 同一时刻系统日志：构建结束后约 24s，
 *     `ActivityTaskManager: Force removing ActivityRecord{… com.nebulaforge.app/.MainActivity} app died`，
 *     而 crash buffer 里**没有** Java 异常 —— 进程是被系统（华为 iAware 清理 / 后台回收）杀掉的。
 *
 * 结论：Flutter 构建要跑 3~5 分钟，用户必然切去别的 App；系统常在构建刚结束那一刻回收本应用。
 * 弹窗状态只活在 Compose 内存里，进程一死就彻底丢失 —— 用户回到应用只看到重启后的界面，
 * 永远等不到那个「安装到本机」的入口（旧实现还把它绑在底部「构建」工具窗是否活着上，双重脆弱）。
 *
 * 所以：**产物落盘 → 待办落盘 → 谁在显示都行**。由挂在 NavHost 之上的 [InstallPromptHost]
 * 负责展示，用户选完安装方式或点「稍后」才清除；进程被杀后重新打开应用也会补弹一次。
 */
class InstallPromptStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 记录「有一个新 APK 等着安装」。
     *
     * 同一个产物（路径 + mtime 作 key）在用户点过「稍后」后不再重复弹；换一次构建（新文件/新 mtime）
     * 自然就是新的一次机会，不受影响。
     */
    fun offer(apk: File, projectRoot: String? = null, sessionId: String? = null) {
        runCatching {
            if (!apk.isFile) return
            val key = keyOf(apk)
            if (prefs.getString(KEY_DISMISSED, null) == key) return
            prefs.edit()
                .putString(KEY_PATH, apk.absolutePath)
                .putLong(KEY_MTIME, apk.lastModified())
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putString(KEY_ROOT, projectRoot)
                .putString(KEY_SESSION, sessionId)
                .apply()
        }
    }

    /**
     * 当前待安装的产物；没有则返回 null。
     *
     * 文件已被删除/被移动/超过 [TTL_MS] 时顺手清掉待办 —— 否则用户会在下次启动时看到一个
     * 指向不存在文件的弹窗（真机上用户会自己删构建产物或整个工程目录）。
     */
    fun pending(): File? {
        val path = prefs.getString(KEY_PATH, null) ?: return null
        val apk = File(path)
        val mtime = prefs.getLong(KEY_MTIME, 0L)
        val at = prefs.getLong(KEY_TIME, 0L)
        val fresh = at > 0L && System.currentTimeMillis() - at < TTL_MS
        if (mtime <= 0L || !apk.isFile || apk.lastModified() != mtime || !fresh) {
            clear()
            return null
        }
        return apk
    }

    /** 造出这个待办归属的工程（可能为 null）：弹窗上「切到该工程」用得上。 */
    fun pendingProjectRoot(): String? = prefs.getString(KEY_ROOT, null)

    /** 用户已处理（装了 / 点了稍后）：清待办，并记住不再重复弹这同一个产物。 */
    fun clear() {
        val path = prefs.getString(KEY_PATH, null)
        val mtime = prefs.getLong(KEY_MTIME, 0L)
        prefs.edit()
            .remove(KEY_PATH)
            .remove(KEY_MTIME)
            .remove(KEY_TIME)
            .remove(KEY_ROOT)
            .remove(KEY_SESSION)
            .apply()
        if (path != null && mtime > 0L) {
            prefs.edit().putString(KEY_DISMISSED, keyOf(File(path), mtime)).apply()
        }
    }

    private fun keyOf(f: File, mtime: Long = f.lastModified()): String = "${f.absolutePath}@$mtime"

    private companion object {
        const val PREFS = "nebula_install_prompt"
        const val KEY_PATH = "apk_path"
        const val KEY_MTIME = "apk_mtime"
        const val KEY_TIME = "offered_at"
        const val KEY_ROOT = "project_root"
        const val KEY_SESSION = "session_id"
        const val KEY_DISMISSED = "dismissed_key"

        /** 待办有效期：一天。更久以前的构建产物再弹出来只会让人困惑。 */
        const val TTL_MS = 24 * 3600_000L
    }
}

/**
 * 「安装到本机」弹窗的**唯一宿主**：挂在 NavHost 之上（见 `NebulaForgeApp.NebulaNavHost`）。
 *
 * 放在这里的三个理由：
 *  1. 不依赖任何具体页面/工具窗 —— 用户在设置页、AI 工作台、编辑器里都能看到它
 *     （旧实现把它塞在底部「构建」工具窗的 Compose 里，面板没组出来就永远不会弹）；
 *  2. 构建结束 → 待办落盘（见 [WorkspaceTaskRunner] 的产物识别段），回到前台自动补弹，
 *     进程被系统杀掉后重启也能补弹；
 *  3. 全程只弹**一个**：面板不再自己渲染弹窗，避免「面板一个 + 全局一个」叠两层。
 */
@Composable
fun InstallPromptHost() {
    val context = LocalContext.current
    val store = remember(context) { InstallPromptStore(context.applicationContext) }
    var pending by remember { mutableStateOf(store.pending()) }

    // 轮询而不是事件订阅：构建可能发生在进程生命周期的任何时刻（甚至在进程被杀之前），
    // 而「回到前台」这件事没有可靠的回调（Compose 页面重建时机也不确定）。
    // 读的是内存缓存的 SharedPreferences + 一次 stat，开销可忽略；stat 放到 IO 线程上，
    // 因为产物在 /storage（FUSE）上时元数据读取偶尔会慢。
    LaunchedEffect(Unit) {
        var autoTriedFor: String? = null
        while (true) {
            val now = withContext(Dispatchers.IO) { store.pending() }
            if (now?.absolutePath != pending?.absolutePath) pending = now
            // 「构建成功后自动安装」：用户勾过开关且本机有静默通道时，不再弹窗，直接装。
            // 失败（没有 Shizuku/Root、安装被系统拦）就把待办留着，弹窗照样出现，让用户改走系统安装器
            // —— 自动失败绝不能变成「什么都不做」。
            if (now != null && ApkPackager.autoInstallEnabled(context) && autoTriedFor != now.absolutePath) {
                autoTriedFor = now.absolutePath
                val outcome = withContext(Dispatchers.IO) {
                    ApkInstaller.installSilently(context.applicationContext, now)
                }
                if (outcome.ok) {
                    store.clear()
                    pending = null
                } else {
                    pending = now
                }
            }
            delay(1_200)
        }
    }

    pending?.let { apk ->
        InstallApkDialog(
            apk = apk,
            onDismiss = {
                store.clear()
                pending = null
            }
        )
    }
}
