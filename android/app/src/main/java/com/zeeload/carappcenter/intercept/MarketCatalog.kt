package com.zeeload.carappcenter.intercept

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** 车机上的一个候选「应用市场」应用。 */
data class MarketApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasInternet: Boolean,
    val score: Int,
)

/**
 * 动态选择要拦截的应用市场：
 * 不写死包名（车机上的商店可能叫应用商店 / 应用中心 / 市场，甚至第三方市场），
 * 按「能上网 + 系统应用 + 名称含商店语义」打分排序，让用户在界面上挑。
 */
object MarketRanking {
    private val KEYWORDS = listOf("商店", "市场", "应用中心", "应用", "store", "market", "app", "shop", "商城")

    fun score(packageName: String, label: String, isSystem: Boolean, hasInternet: Boolean): Int {
        if (!hasInternet) return -1
        var s = 0
        if (isSystem) s += 20
        val hay = (label + " " + packageName).lowercase()
        if (KEYWORDS.any { hay.contains(it) }) s += 30
        if (hay.contains("store") || hay.contains("market") || hay.contains("商店") || hay.contains("市场")) s += 10
        if (packageName.contains("launcher")) s -= 40
        if (packageName.contains("input") || packageName.contains("keyboard")) s -= 40
        if (packageName.contains("settings")) s -= 15
        return s
    }

    fun rank(rows: List<MarketApp>): List<MarketApp> = rows.filter { it.score >= 0 }
        .sortedWith(compareByDescending<MarketApp> { it.score }.thenBy { it.label })
}

class MarketCatalog(private val context: Context) {

    fun candidates(): List<MarketApp> {
        val pm = context.packageManager
        val out = ArrayList<MarketApp>()
        pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { ai ->
            val pkg = ai.packageName ?: return@forEach
            if (pkg == context.packageName) return@forEach
            val hasInternet = runCatching {
                pm.checkPermission(android.Manifest.permission.INTERNET, pkg) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            val score = MarketRanking.score(pkg, label, isSystem, hasInternet)
            if (score < 0) return@forEach
            out.add(MarketApp(pkg, label, isSystem, hasInternet, score))
        }
        return MarketRanking.rank(out)
    }

    fun labelOf(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * 打开商店（用户去点「更新」的那一步）。
     * @return true = 真的拉起了；false = 没有启动入口 / 被系统拒绝（调用方要提示用户手动打开）
     */
    /** 兜底：直接打开系统的「应用详情」页，用户可以从那里点「打开」。 */
    fun openAppDetails(packageName: String): Boolean {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun launchStore(packageName: String): Boolean {
        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
