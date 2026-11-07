package com.zeeload.carappcenter.intercept

/** APK 自身元信息：升级判定（版本 / 签名）全靠这几个字段。 */
data class ApkMeta(
    val packageName: String = "",
    val versionCode: Long = 0,
    val versionName: String = "",
    val label: String = "",
    val signerSha256: String = "",
    val sha256: String = "",
    val md5: String = "",
    val size: Long = 0,
    val path: String = "",
)

enum class ArmMode { AUTO, UPDATE, NEW }

/** 武装状态：谁被当成「有更新」、装哪个文件、有效期多久。 */
data class ArmState(
    val enabled: Boolean = false,
    val mode: ArmMode = ArmMode.AUTO,
    /** 注入目标包名（UPDATE 模式下 = APK 自己的包名；NEW 模式下 = 外壳应用） */
    val targetPackage: String = "",
    val apk: ApkMeta = ApkMeta(),
    val armedAt: Long = 0L,
    val ttlSeconds: Long = 3600L,
    /** 被拦截的应用市场包名集合（VpnService 的 addAllowedApplication 白名单） */
    val marketPackages: Set<String> = emptySet(),
    val warnings: List<String> = emptyList(),
) {
    fun fresh(now: Long = System.currentTimeMillis()): Boolean =
        enabled && armedAt > 0 && now - armedAt < ttlSeconds * 1000

    fun remainingSeconds(now: Long = System.currentTimeMillis()): Long =
        (ttlSeconds - (now - armedAt) / 1000).coerceAtLeast(0)

    fun toProperties(): String {
        val p = java.util.Properties()
        p["enabled"] = enabled.toString()
        p["mode"] = mode.name
        p["target"] = targetPackage
        p["pkg"] = apk.packageName
        p["vc"] = apk.versionCode.toString()
        p["vn"] = apk.versionName
        p["label"] = apk.label
        p["signer"] = apk.signerSha256
        p["sha256"] = apk.sha256
        p["md5"] = apk.md5
        p["size"] = apk.size.toString()
        p["path"] = apk.path
        p["armedAt"] = armedAt.toString()
        p["ttl"] = ttlSeconds.toString()
        p["markets"] = marketPackages.joinToString(",")
        p["warnings"] = warnings.joinToString("\u001f")
        return java.io.StringWriter().also { p.store(it, null) }.toString()
    }

    companion object {
        fun fromProperties(text: String?): ArmState {
            if (text.isNullOrBlank()) return ArmState()
            val p = java.util.Properties()
            return runCatching { p.load(java.io.StringReader(text)) }.let {
                ArmState(
                    enabled = p.getProperty("enabled")?.toBooleanStrictOrNull() ?: false,
                    mode = runCatching { ArmMode.valueOf(p.getProperty("mode") ?: "AUTO") }
                        .getOrDefault(ArmMode.AUTO),
                    targetPackage = p.getProperty("target").orEmpty(),
                    apk = ApkMeta(
                        packageName = p.getProperty("pkg").orEmpty(),
                        versionCode = p.getProperty("vc")?.toLongOrNull() ?: 0L,
                        versionName = p.getProperty("vn").orEmpty(),
                        label = p.getProperty("label").orEmpty(),
                        signerSha256 = p.getProperty("signer").orEmpty(),
                        sha256 = p.getProperty("sha256").orEmpty(),
                        md5 = p.getProperty("md5").orEmpty(),
                        size = p.getProperty("size")?.toLongOrNull() ?: 0L,
                        path = p.getProperty("path").orEmpty(),
                    ),
                    armedAt = p.getProperty("armedAt")?.toLongOrNull() ?: 0L,
                    ttlSeconds = p.getProperty("ttl")?.toLongOrNull() ?: 3600L,
                    marketPackages = p.getProperty("markets").orEmpty()
                        .split(',').filter { it.isNotBlank() }.toSet(),
                    warnings = p.getProperty("warnings").orEmpty()
                        .split('\u001f').filter { it.isNotBlank() },
                )
            }
        }
    }
}

/**
 * 武装决策：先判定「能不能升级」，再决定注入目标。
 * 只做纯计算（不碰 Android API），因此可以完整单测。
 */
object ArmPlanner {
    data class Decision(val state: ArmState)

    /**
     * @param requested 用户选的模式
     * @param apk       待装 APK 的元信息
     * @param storeKnowsApkPackage 商店数据里是否已经有这个包（= 车机上装着它，可走升级）
     * @param shellPackage NEW 模式下用来当「更新外壳」的已安装应用
     * @param installedVersionCode 车机上该应用的 versionCode（商店元数据或用户显式给出）
     * @param installedSignerSha256 车机上该应用的签名指纹（可选，给了就比对）
     */
    fun decide(
        requested: ArmMode,
        apk: ApkMeta,
        storeKnowsApkPackage: Boolean,
        shellPackage: String,
        installedVersionCode: Long?,
        installedSignerSha256: String?,
        ttlSeconds: Long = 3600,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        val warnings = ArrayList<String>()
        require(apk.size > 0) { "APK 文件读不出来：${apk.path}" }
        val mode = when (requested) {
            ArmMode.AUTO -> if (storeKnowsApkPackage && apk.packageName.isNotEmpty()) ArmMode.UPDATE else ArmMode.NEW
            else -> requested
        }
        val target: String
        if (mode == ArmMode.UPDATE) {
            require(apk.packageName.isNotEmpty()) { "升级模式必须读出 APK 包名（当前解析失败）" }
            target = apk.packageName
            if (installedVersionCode != null && apk.versionCode <= installedVersionCode) {
                throw IllegalArgumentException(
                    "升级走不通：APK 内部 versionCode=${apk.versionCode} ≤ 已安装 $installedVersionCode。" +
                        "安装器只认 APK 自己的 versionCode，先把版本号加大重新出包（或改包名走新装）"
                )
            }
            if (installedVersionCode == null) {
                warnings.add("还拿不到已安装版本号：先在车机点开该应用详情页，或手动填入已装 versionCode")
            }
            if (!installedSignerSha256.isNullOrBlank() && apk.signerSha256.isNotEmpty()) {
                if (installedSignerSha256 != apk.signerSha256) {
                    warnings.add(
                        "签名不一致：本次 ${apk.signerSha256.take(12)}… vs 已装 ${installedSignerSha256.take(12)}…，同包升级会被拒装"
                    )
                } else {
                    warnings.add("签名一致，可走同包升级")
                }
            } else if (apk.signerSha256.isNotEmpty() && installedVersionCode != null) {
                // 已装版本知道、但拿不到它的签名时才提示（否则和上面"拿不到版本号"重复）
                warnings.add("升级要求签名与已安装应用一致（本 APK ${apk.signerSha256.take(12)}…）")
            }
        } else {
            target = shellPackage.ifBlank { "ALL" }
            if (storeKnowsApkPackage && apk.packageName.isNotEmpty()) {
                warnings.add("包名 ${apk.packageName} 商店里已有，可改用 UPDATE 直接升级（保留应用数据）")
            }
            if (apk.packageName.isEmpty()) {
                warnings.add("读不出 APK 包名：只能按外壳模式注入，版本号走哈希派生")
            }
        }
        return Decision(
            ArmState(
                enabled = true,
                mode = mode,
                targetPackage = target,
                apk = apk,
                armedAt = now,
                ttlSeconds = ttlSeconds,
                warnings = warnings,
            )
        )
    }
}
