package com.nebulaforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.nebulaforge.app.navigation.NebulaForgeApp
import com.nebulaforge.core.device.ShizukuAccess
import com.nebulaforge.core.theme.NebulaForgeTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 项目本体存放在公共存储 /storage/emulated/0/NebulaForgeProjects，
        // 启动时先确保具备读写能力，否则项目创建/打开会静默回退到应用私有目录。
        requestProjectStorageAccess()
        setContent {
            // 断点自适应的核心依据：对应开发方案第十章 WindowSizeClass 断点表
            val windowSizeClass = calculateWindowSizeClass(this)
            NebulaForgeTheme {
                NebulaForgeApp(
                    windowSizeClass = windowSizeClass,
                    // 直达路由：`adb shell am start -n com.nebulaforge.app/.MainActivity -e route workspace`
                    // 便于开发调试与自动化冒烟（无需手工点进工作区）。仅接受白名单路由。
                    initialRoute = intent?.getStringExtra(EXTRA_ROUTE)
                )
            }
        }
    }

    /**
     * Shizuku 授权结果回调。
     *
     * Shizuku 的权限弹窗复用系统 requestPermissions 流程，requestCode 是
     * [ShizukuAccess.REQUEST_CODE]；把结果转交给 [ShizukuAccess] 后再刷新应用内的状态流，
     * 否则「设备权限」页会一直停在「等待授权」。
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val grantResult = grantResults.firstOrNull() ?: PackageManager.PERMISSION_DENIED
        ShizukuAccess.onPermissionResult(requestCode, grantResult, this)
        (application as? NebulaForgeApplication)?.refreshDeviceAccess()
    }

    /**
     * 申请公共存储访问权限。
     *
     * - Android 11（API 30）及以上：公共目录需要「所有文件访问」权限，跳系统设置页授予；
     *   用户拒绝时不做强制，[com.nebulaforge.core.environment.Environment.projectsDir] 会自动回退私有目录。
     * - Android 10 及以下：走传统运行时权限（READ/WRITE_EXTERNAL_STORAGE）。
     */
    private fun requestProjectStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val granted = runCatching {
                android.os.Environment.isExternalStorageManager()
            }.getOrDefault(false)
            if (!granted) {
                // 优先跳到本应用专属的「所有文件访问」设置页，失败再退到通用页。
                val appPage = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                val opened = runCatching { startActivity(appPage); true }.getOrDefault(false)
                if (!opened) {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            }
        } else {
            val needed = listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), REQUEST_CODE_STORAGE)
            }
        }
    }

    private companion object {
        const val REQUEST_CODE_STORAGE = 1001

        /** 直达路由 extra（调试/自动化冒烟用），见 [NebulaForgeApp] 的 initialRoute。 */
        const val EXTRA_ROUTE = "route"
    }
}
