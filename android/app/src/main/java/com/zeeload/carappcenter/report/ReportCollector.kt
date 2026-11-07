package com.zeeload.carappcenter.report

import android.content.Context
import android.os.Build
import com.zeeload.carappcenter.Services
import com.zeeload.carappcenter.intercept.ArmRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把「异常 + 调试日志 + 安装/拦截记录 + 设备信息」采集成一份 [ReportInput]。
 * 只在这里碰 Android API，ReportBuilder 保持纯函数。
 */
class ReportCollector(
    private val context: Context,
    private val services: Services,
) {
    private val crashDir = File(context.filesDir, "crash")
    private val crashLog = CrashLog(crashDir)

    fun installCrashHandler() = crashLog.install()

    suspend fun collect(): ReportInput {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        val events = runCatching { services.db.events().recent(5000) }.getOrDefault(emptyList())
        val fmtDate = { ts: Long -> SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ts)) }
        val debugLogs = events.map { e ->
            "${fmt.format(Date(e.ts))} [${e.level}] ${e.stage} ${e.packageName.ifBlank { "-" }} " +
                "http=${e.httpStatus} err=${e.errorCode.ifBlank { "-" }} ${e.message}"
        }

        val installs = runCatching { services.db.apps().all() }.getOrDefault(emptyList())
        val installLines = installs.map { a ->
            "pkg=${a.packageName} name=${a.name} vc=${a.versionCode} vn=${a.versionName} " +
                "origin=${a.origin} sha=${a.apkSha256.take(16)} result=${a.lastResult}"
        }

        val liveEvents = ArmRepository.get(context).events.value.map { "${it.time} [${it.kind}] ${it.detail}" }
        val persisted = runCatching {
            services.db.interceptEvents().recent(500).map { e ->
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(e.ts))
                "$stamp [${e.kind}] ${e.detail}"
            }
        }.getOrDefault(emptyList())
        val intercept = (liveEvents + persisted).distinct()

        val packages = runCatching {
            services.packages.installed().map { app ->
                "pkg=${app.info.packageName} name=${app.info.name} vc=${app.info.versionCode} " +
                    "system=${app.system} cert=${app.info.certificateSha256.take(16)}"
            }
        }.getOrDefault(emptyList())

        val deviceName = runCatching {
            @Suppress("HardwareIds")
            val model = Build.MODEL.orEmpty()
            if (Build.BRAND.isBlank()) model else "${Build.BRAND} $model"
        }.getOrDefault("car")

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")

        // 每条安装记录带上关联的拦截事件与日志（时间窗 ±15 分钟 + 同包）
        val historyRows = runCatching { services.db.apps().historySnapshot() }.getOrDefault(emptyList())
        val allEvents = runCatching { services.db.interceptEvents().recent(500) }.getOrDefault(emptyList())
        val detail = historyRows.take(100).map { record ->
            val from = record.at - 15 * 60_000L
            val to = record.at + 5 * 60_000L
            val relatedEvents = allEvents
                .filter { it.ts in from..to }
                .filter { it.detail.contains(record.packageName) || it.detail.contains(record.packageName.substringAfterLast('.')) }
                .sortedBy { it.ts }
                .take(20)
                .map { e -> "${fmtDate(e.ts)} [${e.kind}] ${e.detail}" }
            val relatedLogs = events
                .filter { it.packageName == record.packageName && it.ts in (record.at - 30 * 60_000L)..(record.at + 10 * 60_000L) }
                .sortedBy { it.ts }
                .take(20)
                .map { e -> "${fmtDate(e.ts)} [${e.level}] ${e.stage} ${e.message}" }
            InstallDetail(
                packageName = record.packageName,
                at = record.at,
                versionCode = record.versionCode,
                result = record.result,
                sha256 = record.apkSha256,
                events = relatedEvents,
                logs = relatedLogs,
            )
        }

        // 现场运行状态：没有 adb 时靠这份判断"当时是什么状态"
        val armState = ArmRepository.get(context).state.value
        val moduleStatus = runCatching {
            com.zeeload.carappcenter.intercept.dynamic.ModuleManager.get(context).status.value
        }.getOrNull()
        val runtime = buildMap {
            put("app.version", version)
            put("app.versionCode", runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            }.getOrDefault(0L).toString())
            put("device", deviceName)
            put("android", Build.VERSION.RELEASE.orEmpty())
            put("model", "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim())
            put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            put("arm.fresh", armState.fresh().toString())
            put("arm.mode", armState.mode.name)
            put("arm.target", armState.targetPackage)
            put("arm.apk", File(armState.apk.path).name)
            put("arm.vc", armState.apk.versionCode.toString())
            put("arm.markets", armState.marketPackages.joinToString(","))
            put("arm.warnings", armState.warnings.joinToString(" | ").take(300))
            buy@ run {
                put(
                    "vpn.active",
                    com.zeeload.carappcenter.intercept.ZeeloadVpnService.activeMarkets.isNotEmpty().toString(),
                )
                put(
                    "vpn.markets",
                    com.zeeload.carappcenter.intercept.ZeeloadVpnService.activeMarkets.joinToString(","),
                )
            }
            put("module.enabled", moduleStatus?.enabled?.toString() ?: "unknown")
            put("module.id", moduleStatus?.moduleId.orEmpty())
            put("module.version", moduleStatus?.version.orEmpty())
            put("module.plugin", moduleStatus?.pluginLoaded?.toString() ?: "unknown")
            put("module.url", moduleStatus?.baseUrl.orEmpty())
            put("module.message", moduleStatus?.message.orEmpty().take(200))
            put("settings.storePackage", services.settings.storePackage)
            put("settings.logUrl", services.settings.logUrl)
            put("settings.uiScale", services.settings.uiScale.toString())
            put("settings.armTimeoutSec", services.settings.armWatchTimeoutSec.toString())
            put("settings.remotePush", services.settings.remoteEnabled.toString())
            put("db.events", events.size.toString())
            put("db.installs", installs.size.toString())
            put("db.interceptEvents", allEvents.size.toString())
        }

        return ReportInput(
            appName = runCatching { context.getString(com.zeeload.carappcenter.R.string.app_name) }
                .getOrDefault("应用助手"),
            deviceName = deviceName,
            appVersion = version,
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            model = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim(),
            generatedAt = now,
            exceptions = crashLog.recent(20),
            debugLogs = debugLogs,
            installs = installLines,
            interceptEvents = intercept,
            installDetails = detail,
            runtime = runtime,
            packages = packages,
            extra = mapOf(
                "拦截武装" to ArmRepository.get(context).state.value.let {
                    if (it.fresh()) "${it.mode.name} → ${it.targetPackage}" else "未武装"
                },
                "日志条数" to events.size.toString(),
                "已装应用" to packages.size.toString(),
            ),
        )
    }
}
