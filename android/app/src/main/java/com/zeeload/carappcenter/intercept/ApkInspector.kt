package com.zeeload.carappcenter.intercept

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** 在车机本地读 APK：包名 / versionCode / versionName / 签名身份 / 文件哈希。 */
class ApkInspector(private val context: Context) {

    fun inspect(file: File): ApkMeta {
        require(file.isFile && file.length() > 0) { "APK 不存在或为空：${file.path}" }
        val (sha, md5) = hashes(file)
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES)
        val info = pm.getPackageArchiveInfo(file.path, flags)
            ?: return ApkMeta(
                sha256 = sha, md5 = md5, size = file.length(), path = file.path
            )
        info.applicationInfo?.let { ai ->
            ai.sourceDir = file.path
            ai.publicSourceDir = file.path
        }
        val signer = signerDigest(info)
        return ApkMeta(
            packageName = info.packageName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong(),
            versionName = info.versionName.orEmpty(),
            label = info.applicationInfo?.let { ai ->
                runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault("")
            }.orEmpty(),
            signerSha256 = signer,
            sha256 = sha,
            md5 = md5,
            size = file.length(),
            path = file.path,
        )
    }

    private fun signerDigest(info: android.content.pm.PackageInfo): String {
        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
        return bytes?.let { sha256Hex(it) }.orEmpty()
    }

    /** 车机上已安装应用的签名指纹（用于同包升级前的签名一致性检查）。 */
    fun installedSigner(packageName: String): String? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        signerDigest(info).ifBlank { null }
    }.getOrNull()

    fun installedVersionCode(packageName: String): Long? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, 0)
        } else {
            pm.getPackageInfo(packageName, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrNull()

    companion object {
        fun hashes(file: File): Pair<String, String> {
            val sha = MessageDigest.getInstance("SHA-256")
            val md5 = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sha.update(buf, 0, n)
                    md5.update(buf, 0, n)
                }
            }
            return sha.digest().toHex() to md5.digest().toHex()
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
