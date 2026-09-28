pluginManagement {
    repositories {
        maven { url = uri("/opt/maven-repo") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("/opt/maven-repo") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "NebulaForgeIDE"
include(":app")
include(":core:core-environment")
include(":core:core-project-model")
include(":core:core-ui-theme")
include(":core:core-session")
include(":core:core-pty")
include(":stack:stack-android")
include(":stack:stack-flutter")
include(":stack:stack-web")

include(":core:core-terminal")
include(":core:core-toolchain")
include(":core:core-exec")
include(":core:core-gradle-bridge")
include(":core:core-agent")

include(":core:core-mcp")
include(":core:core-plugin-runtime")
include(":stack:stack-cpp")
include(":stack:stack-server")

include(":core:core-editor-kernel")
include(":core:core-database")
include(":core:core-device")
include(":core:core-toolwindow")
include(":core:core-ai-provider")
include(":plugins-sdk")