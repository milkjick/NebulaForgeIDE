package com.nebulaforge.app.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 插件图标的真实加载：内存 LRU → 磁盘缓存 → 网络，三层都命中不了才回退首字母占位。
 *
 * 不用图片库是刻意的：市场入口要能在插件面板里独立工作，避免为一个图标再引入网络图片依赖。
 * SVG 直接放弃解码（Android 的 BitmapFactory 不支持 SVG），回退占位而不是显示空白方块。
 */
object PluginIconLoader {

    private const val MAX_CACHE_BYTES = 6 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
    private const val TARGET_PX = 128
    private const val ASSET_PREFIX = "asset://"

    private val memory = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        memory.get(url)?.let { return it }
        val bitmap = if (url.startsWith(ASSET_PREFIX)) {
            runCatching {
                context.assets.open(url.removePrefix(ASSET_PREFIX)).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        } else {
            val file = diskFile(context, url)
            if (!file.isFile) return null
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        } ?: return null
        memory.put(url, bitmap)
        return bitmap
    }

    suspend fun load(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        cached(context, url)?.let { return@withContext it }
        // 内置资源已经在上一步尝试过；SVG 交给占位图，不做假解码。
        if (url.startsWith(ASSET_PREFIX) || url.endsWith(".svg", true)) return@withContext null
        val bitmap = runCatching { download(context, url) }.getOrNull()
        bitmap?.also { memory.put(url, it) }
    }

    private fun download(context: Context, url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "NebulaForge-IDE/2")
        }
        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                val sink = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    if (sink.size() > MAX_DOWNLOAD_BYTES) return null
                }
                sink.toByteArray()
            }
            val bitmap = decodeScaled(bytes) ?: return null
            runCatching {
                val file = diskFile(context, url)
                file.parentFile?.mkdirs()
                file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }
            bitmap
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun diskFile(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "plugin-icons"), "$hash.img")
    }
}

/** 插件图标：真实网络图标，失败时用首字母方块占位（保证列表视觉一致）。 */
@Composable
fun PluginIcon(iconUrl: String?, label: String, size: Dp = 44.dp) {
    val context = LocalContext.current
    var bitmap by remember(iconUrl) { mutableStateOf(PluginIconLoader.cached(context, iconUrl)) }
    LaunchedEffect(iconUrl) {
        if (bitmap == null && !iconUrl.isNullOrBlank()) {
            bitmap = PluginIconLoader.load(context, iconUrl)
        }
    }
    val shape = RoundedCornerShape(size / 4)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape)
            )
        } else {
            Text(
                label.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
