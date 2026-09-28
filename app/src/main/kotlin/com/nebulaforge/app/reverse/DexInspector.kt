package com.nebulaforge.app.reverse

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/** APK DEX/签名静态检查器：不执行 APK，仅读取结构化元数据。 */
object DexInspector {
    data class DexSummary(
        val fileName: String,
        val version: String,
        val fileSize: Long,
        val checksum: String,
        val sha1: String,
        val stringCount: Int,
        val typeCount: Int,
        val methodCount: Int,
        val classCount: Int
    )

    data class CertificateSummary(val entry: String, val sha256: String, val subject: String)

    fun inspectDexFiles(apk: File): List<DexSummary> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.mapNotNull { entry ->
            runCatching { parse(entry.name, zip.getInputStream(entry).readBytes()) }.getOrNull()
        }.toList()
    }

    fun extractUrls(apk: File): List<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.flatMap { entry ->
            val bytes = zip.getInputStream(entry).readBytes()
            readStrings(bytes).asSequence().flatMap { Regex("https?://[^\\s\\x22'<>\\x00-\\x1f]+", RegexOption.IGNORE_CASE).findAll(it).map { m -> m.value.trimEnd('.', ',', ';', ')', ']', '}') } }
        }.distinct().toList()
    }

    fun readV1Certificates(apk: File): List<CertificateSummary> = ZipFile(apk).use { zip ->
        val factory = CertificateFactory.getInstance("X.509")
        zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("META-INF/", true) && it.name.substringAfterLast('/').matches(Regex(".+\\.(RSA|DSA|EC)", RegexOption.IGNORE_CASE)) }.flatMap { entry ->
            runCatching {
                val certs = factory.generateCertificates(zip.getInputStream(entry))
                certs.mapNotNull { cert ->
                    val x = cert as? java.security.cert.X509Certificate ?: return@mapNotNull null
                    CertificateSummary(entry.name, sha256(x.encoded), x.subjectX500Principal.name)
                }.asSequence()
            }.getOrDefault(emptySequence())
        }.toList()
    }

    private fun parse(name: String, b: ByteArray): DexSummary {
        require(b.size >= 112 && b.copyOfRange(0, 4).contentEquals(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())))
        val version = b.copyOfRange(4, 7).toString(Charsets.US_ASCII)
        fun u32(o: Int) = ((b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or ((b[o+2].toInt() and 255) shl 16) or ((b[o+3].toInt() and 255) shl 24)).toLong() and 0xffffffffL
        return DexSummary(name, version, u32(0x20), u32(8).toString(16), hex(MessageDigest.getInstance("SHA-1").digest(b.copyOfRange(32, 52))), u32(0x38).toInt(), u32(0x40).toInt(), u32(0x58).toInt(), u32(0x60).toInt())
    }

    private fun readStrings(b: ByteArray): List<String> {
        if (b.size < 112) return emptyList()
        fun u32(o: Int) = ((b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or ((b[o+2].toInt() and 255) shl 16) or ((b[o+3].toInt() and 255) shl 24)).toLong() and 0xffffffffL
        val count = u32(0x38).toInt(); val off = u32(0x3c).toInt()
        if (count <= 0 || off < 0 || off + count * 4 > b.size) return emptyList()
        return (0 until count).mapNotNull { i ->
            val at = u32(off + i * 4).toInt(); if (at <= 0 || at >= b.size) return@mapNotNull null
            var p = at
            // string_data_item: utf8_size + MUTF-8 bytes + NUL；长度只用于跳过，不参与解码。
            var shift = 0; while (p < b.size && (b[p].toInt() and 0x80) != 0) { p++; shift += 7; if (shift > 28) return@mapNotNull null }; p++
            val start = p; while (p < b.size && b[p].toInt() != 0) p++
            if (p <= start) null else b.copyOfRange(start, p).toString(Charsets.UTF_8)
        }
    }

    private fun sha256(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
