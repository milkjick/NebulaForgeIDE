package com.nebulaforge.app.reverse.api

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** 本地 MITM CA 与按域名签发的临时服务器证书。仅用于用户授权的本机流量分析。 */
class CertificateAuthority(private val context: Context) {
    private val dir = File(context.filesDir, "reverse/mitm")
    private val keyFile = File(dir, "ca-private.der")
    private val certFile = File(dir, "ca-cert.der")
    private val random = SecureRandom()
    private var pair: KeyPair? = null
    private var certificate: X509Certificate? = null

    @Synchronized fun ensure(): X509Certificate {
        if (certificate != null && pair != null) return certificate!!
        dir.mkdirs()
        if (keyFile.isFile && certFile.isFile) {
            // 读到旧版本留下的损坏文件时静默丢弃并重建，绝不让历史产物导致崩溃。
            runCatching {
                val privateKey = java.security.KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile.inputStream()) as X509Certificate
                pair = KeyPair(cert.publicKey, privateKey)
                certificate = cert
                return cert
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, random)
        val generated = generator.generateKeyPair()
        val cert = CertificateDer.createCertificate(
            issuerCa = true,
            commonName = CA_COMMON_NAME,
            publicKey = generated.public,
            issuerKey = generated.private,
            serial = newSerial(),
            days = 3650,
            dnsName = null
        )
        keyFile.writeBytes(generated.private.encoded)
        certFile.writeBytes(cert.encoded)
        pair = generated
        certificate = cert
        return cert
    }

    @Synchronized fun issue(host: String): KeyStore {
        val caCert = ensure()
        val caPair = pair ?: error("CA 私钥不可用")
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, random)
        val leafPair = generator.generateKeyPair()
        val leaf = CertificateDer.createCertificate(
            issuerCa = false,
            commonName = host,
            publicKey = leafPair.public,
            issuerKey = caPair.private,
            serial = newSerial(),
            days = 7,
            dnsName = host
        )
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("leaf", leafPair.private, STORE_PASSWORD.toCharArray(), arrayOf(leaf, caCert))
        }
    }

    /**
     * MITM 用的服务器 SSLContext（按域名缓存）。
     *
     * 原来每个 CONNECT 都现生成一对 RSA-2048 密钥并签发叶子证书（真机约 60~150ms），
     * 页面一并发十几个请求就会排队，表现是「代理启动了但抓包极卡、大量请求超时」。
     * 这里按域名缓存（LinkedHashMap accessOrder + LRU 上限 64），同域名复用同一张叶子证书。
     */
    @Synchronized fun sslContextFor(host: String): SSLContext =
        contextCache.getOrPut(host) { buildSslContext(host) }

    private fun buildSslContext(host: String): SSLContext {
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        km.init(issue(host), STORE_PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, random) }
    }

    private val contextCache = object : LinkedHashMap<String, SSLContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLContext>?) = size > 64
    }

    fun certificateFile(): File = certFile.also { ensure() }

    /** PEM 文本（`-----BEGIN CERTIFICATE-----`）。给桌面浏览器/其它工具导入用。 */
    fun pemBytes(): ByteArray {
        val base64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(ensure().encoded)
        return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
            .toByteArray(Charsets.US_ASCII)
    }

    /**
     * 把 CA 导出到用户可见的公共目录，返回写成功的文件（全部失败返回 null）。
     *
     * 导出 `.pem`（浏览器/桌面工具）与 `.crt`（Android「从存储设备安装」认的 DER 后缀）两份，
     * 是因为「安装 CA」按钮在部分机型（EMUI 等）会唤起不了系统安装页，此时导出后手动安装
     * 是唯一可行的路径。
     */
    fun exportToPublic(context: Context): File? {
        val cert = ensure()
        val dir = File("/storage/emulated/0/NebulaForgeIDE/certs")
        val fallback = context.getExternalFilesDir(null)?.let { File(it, "certs") }
        val targets = listOfNotNull(dir, fallback)
        for (target in targets) {
            val pem = File(target, "nebulaforge-ca.pem")
            val crt = File(target, "nebulaforge-ca.crt")
            val ok = runCatching {
                target.mkdirs()
                pem.writeBytes(pemBytes())
                crt.writeBytes(cert.encoded)
            }.isSuccess
            if (ok) return pem
        }
        return null
    }

    /** 分享导出好的证书文件（微信/邮件发到电脑上导入）。 */
    fun shareIntent(exported: File): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", exported
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/x-pem-file"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NebulaForge 本地代理 CA")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 打开系统「加密与凭据 / 安全」设置页：KeyChain 安装页唤不起时的兜底入口。 */
    fun securitySettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(): Intent {
        val cert = ensure()
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, cert.encoded)
            putExtra(KeyChain.EXTRA_NAME, "NebulaForge 本地 HTTPS 代理 CA")
            // 从 Application context 唤起（如 AI Agent 触发的路径）时没有 NEW_TASK 会直接抛
            // ActivityNotFoundException，表现为「点安装 CA 毫无反应」。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 计算 Android 系统证书库 `/system/etc/security/cacerts/` 需要的文件名（OpenSSL subject hash）。
     *
     * 优先调用用户态 openssl（Termux 前缀里通常有，最权威）；没有时退回 MD5 实现：
     * Android 的 cacerts 文件名沿用 OpenSSL 0.9.8 的 `X509_NAME_hash` = MD5(subject DER) 前 4 字节小端。
     */
    fun subjectHash(context: Context): String {
        val openssl = File(com.nebulaforge.core.environment.Environment.usrRoot(context), "bin/openssl")
        if (openssl.isFile) {
            val out = runCatching {
                val p = ProcessBuilder(
                    openssl.absolutePath, "x509", "-inform", "DER", "-in", certFile.absolutePath,
                    "-noout", "-subject_hash"
                ).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor()
                text
            }.getOrDefault("")
            Regex("[0-9a-f]{8}").find(out)?.value?.let { return it }
        }
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(ensure().subjectX500Principal.encoded)
        val h = (md5[0].toInt() and 0xFF) or
            ((md5[1].toInt() and 0xFF) shl 8) or
            ((md5[2].toInt() and 0xFF) shl 16) or
            ((md5[3].toInt() and 0xFF) shl 24)
        return "%08x".format(h)
    }

    /** 128 位正随机序列号；序列号必须为正整数，0 在某些校验方会被拒绝。 */
    private fun newSerial(): BigInteger = BigInteger(128, random).max(BigInteger.ONE)

    companion object {
        const val CA_COMMON_NAME = "NebulaForge Local CA"
        private const val STORE_PASSWORD = "changeit"
    }

    /**
     * 最小但严格的 DER 编码器。
     *
     * 历史实现有 3 个真实缺陷，导致 Android 端 `CertificateFactory.generateCertificate`
     * 抛 `ASN.1 encoding routines ... TOO_LONG`（用户可见表现是点「安装 CA」立即闪退）：
     *  1. 硬编码的 sha256WithRSA AlgorithmIdentifier 漏了 NULL 参数：写成了
     *     `30 0d 06 09 <9 字节 OID>`，声明长度 13 而实际内容只有 11 字节 —— 这就是 TOO_LONG。
     *  2. BasicConstraints 的 `extnValue` 漏了 OCTET STRING 包装，pathLen 的 INTEGER 直接落在
     *     Extension SEQUENCE 里，解析时报 `Field=value, Type=X509_EXTENSION`。
     *  3. Name 把 "CN=xxx" 整串当作属性值，证书 CN 变成字面量 "CN=xxx"。
     * 现改为：算法标识用 `sequence(oid, NULL)` 构造、扩展值统一 `octet(...)` 包装、
     * Name 交给 `X500Principal` 编码，并在签名前用 [requireWellFormed] 自检 TLV 结构。
     */
    private object CertificateDer {
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        fun createCertificate(
            issuerCa: Boolean,
            commonName: String,
            publicKey: PublicKey,
            issuerKey: PrivateKey,
            serial: BigInteger,
            days: Int,
            dnsName: String?
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val sigAlg = sha256WithRsa()
            val issuer = if (issuerCa) "CN=$commonName" else "CN=${CA_COMMON_NAME}"
            val subject = "CN=$commonName"
            val tbs = sequence(
                explicitVersion3(),
                integer(serial),
                sigAlg,
                name(issuer),
                validity(Date(now - 60_000), Date(now + days * 24L * 60L * 60L * 1000L)),
                name(subject),
                publicKeyInfo(publicKey),
                // TBSCertificate 中 extensions 必须是 [3] EXPLICIT 包装；Extensions 本体是 SEQUENCE。
                explicit(3, extensions(issuerCa, dnsName))
            )
            val signature = Signature.getInstance("SHA256withRSA")
                .apply { initSign(issuerKey); update(tbs) }
                .sign()
            val der = sequence(tbs, sigAlg, bitString(signature))
            val label = if (issuerCa) "本地 CA 证书" else "服务器证书（$dnsName）"
            requireWellFormed(der, label)
            return runCatching {
                CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            }.getOrElse { t ->
                throw IllegalStateException("$label 解析失败：${t.message ?: t.javaClass.simpleName}", t)
            }
        }

        // ---- ASN.1 基础类型 ----

        private fun sha256WithRsa() = sequence(oid("1.2.840.113549.1.1.11"), nullValue())
        private fun explicitVersion3() = byteArrayOf(0xa0.toByte(), 0x03, 0x02, 0x01, 0x02)

        private fun validity(from: Date, to: Date) = sequence(generalOrUtcTime(from), generalOrUtcTime(to))

        /** RFC 5280：2050 年以前用 UTCTime，之后必须用 GeneralizedTime。 */
        private fun generalOrUtcTime(date: Date): ByteArray {
            val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = UTC }.format(date).toInt()
            return if (year >= 2050) {
                tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = UTC }
                    .format(date).toByteArray(Charsets.US_ASCII))
            } else {
                tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = UTC }
                    .format(date).toByteArray(Charsets.US_ASCII))
            }
        }

        /** Name 用 JDK 实现编码，避免手写 RDN 时把 "CN=" 当成属性值。 */
        private fun name(dn: String) = X500Principal(dn).encoded

        private fun publicKeyInfo(key: PublicKey) = sequence(
            sequence(oid("1.2.840.113549.1.1.1"), nullValue()),
            bitString(key.encodedPkcs1())
        )

        private fun PublicKey.encodedPkcs1(): ByteArray {
            val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
            val rsa = pub as RSAPublicKey
            return sequence(integer(rsa.modulus), integer(rsa.publicExponent))
        }

        private fun extensions(ca: Boolean, dnsName: String?): ByteArray {
            val list = mutableListOf<ByteArray>()
            // Extension ::= SEQUENCE { extnID OID, critical BOOLEAN DEFAULT FALSE, extnValue OCTET STRING }
            // extnValue 里才是该扩展自身的 DER —— 少了 octet() 包装就会解析失败。
            list += if (ca) {
                // BasicConstraints ::= SEQUENCE { cA BOOLEAN DEFAULT FALSE, pathLenConstraint INTEGER OPTIONAL }
                sequence(
                    oid("2.5.29.19"),
                    boolean(true),
                    octet(sequence(boolean(true), integer(BigInteger.ZERO)))
                )
            } else {
                // cA 为 DEFAULT FALSE，按 DER 必须省略 → 空 SEQUENCE
                sequence(oid("2.5.29.19"), octet(sequence()))
            }
            // KeyUsage ::= BIT STRING，位从高位起：CA 需要 keyCertSign+cRLSign，叶子需要
            // digitalSignature+keyEncipherment。
            if (ca) {
                list += sequence(oid("2.5.29.15"), boolean(true), octet(bitString(byteArrayOf(0x06))))
            } else {
                list += sequence(oid("2.5.29.15"), boolean(true), octet(bitString(byteArrayOf(0xA0.toByte()))))
                dnsName?.takeIf { it.isNotBlank() }?.let { host ->
                    list += sequence(
                        oid("2.5.29.17"),
                        octet(sequence(tlv(0x82, host.toByteArray(Charsets.US_ASCII))))
                    )
                }
            }
            return sequence(*list.toTypedArray())
        }

        private fun boolean(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xff.toByte() else 0))
        private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
        private fun octet(v: ByteArray) = tlv(0x04, v)
        private fun bitString(v: ByteArray) = tlv(0x03, byteArrayOf(0) + v)
        private fun nullValue() = tlv(0x05, ByteArray(0))
        private fun sequence(vararg v: ByteArray?) =
            tlv(0x30, v.filterNotNull().fold(ByteArray(0)) { a, b -> a + b })

        private fun explicit(tag: Int, v: ByteArray) = tlv(0xa0 + tag, v)

        private fun oid(value: String): ByteArray {
            val parts = value.split('.').map(String::toLong)
            val out = ArrayList<Byte>()
            out += (parts[0] * 40 + parts[1]).toByte()
            for (p in parts.drop(2)) {
                var n = p
                val tmp = ArrayList<Byte>()
                tmp += (n and 0x7f).toByte()
                n = n ushr 7
                while (n > 0) {
                    tmp += ((n and 0x7f) or 0x80).toByte()
                    n = n ushr 7
                }
                tmp.asReversed().forEach { out += it }
            }
            return tlv(0x06, out.toByteArray())
        }

        private fun tlv(tag: Int, body: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + length(body.size) + body

        private fun length(n: Int): ByteArray = if (n < 128) byteArrayOf(n.toByte()) else {
            val bytes = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            byteArrayOf((0x80 or bytes.size).toByte()) + bytes
        }

        /**
         * 走一遍 TLV 结构：任何「声明长度超出缓冲区」「子元素未对齐」都会立刻抛出，
         * 并带上出错偏移量与 TLV 标签，避免再次出现只能靠 OpenSSL 反推的 TOO_LONG。
         */
        private fun requireWellFormed(der: ByteArray, what: String) {
            fun fail(reason: String): Nothing =
                throw IllegalStateException("$what DER 结构错误：$reason（总长 ${der.size} 字节）")

            fun readLength(pos: Int): Pair<Int, Int> {
                if (pos >= der.size) fail("长度字段越界 @$pos")
                val first = der[pos].toInt() and 0xFF
                val p = pos + 1
                if (first < 0x80) return first to p
                val count = first and 0x7F
                if (count == 0 || count > 4) fail("不支持的长度形式 0x${first.toString(16)} @$pos")
                if (p + count > der.size) fail("长形式长度越界 @$pos")
                var len = 0
                for (i in 0 until count) len = (len shl 8) or (der[p + i].toInt() and 0xFF)
                return len to (p + count)
            }

            fun walk(pos: Int, depth: Int): Int {
                if (depth > 16) fail("嵌套过深 @$pos")
                if (pos >= der.size) fail("期望 TLV 但已到末尾 @$pos")
                val tag = der[pos].toInt() and 0xFF
                val (len, contentStart) = readLength(pos + 1)
                val end = contentStart + len
                if (end > der.size) {
                    fail("TLV @$pos (tag 0x${tag.toString(16)}) 声明长度 $len 超出缓冲区（剩余 ${der.size - contentStart} 字节）")
                }
                if (tag and 0x20 != 0) {
                    var child = contentStart
                    while (child < end) child = walk(child, depth + 1)
                    if (child != end) fail("TLV @$pos 子元素未对齐")
                }
                return end
            }

            val consumed = walk(0, 0)
            if (consumed != der.size) fail("顶层对象未消费全部字节（$consumed/${der.size}）")
        }
    }
}
