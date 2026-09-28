package com.nebulaforge.stack.flutter

import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.projectmodel.RunConfiguration
import java.io.File

class FlutterRunConfiguration : RunConfiguration {
    override val id = "flutter-run"
    override fun command(projectRoot: File, mode: String): String = when (mode) {
        "web" -> "flutter run -d web-server --web-port=8080"
        "profile" -> "flutter run --profile"
        "release" -> "flutter run --release"
        else -> "flutter run"
    }
    override fun supports(mode: String) = mode in setOf("run", "web", "profile", "release")
}
