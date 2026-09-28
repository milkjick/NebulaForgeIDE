package com.nebulaforge.app.reverse

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * 轻量 AXML 读取器。
 * 目标不是替代 apktool，而是在不启动外部工具时快速读取 Manifest 的字符串属性。
 */
object AndroidBinaryXmlReader {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 0x00000100

    private data class Attr(val name: String, val value: String)
    private data class Element(val name: String, val attrs: List<Attr>)

    fun readManifestSummary(apk: File): ApkReverseEngine.ManifestSummary {
        val bytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return ApkReverseEngine.ManifestSummary(
                null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
            zip.getInputStream(entry).readBytes()
        }
        return runCatching { parse(bytes) }.getOrElse {
            ApkReverseEngine.ManifestSummary(null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    private fun parse(bytes: ByteArray): ApkReverseEngine.ManifestSummary {
        val pool = StringPool.read(bytes)
        val elements = ArrayList<Element>()
        var offset = 8
        while (offset + 8 <= bytes.size) {
            val type = u16(bytes, offset)
            val headerSize = u16(bytes, offset + 2)
            val chunkSize = u32(bytes, offset + 4)
            if (chunkSize < headerSize || chunkSize <= 0 || offset + chunkSize > bytes.size) break
            if (type == RES_XML_START_ELEMENT_TYPE && headerSize >= 0x24) {
                val nameIndex = u32(bytes, offset + 20)
                val attributeStart = u16(bytes, offset + 24)
                val attributeSize = u16(bytes, offset + 26)
                val attributeCount = u16(bytes, offset + 28)
                val name = pool.get(nameIndex)
                val attrs = ArrayList<Attr>()
                val attrsOffset = offset + attributeStart
                for (i in 0 until attributeCount) {
                    val p = attrsOffset + i * attributeSize
                    if (p + 20 > offset + chunkSize) break
                    val attrName = pool.get(u32(bytes, p + 4))
                    val raw = u32(bytes, p + 8)
                    val dataType = bytes[p + 15].toInt() and 0xff
                    val data = u32(bytes, p + 16)
                    val value = if (raw != 0xffffffffL) {
                        pool.get(raw)
                    } else {
                        decodeTypedValue(dataType, data)
                    }
                    attrs += Attr(attrName, value)
                }
                elements += Element(name, attrs)
            }
            offset += chunkSize.toInt()
        }

        val manifest = elements.firstOrNull { it.name == "manifest" }
        val usesSdk = elements.filter { it.name == "uses-sdk" }.flatMap { it.attrs.map { a -> "${a.name}=${a.value}" } }
        val permissions = elements.filter { it.name == "uses-permission" }.mapNotNull { it.attrs.firstOrNull { a -> a.name == "name" }?.value }
        val activities = elements.filter { it.name == "activity" }.mapNotNull { it.attrs.firstOrNull { a -> a.name == "name" }?.value }
        val services = elements.filter { it.name == "service" }.mapNotNull { it.attrs.firstOrNull { a -> a.name == "name" }?.value }
        val receivers = elements.filter { it.name == "receiver" }.mapNotNull { it.attrs.firstOrNull { a -> a.name == "name" }?.value }
        val providers = elements.filter { it.name == "provider" }.mapNotNull { it.attrs.firstOrNull { a -> a.name == "name" }?.value }

        fun attr(element: Element?, name: String): String? =
            element?.attrs?.firstOrNull { it.name == name }?.value

        return ApkReverseEngine.ManifestSummary(
            packageName = attr(manifest, "package"),
            versionName = attr(manifest, "versionName"),
            versionCode = attr(manifest, "versionCode"),
            usesSdk = usesSdk,
            permissions = permissions.distinct(),
            activities = activities.distinct(),
            services = services.distinct(),
            receivers = receivers.distinct(),
            providers = providers.distinct()
        )
    }

    private fun decodeTypedValue(type: Int, data: Long): String = when (type) {
        0x10 -> data.toString() // TYPE_INT_DEC
        0x11 -> "0x${data.toString(16)}" // TYPE_INT_HEX
        0x12 -> if (data != 0L) "true" else "false" // TYPE_INT_BOOLEAN
        0x01 -> "@0x${data.toString(16)}" // TYPE_REFERENCE
        0x03 -> "@string/0x${data.toString(16)}"
        else -> "0x${data.toString(16)}"
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    private class StringPool private constructor(
        private val bytes: ByteArray,
        private val offsets: IntArray,
        private val stringsStart: Int,
        private val utf8: Boolean
    ) {
        fun get(index: Long): String {
            if (index < 0 || index >= offsets.size) return ""
            val start = stringsStart + offsets[index.toInt()]
            if (start >= bytes.size) return ""
            return if (utf8) readUtf8(start) else readUtf16(start)
        }

        private fun readUtf8(start: Int): String {
            var p = start
            val (_, next) = readLength8(bytes, p)
            p = next
            val (_, charsNext) = readLength8(bytes, p)
            p = charsNext
            var end = p
            while (end < bytes.size && bytes[end].toInt() != 0) end++
            return bytes.copyOfRange(p, end).toString(Charsets.UTF_8)
        }

        private fun readUtf16(start: Int): String {
            var p = start
            val (length, next) = readLength16(bytes, p)
            p = next
            val end = (p + length * 2).coerceAtMost(bytes.size)
            return bytes.copyOfRange(p, end).toString(Charsets.UTF_16LE)
        }

        private fun readLength8(bytes: ByteArray, start: Int): Pair<Int, Int> {
            val first = bytes[start].toInt() and 0xff
            return if ((first and 0x80) != 0) {
                val second = bytes[start + 1].toInt() and 0xff
                (((first and 0x7f) shl 7) or second) to (start + 2)
            } else first to (start + 1)
        }

        private fun readLength16(bytes: ByteArray, start: Int): Pair<Int, Int> {
            val first = u16(bytes, start)
            return if ((first and 0x8000) != 0) {
                val second = u16(bytes, start + 2)
                (((first and 0x7fff) shl 15) or second) to (start + 4)
            } else first to (start + 2)
        }

        companion object {
            fun read(bytes: ByteArray): StringPool {
                require(bytes.size >= 28)
                var offset = 8
                while (offset + 8 <= bytes.size) {
                    val type = u16(bytes, offset)
                    val headerSize = u16(bytes, offset + 2)
                    val chunkSize = u32(bytes, offset + 4).toInt()
                    if (chunkSize <= 0 || offset + chunkSize > bytes.size) break
                    if (type == RES_STRING_POOL_TYPE) {
                        val count = u32(bytes, offset + 8).toInt()
                        val flags = u32(bytes, offset + 16).toInt()
                        val stringsStart = u32(bytes, offset + 20).toInt() + offset
                        val offsetsStart = offset + headerSize
                        val offsets = IntArray(count) { i -> u32(bytes, offsetsStart + i * 4).toInt() }
                        return StringPool(bytes, offsets, stringsStart, (flags and UTF8_FLAG) != 0)
                    }
                    offset += chunkSize
                }
                error("APK Manifest 缺少字符串池")
            }
        }
    }
}
