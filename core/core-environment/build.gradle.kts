plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nebulaforge.core.environment"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Way-B：bootstrap 真实可执行性探测必须走 NativePty（与终端/构建/LSP 同一用户态）。
    implementation(project(":core:core-exec"))
    implementation(project(":core:core-pty"))
    testImplementation("junit:junit:4.13.2")
}
