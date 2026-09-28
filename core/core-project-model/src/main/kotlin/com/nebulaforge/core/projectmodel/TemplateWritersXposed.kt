package com.nebulaforge.core.projectmodel

import java.io.File

/**
 * Xposed / LSPosed Hook 模块骨架。
 *
 * 生成内容：
 *  - 单模块 Android 工程（settings.gradle.kts + build.gradle.kts）
 *  - AndroidManifest.xml 中的 xposedmodule / xposeddescription / xposedminversion 元数据
 *  - assets/xposed_init 声明入口类
 *  - MainHook.kt：IXposedHookLoadPackage 实现，含 before/after 回调骨架
 *
 * xposed api 采用 `compileOnly`，只在编译期参与，不会打进 APK。
 */
internal fun createXposed(root: File, packageName: String) {
    val pkg = packageName.ifBlank { "com.example.nebulaforge" }
    val pkgPath = pkg.replace('.', '/')
    val appName = root.name.ifBlank { "XposedModule" }

    writeTemplateFile(root, "settings.gradle.kts", """import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://api.xposed.info/") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
        google()
        mavenCentral()
        maven { url = uri("https://api.xposed.info/") }
    }
}
rootProject.name = "$appName"
""")
    writeTemplateFile(root, "build.gradle.kts", """plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
""")
    writeTemplateFile(root, "gradle.properties", "org.gradle.jvmargs=-Xmx1536m\nandroid.useAndroidX=true\nkotlin.code.style=official\n")
    writeTemplateFile(root, "app/build.gradle.kts", """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "$pkg"
    compileSdk = 34

    defaultConfig {
        applicationId = "$pkg"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Xposed API 仅编译期可见，不打包进 APK；运行期由宿主框架提供。
    compileOnly("de.robv.android.xposed:api:82")
}
""")
    writeTemplateFile(root, "app/src/main/AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:versionCode="1"
    android:versionName="1.0">

    <application
        android:hasCode="true"
        android:label="$appName">

        <!-- Xposed 模块声明：被框架识别为模块而非普通应用 -->
        <meta-data
            android:name="xposedmodule"
            android:value="true" />
        <meta-data
            android:name="xposeddescription"
            android:value="由星弦 IDE 生成的 Xposed 模块骨架" />
        <meta-data
            android:name="xposedminversion"
            android:value="82" />
        <!-- 若使用 LSPosed，可额外声明作用域（可选） -->
        <meta-data
            android:name="xposedscope"
            android:resource="@array/xposed_scope" />
    </application>
</manifest>
""")
    writeTemplateFile(root, "app/src/main/res/values/arrays.xml", """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 默认作用域：请替换为你要 Hook 的目标包名 -->
    <string-array name="xposed_scope">
        <item>com.example.target</item>
    </string-array>
</resources>
""")
    writeTemplateFile(root, "app/src/main/assets/xposed_init", "$pkg.MainHook\n")
    writeTemplateFile(root, "app/src/main/java/$pkgPath/MainHook.kt", """package $pkg

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed 模块入口：框架按 assets/xposed_init 中声明的类名加载本类。
 *
 * 修改 [TARGET_PACKAGE] / [TARGET_CLASS] / [TARGET_METHOD] 即可接入你的目标应用。
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedBridge.log("NebulaForge Xposed: hooking ${'$'}{lpparam.packageName}")

        runCatching {
            XposedHelpers.findAndHookMethod(
                TARGET_CLASS,
                lpparam.classLoader,
                TARGET_METHOD,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("NebulaForge Xposed: before ${'$'}TARGET_METHOD")
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 示例：篡改返回值，实际逻辑请按需替换。
                        param.result = "hooked by NebulaForge"
                    }
                }
            )
        }.onFailure { e ->
            XposedBridge.log("NebulaForge Xposed: hook failed - ${'$'}{e.message}")
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.target"
        const val TARGET_CLASS = "com.example.target.MainActivity"
        const val TARGET_METHOD = "getGreeting"
    }
}
""")
    writeTemplateFile(root, "README.md", """# $appName

由星弦 IDE 生成的 **Xposed / LSPosed 模块**骨架。

## 结构

| 文件 | 作用 |
| --- | --- |
| `app/src/main/assets/xposed_init` | 声明 Hook 入口类，框架据此加载 |
| `app/src/main/AndroidManifest.xml` | `xposedmodule` / `xposeddescription` / `xposedscope` 元数据 |
| `app/src/main/java/.../MainHook.kt` | `IXposedHookLoadPackage` 实现，含 before/after 回调 |

## 接入步骤

1. 打开 `MainHook.kt`，把 `TARGET_PACKAGE` / `TARGET_CLASS` / `TARGET_METHOD` 改成你的目标。
2. 打开 `app/src/main/res/values/arrays.xml`，把 `xposed_scope` 改成目标包名。
3. 构建：`gradle :app:assembleDebug`；安装后在 LSPosed 中启用并勾选作用域，重启目标应用。
""")
}
