package com.nebulaforge.app.reverse

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * APK 逆向工作区。
 *
 * 第 9.1 节的实际落地：
 * - APK 本体只读分析；
 * - ZIP/Dex/资源清单检查；
 * - apktool/jadx 通过内置运行时中的 JDK 执行；
 * - 所有输出进入独立工作目录，避免污染原 APK；
 * - 回编译先使用 apktool b，签名阶段由 Android build-tools 的 apksigner 完成。
 *
 * 工具目录（运行时取 `Environment.homeRoot(context)`，界面上直接显示真实绝对路径，
 * 不要把 `$HOME` 这种字面量端给用户 —— 他们照着 `$HOME` 找不到文件、也没法手动放 jar）：
 * `<homeRoot>/.nebulaforge/tools/apktool.jar`
 * `<homeRoot>/.nebulaforge/tools/jadx-cli.jar`
 */
class ApkReverseEngine(private val context: Context) {

    data class ToolStatus(
        val javaHome: String?,
        val apktoolJar: File?,
        val jadxJar: File?,
        val apksigner: File?,
        /**
         * 工具实际落盘的目录。
         *
         * 界面以前把「工具目录」写成字面量 `$HOME/.nebulaforge/tools/`，真机上就真的显示 `$HOME`，
         * 用户照着这个路径既找不到文件、也无法把手动下载的 jar 放进去。这里带上真实路径。
         */
        val toolsDir: File
    ) {
        val readyForAnalysis: Boolean get() = javaHome != null
        val readyForDecompile: Boolean get() = readyForAnalysis && apktoolJar?.isFile == true
        val readyForJava: Boolean get() = readyForAnalysis && jadxJar?.isFile == true
        val readyForBuild: Boolean get() = readyForDecompile && apksigner?.isFile == true
    }

    data class ApkEntry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val crc: Long,
        val isDirectory: Boolean
    )

    data class ApkReport(
        val apkPath: String,
        val sha256: String,
        val size: Long,
        val entries: List<ApkEntry>,
        val dexCount: Int,
        val hasResources: Boolean,
        val hasManifest: Boolean,
        val manifestSummary: ManifestSummary,
        val dexFiles: List<DexInspector.DexSummary>,
        val nativeLibraries: List<String>,
        val assets: List<String>,
        val resources: List<String>,
        val urls: List<String>,
        val signingCertificates: List<DexInspector.CertificateSummary>
    )

    data class ManifestSummary(
        val packageName: String?,
        val versionName: String?,
        val versionCode: String?,
        val usesSdk: List<String>,
        val permissions: List<String>,
        val activities: List<String>,
        val services: List<String>,
        val receivers: List<String>,
        val providers: List<String>
    )

    data class SigningOptions(
        val keystore: File,
        val storePassword: String,
        val alias: String,
        val keyPassword: String = storePassword
    )

    sealed class Event {
        data class Output(val text: String, val error: Boolean = false) : Event()
        data class Finished(val exitCode: Int) : Event()
    }

    fun toolStatus(): ToolStatus {
        val javaHome = Environment.resolveJdkHome(context, 17)
        val tools = File(Environment.homeRoot(context), ".nebulaforge/tools")
        return ToolStatus(
            javaHome = javaHome,
            apktoolJar = File(tools, "apktool.jar").takeIf { it.isFile },
            jadxJar = File(tools, "jadx-cli.jar").takeIf { it.isFile },
            apksigner = Environment.latestBuildToolsExecutable(context, "apksigner").takeIf { it?.isFile == true },
            toolsDir = tools
        )
    }

    fun inspect(apk: File): ApkReport {
        require(apk.isFile && apk.extension.equals("apk", true)) { "请选择有效的 APK 文件" }
        val entries = ZipFile(apk).use { zip ->
            zip.entries().asSequence().map {
                ApkEntry(it.name, it.size, it.compressedSize, it.crc, it.isDirectory)
            }.toList()
        }
        val manifest = AndroidBinaryXmlReader.readManifestSummary(apk)
        return ApkReport(
            apkPath = apk.absolutePath,
            sha256 = sha256(apk),
            size = apk.length(),
            entries = entries,
            dexCount = entries.count { it.name.matches(Regex("""classes\d*\.dex""")) },
            hasResources = entries.any { it.name == "resources.arsc" },
            hasManifest = entries.any { it.name == "AndroidManifest.xml" },
            manifestSummary = manifest,
            dexFiles = DexInspector.inspectDexFiles(apk),
            nativeLibraries = entries.map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so", true) },
            assets = entries.map { it.name }.filter { it.startsWith("assets/") && !it.endsWith("/") }.take(1000),
            resources = entries.map { it.name }.filter { it.startsWith("res/") && !it.endsWith("/") }.take(1000),
            urls = DexInspector.extractUrls(apk).take(500),
            signingCertificates = DexInspector.readV1Certificates(apk)
        )
    }

    fun workspaceFor(apk: File): File {
        val safeName = apk.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(
            Environment.homeRoot(context),
            ".nebulaforge/reverse/apk/$safeName"
        )
    }

    fun copyIntoWorkspace(apk: File): File {
        val workspace = workspaceFor(apk)
        workspace.mkdirs()
        val target = File(workspace, "source.apk")
        FileInputStream(apk).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    fun decompileResources(apk: File): Flow<Event> {
        val status = toolStatus()
        require(status.readyForDecompile) {
            "缺少 APK 反编译工具或 JDK 17，请在工具链中准备 apktool.jar 与 JDK 17"
        }
        val workspace = workspaceFor(apk)
        workspace.mkdirs()
        val output = File(workspace, "apktool")
        if (output.exists()) output.deleteRecursively()
        val env = Environment.buildTerminalEnv(context)
        val command = "java -jar ${quote(status.apktoolJar!!.absolutePath)} d -f -o ${quote(output.absolutePath)} ${quote(apk.absolutePath)}"
        return TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
            .execute(command, workspace, env)
            .map { event ->
                when (event) {
                    is TermuxCommandExecutor.Event.Line -> Event.Output(event.text)
                    is TermuxCommandExecutor.Event.Finished -> Event.Finished(event.exitCode)
                }
            }
    }

    fun generateJava(apk: File): Flow<Event> {
        val status = toolStatus()
        require(status.readyForJava) {
            "缺少 JADX CLI 或 JDK 17，请在工具链中准备 jadx-cli.jar 与 JDK 17"
        }
        val workspace = workspaceFor(apk)
        workspace.mkdirs()
        val output = File(workspace, "jadx")
        if (output.exists()) output.deleteRecursively()
        val env = Environment.buildTerminalEnv(context)
        // 必须显式指定 CLI 主类：`java -jar` 走的是 jar 里的 Main-Class，jadx 官方发行包中
        // `jadx-gui-<v>-all.jar` 的 Main-Class 是 jadx.gui.JadxGUI，在设备上会直接进 GUI 初始化，
        // 无字体/无显示时抛 `ExceptionInInitializerError: Fontconfig head is null`，
        // 表现为「JADX 已安装却无法反编译」。CLI 主类在同一份 all-jar 里同样存在，故用 -cp 显式选择，
        // 并加 headless 开关避免任何 AWT 初始化。
        val command = "java -Djava.awt.headless=true -Duser.language=en -cp ${quote(status.jadxJar!!.absolutePath)} " +
            "jadx.cli.JadxCLI -d ${quote(output.absolutePath)} ${quote(apk.absolutePath)}"
        return TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
            .execute(command, workspace, env)
            .map { event ->
                when (event) {
                    is TermuxCommandExecutor.Event.Line -> Event.Output(event.text)
                    is TermuxCommandExecutor.Event.Finished -> Event.Finished(event.exitCode)
                }
            }
    }

    fun rebuildAndSign(decodedDirectory: File, outputApk: File): Flow<Event> {
        val status = toolStatus()
        require(status.readyForBuild) {
            "缺少 apktool、JDK 17 或 build-tools/apksigner"
        }
        val workspace = decodedDirectory.parentFile ?: error("无效的反编译目录")
        val unsigned = File(workspace, "rebuilt-unsigned.apk")
        val keystore = File(workspace, "debug.keystore")
        val signed = outputApk.absoluteFile
        val env = Environment.buildTerminalEnv(context)
        val keytool = File(status.javaHome!!, "bin/keytool")
        val commands = buildString {
            append("rm -f ").append(quote(unsigned.absolutePath)).append("; ")
            append("java -jar ").append(quote(status.apktoolJar!!.absolutePath))
                .append(" b ").append(quote(decodedDirectory.absolutePath))
                .append(" -o ").append(quote(unsigned.absolutePath)).append("; ")
            append("code=\$?; if [ \$code -ne 0 ]; then exit \$code; fi; ")
            append("if [ ! -f ").append(quote(keystore.absolutePath)).append(" ]; then ")
            append(quote(keytool.absolutePath)).append(" -genkeypair -v -keystore ").append(quote(keystore.absolutePath))
                .append(" -storepass android -alias androiddebugkey -keypass android ")
                .append("-dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000; fi; ")
            append(quote(status.apksigner!!.absolutePath)).append(" sign --ks ").append(quote(keystore.absolutePath))
                .append(" --ks-pass pass:android --key-pass pass:android --out ").append(quote(signed.absolutePath))
                .append(" ").append(quote(unsigned.absolutePath))
        }
        return TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
            .execute(commands, workspace, env)
            .map { event ->
                when (event) {
                    is TermuxCommandExecutor.Event.Line -> Event.Output(event.text)
                    is TermuxCommandExecutor.Event.Finished -> Event.Finished(event.exitCode)
                }
            }
    }

    fun rebuildAndSign(decodedDirectory: File, outputApk: File, signing: SigningOptions): Flow<Event> {
        val status = toolStatus()
        require(status.readyForDecompile && status.apksigner?.isFile == true) { "缺少 apktool、JDK 17 或 apksigner" }
        require(signing.keystore.isFile) { "用户签名 keystore 不存在" }
        require(signing.alias.isNotBlank()) { "签名别名不能为空" }
        val workspace = decodedDirectory.parentFile ?: error("无效的反编译目录")
        val unsigned = File(workspace, "rebuilt-user-unsigned.apk")
        val env = Environment.buildTerminalEnv(context)
        val command = buildString {
            append("rm -f ").append(quote(unsigned.absolutePath)).append("; ")
            append("java -jar ").append(quote(status.apktoolJar!!.absolutePath)).append(" b ").append(quote(decodedDirectory.absolutePath)).append(" -o ").append(quote(unsigned.absolutePath)).append("; ")
            append("code=\$?; if [ \$code -ne 0 ]; then exit \$code; fi; ")
            append(quote(status.apksigner!!.absolutePath)).append(" sign --ks ").append(quote(signing.keystore.absolutePath))
                .append(" --ks-pass pass:").append(quoteShellArg(signing.storePassword))
                .append(" --ks-key-alias ").append(quote(signing.alias))
                .append(" --key-pass pass:").append(quoteShellArg(signing.keyPassword))
                .append(" --out ").append(quote(outputApk.absolutePath)).append(" ").append(quote(unsigned.absolutePath))
        }
        return TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true).execute(command, workspace, env).map { event ->
            when (event) {
                is TermuxCommandExecutor.Event.Line -> Event.Output(event.text)
                is TermuxCommandExecutor.Event.Finished -> Event.Finished(event.exitCode)
            }
        }
    }

    /**
     * 返回 apktool 工作区中可直接修改的文本文件。
     * 只允许访问当前 decodedDirectory 内部，避免路径穿越。
     */
    fun listEditableFiles(decodedDirectory: File): List<File> {
        require(decodedDirectory.isDirectory) { "反编译工作区不存在" }
        return decodedDirectory.walkTopDown()
            .filter { it.isFile }
            .filter { isEditableText(it) }
            .filter { isInside(decodedDirectory, it) }
            .sortedBy { it.relativeTo(decodedDirectory).path }
            .take(2000)
            .toList()
    }

    fun readEditableFile(decodedDirectory: File, file: File): String {
        require(isInside(decodedDirectory, file)) { "禁止访问工作区之外的文件" }
        require(file.isFile) { "文件不存在" }
        require(isEditableText(file)) { "当前文件不是支持直接编辑的文本文件" }
        require(file.length() <= MAX_EDITABLE_FILE_BYTES) { "文件过大，请使用外部工具处理" }
        return file.readText(Charsets.UTF_8)
    }

    fun writeEditableFile(decodedDirectory: File, file: File, content: String) {
        require(isInside(decodedDirectory, file)) { "禁止写入工作区之外的文件" }
        require(file.isFile) { "文件不存在" }
        require(isEditableText(file)) { "当前文件不是支持直接编辑的文本文件" }
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_EDITABLE_FILE_BYTES) { "文件过大，拒绝写入" }
        val backup = File(file.parentFile, file.name + ".nebulaforge.bak")
        if (!backup.exists()) file.copyTo(backup, overwrite = false)
        val temp = File(file.parentFile, file.name + ".nebulaforge.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            require(temp.renameTo(file)) { "无法提交修改" }
        }
    }

    fun restoreEditableBackup(file: File) {
        val backup = File(file.parentFile, file.name + ".nebulaforge.bak")
        require(backup.isFile) { "没有可恢复的备份" }
        backup.copyTo(file, overwrite = true)
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        return childPath.startsWith(rootPath)
    }

    private fun isEditableText(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".xml") || name.endsWith(".smali") || name.endsWith(".json") ||
            name.endsWith(".txt") || name.endsWith(".properties") || name.endsWith(".yml") ||
            name.endsWith(".yaml") || name.endsWith(".js") || name.endsWith(".kt") ||
            name.endsWith(".java") || name.endsWith(".html") || name.endsWith(".css") ||
            name.endsWith(".gradle") || name.endsWith(".kts") || name.endsWith(".pro") ||
            name == "AndroidManifest.xml" || name == "apktool.yml"
    }

    companion object {
        private const val MAX_EDITABLE_FILE_BYTES = 2L * 1024L * 1024L
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun quoteShellArg(value: String): String = value.replace("'", "'\\''")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
