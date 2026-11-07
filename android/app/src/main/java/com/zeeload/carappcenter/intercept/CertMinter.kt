package com.zeeload.carappcenter.intercept

import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 用「车机本来就信任的中间 CA」现场签叶子证书。
 *
 * 车机不需要装任何证书：叶子 ← 中间 CA ← 根 CA，链路落在车机系统信任库里，
 * 所以商店的 TLS 握手会通过，我们才能看到并改写它的明文。
 *
 * CA 材料放在 assets/ca/：
 *   · zeeload-int.pem   —— 中间 CA 证书（可含多张，第一张为签发者，其余并进链）
 *   · zeeload-int.key   —— 中间 CA 私钥（PKCS#8，PEM）
 */
class CaMaterial(
    val chain: List<X509Certificate>,
    val privateKey: PrivateKey,
    val subjectNameDer: ByteArray,
) {
    companion object {
        fun load(certPemOrDer: ByteArray, keyPem: String): CaMaterial {
            val certs = parseCertificates(certPemOrDer)
            require(certs.isNotEmpty()) { "中间 CA 证书解析失败" }
            val key = Pem.readPrivateKey(keyPem)
            return CaMaterial(
                chain = certs,
                privateKey = key,
                subjectNameDer = certs.first().subjectX500Principal.encoded,
            )
        }

        fun parseCertificates(blob: ByteArray): List<X509Certificate> {
            val cf = CertificateFactory.getInstance("X.509")
            val derList = if (String(blob, Charsets.US_ASCII).contains("-----BEGIN")) Pem.readAllCertificates(blob) else listOf(blob)
            return derList.map { cf.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        }
    }
}

object Pem {
    fun decode(pem: String): ByteArray {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        return java.util.Base64.getDecoder().decode(body)
    }

    fun readAllCertificates(blob: ByteArray): List<ByteArray> {
        val text = blob.toString(Charsets.US_ASCII)
        val out = ArrayList<ByteArray>()
        val regex = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
        regex.findAll(text).forEach { out.add(decode(it.groupValues[1])) }
        return out
    }

    fun readPrivateKey(pem: String): PrivateKey {
        val isEc = pem.contains("BEGIN EC PRIVATE KEY")
        val der = decode(pem)
        val spec = if (isEc) java.security.spec.PKCS8EncodedKeySpec(der) else java.security.spec.PKCS8EncodedKeySpec(der)
        val algorithm = if (isEc) "EC" else "RSA"
        val kf = java.security.KeyFactory.getInstance(algorithm)
        return runCatching { kf.generatePrivate(spec) }.getOrElse {
            // 有些 CA 私钥是 PKCS#1，先包成 PKCS#8 再试一次
            kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Pkcs1.wrap(der, algorithm)))
        }
    }

    /** PKCS#1 → PKCS#8 包装（openssl 导出的 RSA 私钥常见形态）。 */
    private object Pkcs1 {
        fun wrap(pkcs1: ByteArray, algorithm: String): ByteArray {
            val algOid = if (algorithm == "RSA") "1.2.840.113549.1.1.1" else "1.2.840.10045.2.1"
            val version = Der.integer(0)
            val algId = Der.seq(Der.oid(algOid), Der.nullValue())
            val inner = Der.octetString(pkcs1)
            return Der.seq(version, algId, inner)
        }
    }
}

data class ServerCert(
    val host: String,
    val chain: Array<X509Certificate>,
    val keyPair: KeyPair,
)

/**
 * 叶子证书铸造：每个 SNI 一张，缓存起来复用。
 * 证书结构刻意做得极简（BC=CA:false / KU / EKU serverAuth / SAN），只求握手通过。
 */
class CertMinter(private val ca: CaMaterial) {
    private val keyPairs = HashMap<String, KeyPair>()
    private val certs = HashMap<String, Array<X509Certificate>>()

    @Synchronized
    fun forHost(host: String): ServerCert {
        val key = keyPairs.getOrPut(host) {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        }
        val chain = certs.getOrPut(host) { buildChain(host, key) }
        return ServerCert(host, chain, key)
    }

    private fun buildChain(host: String, keyPair: KeyPair): Array<X509Certificate> {
        val leafDer = buildLeafDer(host, keyPair)
        val cf = CertificateFactory.getInstance("X.509")
        val leaf = cf.generateCertificate(ByteArrayInputStream(leafDer)) as X509Certificate
        @Suppress("UNCHECKED_CAST")
        return (listOf(leaf) + ca.chain).toTypedArray()
    }

    fun buildLeafDer(host: String, keyPair: KeyPair): ByteArray {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 825L * 24 * 3600 * 1000)
        val serial = java.math.BigInteger(63, SecureRandom()).abs().max(java.math.BigInteger.ONE)

        val signatureAlg = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.nullValue()) // sha256WithRSAEncryption
        val subject = Der.seq(
            Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(host)))
        )
        val validity = Der.seq(Der.utcTime(notBefore), Der.utcTime(notAfter))

        val extensions = Der.explicit(
            3,
            Der.seq(
                Der.seq(Der.oid("2.5.29.19"), Der.boolean(true), Der.octetString(Der.seq())), // basicConstraints CA:false
                Der.seq(
                    Der.oid("2.5.29.15"),
                    Der.boolean(true),
                    Der.octetString(Der.bitString(byteArrayOf(0xA0.toByte()))) // digitalSignature | keyEncipherment
                ),
                Der.seq(
                    Der.oid("2.5.29.37"),
                    Der.octetString(Der.seq(Der.oid("1.3.6.1.5.5.7.3.1"))) // serverAuth
                ),
                Der.seq(
                    Der.oid("2.5.29.17"),
                    Der.octetString(Der.seq(tlvDnsName(host)))
                ),
            )
        )

        val tbs = Der.seq(
            Der.explicit(0, Der.integer(2)),                       // v3
            Der.integer(serial),
            signatureAlg,
            ca.subjectNameDer,                                     // issuer = 中间 CA 的 subject
            validity,
            subject,
            keyPair.public.encoded,                                // SubjectPublicKeyInfo
            extensions,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(ca.privateKey)
            update(tbs)
            sign()
        }
        return Der.seq(tbs, signatureAlg, Der.bitString(signature))
    }

    private fun tlvDnsName(host: String): ByteArray =
        Der.tlv(0x82, host.toByteArray(Charsets.US_ASCII)) // [2] dNSName
}
