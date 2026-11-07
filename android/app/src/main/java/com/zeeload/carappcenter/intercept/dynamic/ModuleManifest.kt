package com.zeeload.carappcenter.intercept.dynamic

import com.zeeload.carappcenter.intercept.J
import com.zeeload.carappcenter.intercept.Json
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 局域网模块清单。参与签名的字段是固定的「规范化串」，
 * 只要 id/version/entry/dexSha256/rulesSha256 任一被改，签名立刻失效——
 * 这挡住了「局域网里有人插一个假模块」的风险。
 */
data class ModuleManifest(
    val schema: Int = 1,
    val id: String = "",
    val version: String = "",
    val entryClass: String = "",
    val dexFile: String = "plugin.dex",
    val dexSha256: String = "",
    val rulesFile: String = "rules.json",
    val rulesSha256: String = "",
    val signature: String = "",
    val note: String = "",
) {
    fun canonical(): String =
        listOf(schema, id, version, entryClass, dexSha256, rulesSha256).joinToString("|")

    companion object {
        fun fromJson(text: String): ModuleManifest? {
            val root = Json.parseObject(text) ?: return null
            fun str(key: String, fallback: String = "") = root.map[key]?.string() ?: fallback
            fun num(key: String, fallback: Long) = root.map[key]?.long() ?: fallback
            val id = str("id")
            val entry = str("entry", str("entryClass"))
            if (id.isBlank() || entry.isBlank()) return null
            return ModuleManifest(
                schema = num("schema", 1).toInt(),
                id = id,
                version = str("version"),
                entryClass = entry,
                dexFile = str("dex", "plugin.dex"),
                dexSha256 = str("dexSha256"),
                rulesFile = str("rules", "rules.json"),
                rulesSha256 = str("rulesSha256"),
                signature = str("sig"),
                note = str("note"),
            )
        }
    }
}

data class VerifyResult(val ok: Boolean, val problems: List<String>)

/** 校验模块：签名（固定公钥）+ 每个文件的 sha256。任一项不过就拒绝加载。 */
class ModuleVerifier(private val publicKey: PublicKey) {

    fun verify(manifest: ModuleManifest, dex: ByteArray, rules: ByteArray?): VerifyResult {
        val problems = ArrayList<String>()
        if (manifest.schema != 1) problems.add("不支持 schema=${manifest.schema}")
        if (manifest.dexSha256.isBlank() || sha256(dex) != manifest.dexSha256) {
            problems.add("plugin.dex 的 sha256 与清单不一致")
        }
        if (rules != null) {
            if (manifest.rulesSha256.isBlank() || sha256(rules) != manifest.rulesSha256) {
                problems.add("rules.json 的 sha256 与清单不一致")
            }
        }
        if (manifest.signature.isBlank()) {
            problems.add("清单缺少签名")
        } else {
            val ok = runCatching {
                val sigBytes = Base64.getDecoder().decode(manifest.signature)
                val algorithm = when (publicKey.algorithm.uppercase()) {
                    "EC", "ECDSA" -> "SHA256withECDSA"
                    "RSA" -> "SHA256withRSA"
                    else -> "SHA256with${publicKey.algorithm}"
                }
                Signature.getInstance(algorithm).run {
                    initVerify(publicKey)
                    update(manifest.canonical().toByteArray(Charsets.UTF_8))
                    verify(sigBytes)
                }
            }.getOrDefault(false)
            if (!ok) problems.add("签名验证失败（不是我们用私钥签的清单）")
        }
        return VerifyResult(problems.isEmpty(), problems)
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** 支持 Base64(X.509) 或 PEM 两种写法。 */
        fun loadPublicKey(text: String): PublicKey? = runCatching {
            val base64 = text.lineSequence()
                .map { it.trim() }
                .filterNot { it.isEmpty() || it.startsWith("#") || it.startsWith("-----") }
                .joinToString("")
                .trim()
            val der = Base64.getDecoder().decode(base64)
            val spec = X509EncodedKeySpec(der)
            val alg = runCatching { KeyFactory.getInstance("EC").generatePublic(spec) }.getOrNull()
                ?: KeyFactory.getInstance("RSA").generatePublic(spec)
            alg
        }.getOrNull()

        fun fromBase64(base64Key: String): ModuleVerifier? =
            loadPublicKey(base64Key)?.let { ModuleVerifier(it) }
    }
}
