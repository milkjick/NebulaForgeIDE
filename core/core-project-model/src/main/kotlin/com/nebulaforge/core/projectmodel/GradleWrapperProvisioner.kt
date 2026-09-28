package com.nebulaforge.core.projectmodel

import com.nebulaforge.core.exec.TermuxCommandExecutor
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Generates and verifies a real Gradle Wrapper using the embedded Termux PTY.
 * Way-B invariant: wrapper generation must not escape to Android ProcessBuilder.
 */
object GradleWrapperProvisioner {
    data class Result(val success: Boolean, val output: String)

    fun provision(projectRoot: File, gradleExecutable: File, env: Map<String, String>): Result {
        require(projectRoot.isDirectory) { "Project root does not exist: ${projectRoot.absolutePath}" }
        require(gradleExecutable.isFile && gradleExecutable.canExecute()) { "Gradle executable is not available" }
        val shell = env["SHELL"]?.takeIf { File(it).isFile && File(it).canExecute() } ?: "/system/bin/sh"
        val executor = TermuxCommandExecutor(shell, guestAware = true)
        val output = StringBuilder()
        var exit = 125
        runBlocking {
            executor.execute(
                command = "${quote(gradleExecutable.absolutePath)} :wrapper --distribution-type bin",
                workingDir = projectRoot,
                env = env
            ).collect { event ->
                when (event) {
                    is TermuxCommandExecutor.Event.Line -> output.appendLine(event.text)
                    is TermuxCommandExecutor.Event.Finished -> exit = event.exitCode
                }
            }
        }
        if (exit != 0) return Result(false, output.toString())
        val jar = File(projectRoot, "gradle/wrapper/gradle-wrapper.jar")
        val props = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
        val script = File(projectRoot, "gradlew")
        return if (jar.isFile && props.isFile && script.isFile) Result(true, output.toString())
        else Result(false, output.toString() + "\nGradle :wrapper finished without complete wrapper files")
    }

    private fun quote(v: String) = "'" + v.replace("'", "'\\''") + "'"
}
