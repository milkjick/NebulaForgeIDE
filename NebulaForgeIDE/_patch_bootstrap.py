import io, sys

p = "core/core-environment/src/main/kotlin/com/nebulaforge/core/environment/BootstrapInstaller.kt"
src = io.open(p, encoding="utf-8").read()
orig = src

# ---------- ① imports ----------
imp_anchor = "import kotlinx.coroutines.flow.flowOn\n"
assert imp_anchor in src, "flowOn import anchor missing"
if "kotlinx.coroutines.channels.Channel" not in src:
    src = src.replace(
        imp_anchor,
        imp_anchor
        + "import kotlinx.coroutines.channels.Channel\n"
        + "import kotlinx.coroutines.flow.channelFlow\n"
        + "import kotlinx.coroutines.launch\n",
        1,
    )

# ---------- ② InstallProgress: 新增 Verifying ----------
old_seal = '        object LinkingSymlinks : InstallProgress()\n'
assert old_seal in src
src = src.replace(
    old_seal,
    old_seal
    + '        /** 内置用户态已落盘，正在做 SHA-256 / 架构 / 可执行性校验 */\n'
    + '        data class Verifying(val step: String) : InstallProgress()\n',
    1,
)

# ---------- ③ 替换 install() ----------
start_marker = "    fun install(useMirror: Boolean = true): Flow<InstallProgress> = flow {"
end_marker = "    }.flowOn(Dispatchers.IO)\n"
i = src.index(start_marker)
j = src.index(end_marker, i) + len(end_marker)

new_install = '''    fun install(useMirror: Boolean = true): Flow<InstallProgress> = channelFlow {
        if (Environment.isBootstrapInstalled(context)) {
            send(InstallProgress.Completed)
            return@channelFlow
        }
        // 说明：这里不再复制一份「下载 -> 解压 -> 建链接」的实现，而是统一委托 installWithStatus，
        // 保证设置页（Flow 入口）与 BootstrapRuntime（挂起入口）行为完全一致——
        // 两条路径逻辑分叉正是此前「内置用户态装好了但 self-test 仍失败」的成因之一。
        val channel = Channel<InstallProgress>(Channel.UNLIMITED)
        val worker = launch(Dispatchers.IO) {
            try {
                installWithStatus { phase, done, total, message ->
                    val mapped = when (phase) {
                        Phase.DOWNLOAD -> InstallProgress.Downloading(done, total)
                        Phase.EXTRACT -> InstallProgress.Extracting(message, done.toInt(), total.toInt())
                        Phase.VERIFY -> InstallProgress.Verifying(message)
                    }
                    channel.trySend(mapped)
                }.fold(
                    onSuccess = { channel.trySend(InstallProgress.Completed) },
                    onFailure = { e ->
                        channel.trySend(InstallProgress.Failed(e.message ?: "初始化内置运行环境失败", e))
                    }
                )
            } finally {
                channel.close()
            }
        }
        try {
            for (progress in channel) send(progress)
        } finally {
            worker.cancel()
        }
    }
'''
src = src[:i] + new_install + src[j:]

# ---------- ④ 插入辅助方法（放在 sha256 之前） ----------
helper_anchor = "    private fun sha256(file: File): String {"
assert helper_anchor in src
helpers = '''    /** APK assets 内是否真的存在该 bootstrap（元数据与打包不同步时不能盲信元数据）。 */
    private fun embeddedAssetExists(asset: EmbeddedBootstrap.Asset): Boolean = runCatching {
        context.assets.open(asset.assetPath).use { true }
    }.getOrDefault(false)

    /** 把 APK 内置的 bootstrap 拷贝到缓存目录（边拷边上报进度，供 UI 显示释放百分比）。 */
    private fun copyAssetToCache(
        asset: EmbeddedBootstrap.Asset,
        dest: File,
        onProgress: (Long) -> Unit
    ) {
        dest.parentFile?.mkdirs()
        context.assets.open(asset.assetPath).use { input ->
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    total += n
                    onProgress(total)
                }
                out.flush()
            }
        }
    }

    /** 读取 zip 内某条目的前 count 字节（用于嗅探 ELF 头，不必解整包）。 */
    private fun zipEntryHead(zipFile: File, entry: String, count: Int): ByteArray? =
        java.util.zip.ZipFile(zipFile).use { zip ->
            val e = zip.getEntry(entry) ?: return null
            zip.getInputStream(e).use { ins ->
                val buf = ByteArray(count)
                var off = 0
                while (off < count) {
                    val n = ins.read(buf, off, count - off)
                    if (n <= 0) break
                    off += n
                }
                if (off <= 0) null else buf.copyOf(off)
            }
        }

    /**
     * 校验内置 bootstrap 的真实 CPU 架构：直接解析包内主 shell 的 ELF 头 e_machine。
     * 防的是「把 x86_64 资产装到 arm64 设备上」这类错误——那种情况下解压会成功，
     * 但所有二进制 exec 时报 "Exec format error"，而单纯的「文件存在性检查」无法提前发现。
     */
    private fun verifyEmbeddedArchitecture(zipFile: File, architecture: String): Boolean {
        val expected = EmbeddedBootstrap.expectedElfMachine(architecture) ?: return false
        val head = listOf("bin/bash", "bin/dash", "bin/sh", "usr/bin/bash", "usr/bin/dash")
            .firstNotNullOfOrNull { zipEntryHead(zipFile, it, 20) } ?: return false
        if (head.size < 20) return false
        // ELF magic: 0x7F 'E' 'L' 'F'
        if (head[0] != 0x7F.toByte() || head[1] != 0x45.toByte() ||
            head[2] != 0x4C.toByte() || head[3] != 0x46.toByte()
        ) return false
        if (head[4] != 2.toByte()) return false // EI_CLASS=2 → ELF64
        val machine = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        return machine == expected
    }

'''
src = src.replace(helper_anchor, helpers + helper_anchor, 1)

assert src != orig
io.open(p, "w", encoding="utf-8").write(src)
print("patched bytes:", len(orig), "->", len(src))
