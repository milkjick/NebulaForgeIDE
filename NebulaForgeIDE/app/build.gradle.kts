plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nebulaforge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nebulaforge.app"
        minSdk = 24
        // Android 10 (API 29) 起，targetSdk >= 29 的应用被 SELinux 禁止执行应用私有目录
        // 中的可执行文件（W^X）。嵌入式 Termux userland 的 sh/pkg 正好落在 filesDir/usr 下，
        // 因此 targetSdk 35 时 bootstrap 自检必然失败，导致所有工具链组件（pkg install）连带失败。
        // 参照 Termux 官方做法保持 targetSdk = 28，使内嵌 runtime 可真正执行。
        targetSdk = 28
        versionCode = 216
        versionName = "2.12.112"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // APK 内置 Termux 用户态（assets/bootstrap/*.zip，约 31MB）本身已是 deflate 压缩包，
        // 再让 aapt2 压缩一遍既浪费时间又几乎没有收益；以 store 方式入库还能保留 AssetFileDescriptor
        // （openFd）拷贝能力，后续若要做零拷贝解压也不用重新打包。
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:core-ai-provider"))
    implementation(project(":core:core-toolwindow"))
    implementation(project(":core:core-editor-kernel"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-device"))
    implementation(project(":core:core-environment"))
    implementation(project(":core:core-project-model"))
    implementation(project(":core:core-ui-theme"))
    implementation(project(":core:core-session"))
    implementation(project(":core:core-exec"))
    implementation(project(":core:core-agent"))
    implementation(project(":core:core-mcp"))
    implementation(project(":core:core-plugin-runtime"))
    implementation(project(":core:core-pty"))
    implementation(project(":core:core-terminal"))
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    implementation("com.termux.termux-app:terminal-emulator:0.118.0")
    implementation(project(":core:core-toolchain"))
    implementation(project(":stack:stack-android"))
    implementation(project(":stack:stack-flutter"))
    implementation(project(":stack:stack-web"))
    implementation(project(":stack:stack-cpp"))
    implementation(project(":stack:stack-server"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.webkit:webkit:1.12.1")

    // Real code editor core: Sora Editor (editor + TreeSitter integration).
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation("io.github.Rosemoe.sora-editor:language-java:0.23.6")
    implementation("io.github.Rosemoe.sora-editor:language-treesitter:0.23.6")
    implementation("com.itsaky.androidide.treesitter:android-tree-sitter:4.3.1")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-java:4.3.1")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-kotlin:4.3.1")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-xml:4.3.1")


    debugImplementation("androidx.compose.ui:ui-tooling")
}
