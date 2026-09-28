package com.nebulaforge.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 本地视频合成器（AI 工作台「生成视频」的兜底路径）。
 *
 * 为什么不用 ffmpeg：设备侧工具链里没有 ffmpeg，而 Android 自带**硬编 H.264**（MediaCodec）
 * 与 MP4 封装（MediaMuxer）。两者是系统能力，零下载、零可执行依赖，短片段（几秒~几十秒）
 * 完全够用；在线视频服务接入后这里作为「没网/没额度/被限流」时的回退。
 *
 * 支持两类输入：
 * - **图生视频**：给一串图片，加 Ken Burns 运镜（缓慢推近）+ 交叉淡入淡出 → MP4
 * - **文生视频**：给一段脚本/描述，按行切成字幕卡（渐变底 + 淡入淡出 + 轻微缩放）→ MP4
 *
 * 线程模型：必须在**非主线程**调用；[onProgress] 会在调用线程回调（0f~1f）。
 */
object VideoComposer {

    /** 合成参数。宽高必须是偶数（H.264 要求），构造时会自动向下取偶。 */
    data class Spec(
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 24,
        val secondsPerImage: Double = 3.0,
        val crossfadeSeconds: Double = 0.4,
        val kenBurns: Boolean = true,
        val bitRate: Int = 4_000_000,
        val title: String? = null
    ) {
        val w: Int get() = width - width % 2
        val h: Int get() = height - height % 2
        val durationPerImageMs: Int get() = (secondsPerImage.coerceIn(0.5, 30.0) * 1000).toInt()
        val frameIntervalMs: Int get() = (1000.0 / fps.coerceIn(10, 60)).toInt()
    }

    /** 合成结果。 */
    data class Result(val file: File, val frames: Int, val durationMs: Int, val codec: String)

    // ------------------------------------------------------------------ 对外

    /** 图生视频。图片按给定顺序播放，尺寸不一致时自动 cover 裁切。 */
    fun imagesToMp4(
        images: List<File>,
        output: File,
        spec: Spec,
        onProgress: (Float) -> Unit = {}
    ): Result {
        require(images.isNotEmpty()) { "没有可用的图片" }
        val frames = mutableListOf<(Int, Int) -> Bitmap>()
        val decoded = images.map { decode(it, spec) }
        decoded.forEachIndexed { index, bitmap ->
            val next = decoded.getOrNull(index + 1)
            frames += { frameInSource, framesPerSource -> renderImageFrame(bitmap, next, frameInSource, framesPerSource, spec) }
        }
        return encode(frames, images.size * spec.durationPerImageMs, output, spec, onProgress)
    }

    /** 文生视频（本地兜底）：脚本按行切成字幕卡。空行会被跳过，最多取 24 张卡。 */
    fun textToMp4(
        script: String,
        output: File,
        spec: Spec,
        onProgress: (Float) -> Unit = {}
    ): Result {
        val cards = script.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(24)
        require(cards.isNotEmpty()) { "没有可用的文案（按行切成字幕卡，至少写一行）" }
        val frames = cards.mapIndexed { index, text -> ({ _: Int, _: Int -> renderTextCard(text, index, cards.size, spec) }) }
        return encode(frames, cards.size * spec.durationPerImageMs, output, spec, onProgress)
    }

    // ------------------------------------------------------------------ 渲染

    private fun decode(file: File, spec: Spec): Bitmap {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var scale = 1
        while (bounds.outWidth / scale > spec.w * 2 && bounds.outHeight / scale > spec.h * 2) scale *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = max(1, scale) }
        return android.graphics.BitmapFactory.decodeFile(file.path, options)
            ?: throw IllegalStateException("图片解码失败：${file.name}")
    }

    /**
     * 单帧：cover 裁切 + Ken Burns 推近；到了本张图的最后 [Spec.crossfadeSeconds]，
     * 把**下一张**按递增不透明度叠上去（比淡到黑更顺，也不会闪白）。
     */
    private fun renderImageFrame(
        current: Bitmap,
        next: Bitmap?,
        frameInSource: Int,
        framesPerSource: Int,
        spec: Spec
    ): Bitmap {
        val progress = if (framesPerSource <= 1) 0f else frameInSource.toFloat() / (framesPerSource - 1)
        val zoom = if (spec.kenBurns) 1.0f + 0.07f * progress else 1.0f
        val out = Bitmap.createBitmap(spec.w, spec.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawCover(canvas, current, spec, zoom, 255)
        val fadeFrames = (spec.crossfadeSeconds * 1000 / spec.frameIntervalMs).toInt()
        if (next != null && fadeFrames > 0) {
            val remain = framesPerSource - 1 - frameInSource
            if (remain < fadeFrames) {
                val alpha = (255 * (fadeFrames - remain) / fadeFrames).coerceIn(0, 255)
                drawCover(canvas, next, spec, zoom = 1.0f, alpha = alpha)
            }
        }
        drawTitle(canvas, spec)
        return out
    }

    /** cover 裁切 + 缩放（[zoom] > 1 表示推近），画到已有 [Canvas] 上。 */
    private fun drawCover(canvas: Canvas, source: Bitmap, spec: Spec, zoom: Float, alpha: Int) {
        val scale = max(spec.w.toFloat() / source.width, spec.h.toFloat() / source.height) * zoom
        val dw = source.width * scale
        val dh = source.height * scale
        val left = (spec.w - dw) / 2f
        val top = (spec.h - dh) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha }
        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt()),
            paint
        )
    }

    /** 文字卡片：渐变底 + 居中大字 + 淡入淡出 + 轻微缩放。 */
    private fun renderTextCard(text: String, index: Int, total: Int, spec: Spec): Bitmap {
        val out = Bitmap.createBitmap(spec.w, spec.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val hue = (index * 360f / max(1, total)) % 360f
        val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.30f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.65f, 0.14f))
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, spec.h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })

        val textPaint = TextPaint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textSize = spec.w / 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, (spec.w * 0.82f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        val zoom = 1f + 0.02f * (index % 3)
        canvas.save()
        canvas.scale(zoom, zoom, spec.w / 2f, spec.h / 2f)
        canvas.translate((spec.w - layout.width) / 2f, (spec.h - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        drawTitle(canvas, spec)
        return out
    }

    private fun drawTitle(canvas: Canvas, spec: Spec) {
        val title = spec.title?.takeIf { it.isNotBlank() } ?: return
        val paint = TextPaint().apply {
            color = Color.argb(200, 255, 255, 255)
            isAntiAlias = true
            textSize = spec.w / 32f
            typeface = Typeface.DEFAULT
        }
        canvas.drawText(title, spec.w * 0.04f, spec.h * 0.94f, paint)
    }

    // ------------------------------------------------------------------ 编码

    /**
     * 逐帧渲染 → YUV420 → H.264 → MP4。
     *
     * YUV 走 `COLOR_FormatYUV420Flexible` + [MediaCodec.getInputImage]，让系统按自己真实的
     * 平面布局（不同厂商 stride 不同）接收数据，避免手写 NV12 在华为/高通上错色。
     */
    private fun encode(
        frameRenderers: List<(Int, Int) -> Bitmap>,
        totalDurationMs: Int,
        output: File,
        spec: Spec,
        onProgress: (Float) -> Unit
    ): Result {
        val codecName = pickCodec()
        val format = MediaFormat.createVideoFormat(codecName, spec.w, spec.h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        var frameIndex = 0
        val totalFrames = max(1, totalDurationMs / spec.frameIntervalMs)
        val info = MediaCodec.BufferInfo()

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // 每张图占多少帧（图生视频/文生视频都一样：均匀分配）
            val framesPerSource = max(1, totalFrames / max(1, frameRenderers.size))
            while (frameIndex < totalFrames) {
                val sourceIndex = min(frameRenderers.size - 1, frameIndex / max(1, framesPerSource))
                val frameInSource = frameIndex - sourceIndex * framesPerSource
                val bitmap = frameRenderers[sourceIndex](frameInSource, framesPerSource)

                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val image = runCatching { codec.getInputImage(inputIndex) }.getOrNull()
                    if (image != null) {
                        fillYuv(image, bitmap)
                        val ptsUs = frameIndex.toLong() * spec.frameIntervalMs * 1000
                        codec.queueInputBuffer(inputIndex, 0, imageSize(image), ptsUs, 0)
                    } else {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        fillYuvBuffer(buffer, bitmap, spec)
                        val ptsUs = frameIndex.toLong() * spec.frameIntervalMs * 1000
                        codec.queueInputBuffer(inputIndex, 0, spec.w * spec.h * 3 / 2, ptsUs, 0)
                    }
                }
                bitmap.recycle()

                // 取编码输出
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        continue
                    }
                    if (outIndex < 0) continue
                    val encoded = codec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encoded, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }

                frameIndex++
                if (frameIndex % 5 == 0 || frameIndex == totalFrames) {
                    onProgress(frameIndex.toFloat() / totalFrames)
                }
            }

            // 冲刷
            val inputIndex = codec.dequeueInputBuffer(50_000)
            if (inputIndex >= 0) codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var draining = true
            while (draining) {
                val outIndex = codec.dequeueOutputBuffer(info, 20_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIndex)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, encoded, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) draining = false
                    }
                }
            }
            onProgress(1f)
            return Result(output, totalFrames, totalDurationMs, codecName)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** 优先硬编 H.264；华为等设备偶发 avc 编码器抽风时退 HEVC。 */
    private fun pickCodec(): String {
        for (mime in listOf("video/avc", "video/hevc")) {
            val codec = runCatching { MediaCodec.createEncoderByType(mime) }.getOrNull() ?: continue
            val caps = runCatching { codec.codecInfo.getCapabilitiesForType(mime) }.getOrNull()
            codec.release()
            if (caps == null) continue
            val supported = caps.colorFormats.any {
                it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible ||
                    it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
            if (supported) return mime
        }
        return "video/avc"
    }

    private fun imageSize(image: android.media.Image): Int {
        var size = 0
        image.planes.forEach { size += it.buffer.capacity() }
        return size
    }

    /** Bitmap → YUV420（按 [MediaCodec.getInputImage] 给出的真实平面布局写入）。 */
    private fun fillYuv(image: android.media.Image, bitmap: Bitmap) {
        val width = image.width
        val height = image.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (row in 0 until height) {
            for (col in 0 until width) {
                val c = argb[row * width + col]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(row * yRowStride + col, y.coerceIn(0, 255).toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val index = (row / 2) * uvRowStride + (col / 2) * uvPixelStride
                    uBuffer.put(index, u.coerceIn(0, 255).toByte())
                    vBuffer.put(index, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    /** 少数设备拿不到 Image 时的兜底：写 NV12（半平面）到普通 ByteBuffer。 */
    private fun fillYuvBuffer(buffer: ByteBuffer, bitmap: Bitmap, spec: Spec) {
        val width = spec.w
        val height = spec.h
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        buffer.clear()
        val ySize = width * height
        for (i in 0 until ySize) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            buffer.put((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte())
        }
        var i = 0
        while (i < ySize) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            buffer.put(u)
            buffer.put(v)
            i += 2
        }
        buffer.position(0)
    }
}
