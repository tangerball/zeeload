package com.zeeload.carappcenter.intercept

import android.content.Context
import android.content.pm.PackageManager
import com.zeeload.carappcenter.CarApp
import com.zeeload.carappcenter.InstallRecord
import com.zeeload.carappcenter.ManagedApp
import kotlinx.coroutines.delay

/**
 * 拦截链路的"对账"：武装之后盯着目标包，等商店把它装完，
 * 然后把这次安装写进同一张安装历史里，并标注 `via=market` 与真实 installer。
 *
 * 我们自己不参与安装，所以只能靠版本号变化 + PackageManager 的安装来源来判定。
 */
object MarketInstallWatch {

    data class Detected(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val installer: String,
    )

    /** 商店包名（安装来源）。API 30+ 用 getInstallSourceInfo，低版本退回 deprecated 字段。 */
    fun installerOf(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName).orEmpty()
        }
    }.getOrDefault("")

    fun installedVersionCode(context: Context, packageName: String): Long? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrNull()

    /**
     * 轮询等待安装完成。baseline 为武装前的版本号（未安装传 0）。
     * 返回 null 表示超时/被取消。
     */
    suspend fun watch(
        context: Context,
        targetPackage: String,
        baseline: Long,
        timeoutMs: Long = 10 * 60 * 1000,
        intervalMs: Long = 2000,
    ): Detected? {
        if (targetPackage.isBlank()) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val vc = installedVersionCode(context, targetPackage)
            if (vc != null && vc != baseline) {
                val name = runCatching {
                    val pm = context.packageManager
                    pm.getPackageInfo(targetPackage, 0).versionName.orEmpty()
                }.getOrDefault("")
                return Detected(targetPackage, vc, name, installerOf(context, targetPackage))
            }
            delay(intervalMs)
        }
        return null
    }

    /** 把"商店装完了"写进安装历史，跟按地址安装的记录放在同一处对账。 */
    suspend fun record(context: Context, detected: Detected): String {
        val services = (context.applicationContext as? CarApp)?.services ?: return "无服务上下文"
        val installerText = detected.installer.ifBlank { "未知（可能由系统 installer 代装）" }
        val result = "商店已安装 · via=market · installer=$installerText · vc=${detected.versionCode}"
        val old = runCatching { services.db.apps().get(detected.packageName) }.getOrNull()
        val actual = runCatching { services.packages.find(detected.packageName) }.getOrNull()
        runCatching {
            services.db.apps().put(
                ManagedApp(
                    packageName = detected.packageName,
                    name = actual?.name ?: old?.name ?: detected.packageName,
                    url = old?.url.orEmpty(),
                    origin = if (old?.origin.isNullOrBlank()) "应用市场（拦截安装）" else old.origin,
                    versionCode = detected.versionCode,
                    versionName = detected.versionName,
                    certificateSha256 = actual?.certificateSha256 ?: old?.certificateSha256.orEmpty(),
                    apkSha256 = old?.apkSha256.orEmpty(),
                    installedAt = old?.installedAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    lastResult = result,
                )
            )
            services.db.apps().record(
                InstallRecord(
                    packageName = detected.packageName,
                    at = System.currentTimeMillis(),
                    versionCode = detected.versionCode,
                    result = result,
                    apkSha256 = old?.apkSha256.orEmpty(),
                )
            )
            services.logs.log(
                "install-result", result, detected.packageName,
                versionCode = detected.versionCode,
            )
        }
        return result
    }

    /** 兼容旧调用：取安装来源（给界面显示"预期 installer"用）。 */
    fun pmOrNull(context: Context): PackageManager? = context.packageManager
}
