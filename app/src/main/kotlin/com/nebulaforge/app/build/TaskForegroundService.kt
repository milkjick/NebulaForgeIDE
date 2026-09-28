package com.nebulaforge.app.build

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nebulaforge.app.R

/**
 * 「任务运行中」的前台服务：构建 / 运行时把本进程钉在**前台服务优先级**，避免被系统冻结。
 *
 * 真机故障（2.12.21-uifix1 实测）：
 * 用户点「运行」跑一个 Python 文件 → 切到别的应用（比如去 AI 对话里描述问题）→
 * 回到 IDE 看到「Python: 语法检查并运行 · 运行中 1s」，**一个字符的输出都没有，而且永远不动**。
 *
 * 取证结论（不是代码死锁，也不是脚本问题）：
 * - `cat /proc/<pid>/cgroup` → `freezer:/Group_3856003344`：进程整组落在 **freezer 冻结组**；
 * - 6 秒内 CPU 时间增量 = 0 jiffies；
 * - 44 个线程（含 `HeapTaskDaemon`、`gpu-work-server`、`TermSessionWait`）全部 D 态、wchan=0。
 *   GC 线程与 GPU 线程不可能被业务代码卡住 —— 这是**进程被系统整体冻结**的特征。
 * - guest 侧完全正常：同一脚本在本机 proot 里 0.05 秒跑完并正常输出。
 *
 * 也就是说：任务本身没停，是**整个应用被冻住了**。EMUI/Android 的 cached-app 冻结（以及自家的
 * 「应用冻结」省电策略）只作用于 cached 进程；只要持有前台服务，进程 adj 提到前台服务级别，
 * 就既不会被冻结也不会被随手回收。此前全工程没有任何 `startForeground` / `WakeLock` 使用，
 * 所以「退到后台就假死」是必然发生。
 *
 * 用法（见 [WorkspaceTaskRunner.start] / [WorkspaceTaskRunner.finish]）：
 * ```
 * TaskForegroundService.begin(context, "正在构建：assembleDebug")
 * ... 任务结束 ...
 * TaskForegroundService.end(context)
 * ```
 * 所有操作都 best-effort：前台服务起不来（例如后台启动受限）只应退化为「和以前一样」，
 * **绝不能让构建流程因此失败**，所以内部一律 `runCatching`。
 */
class TaskForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: "正在执行任务…"
        // 必须在 5 秒内进入前台，否则系统判定前台服务超时并直接杀进程；这里同步完成，
        // 不做任何可能阻塞的事（通知构建是纯内存操作）。
        runCatching { startForegroundCompat(text) }
        // 不粘性：进程被杀后不要用空 intent 复活一个没有任务的服务。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NebulaForge IDE")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "任务运行",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "构建 / 运行期间的保活通知（避免系统冻结正在跑的任务）"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 屏幕熄灭时也保证 CPU 不被挂起。
     *
     * 保活主因是前台服务（脱离 cached 冻结），唤醒锁是补强：长时间构建时用户往往会熄屏等待，
     * 此时 Doze/App Standby 会进一步限制后台进程。带超时获取，避免异常路径漏放导致耗电。
     */
    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "nebulaforge-tasks"
        private const val NOTIFICATION_ID = 0x4E42
        private const val WAKE_LOCK_TAG = "NebulaForge:task"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        private const val EXTRA_TEXT = "text"

        /** 任务开始：进入前台服务状态。重复调用只更新通知文案。 */
        fun begin(context: Context, text: String) {
            val intent = Intent(context, TaskForegroundService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 任务结束：撤掉前台服务与唤醒锁。 */
        fun end(context: Context) {
            runCatching { context.stopService(Intent(context, TaskForegroundService::class.java)) }
        }
    }
}
