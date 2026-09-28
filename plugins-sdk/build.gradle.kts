plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.nebulaforge.plugins.sdk"; compileSdk = 34; defaultConfig { minSdk = 24 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" } }
dependencies { implementation(project(":core:core-project-model")); implementation(project(":core:core-mcp")); implementation(project(":core:core-plugin-runtime")) }
