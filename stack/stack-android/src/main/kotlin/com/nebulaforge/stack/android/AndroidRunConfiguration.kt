package com.nebulaforge.stack.android

import com.nebulaforge.core.projectmodel.RunConfiguration
import java.io.File

class AndroidRunConfiguration : RunConfiguration {
    override val id = "android-debug"
    override fun command(projectRoot: File, mode: String): String = when (mode) {
        "debug", "run" -> "assembleDebug"
        else -> throw IllegalArgumentException("Android 当前运行引擎仅支持 debug/run")
    }
    override fun supports(mode: String) = mode == "debug" || mode == "run"
}
