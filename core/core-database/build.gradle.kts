plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.nebulaforge.core.database"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:core-environment"))
    implementation(project(":core:core-project-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
