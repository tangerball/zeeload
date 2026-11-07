package com.zeeload.carappcenter

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class Installer(
    private val context: Context,
    private val db: CarDatabase,
    private val cache: CacheManager,
    private val logs: Diagnostics
) {
    private val pendingPrefs = context.getSharedPreferences("install-pending", Context.MODE_PRIVATE)

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openUnknownSources() {
        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    suspend fun install(file: File, apk: ApkInfo, url: String, sha256: String): Int {
        check(canInstall()) { "请先允许此应用安装未知来源应用" }
        check(file.isFile && file.canRead() && file.length() > 0) { "安装包不存在或不可读取" }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(apk.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= 31) {
                    val policy = context.getSystemService(DevicePolicyManager::class.java)
                    val managed = policy?.isDeviceOwnerApp(context.packageName) == true
                    setRequireUserAction(
                        if (managed) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    )
                }
            }
        val packageInstaller = context.packageManager.packageInstaller
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        cache.pin(file)
        var committed = false
        try {
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    input.copyTo(output, 128 * 1024)
                    session.fsync(output)
                }
            }
            val pending = JSONObject()
                .put("packageName", apk.packageName).put("name", apk.name)
                .put("versionCode", apk.versionCode).put("versionName", apk.versionName)
                .put("certificateSha256", apk.certificateSha256).put("apkSha256", sha256)
                .put("url", url).put("path", file.absolutePath)
                .put("at", System.currentTimeMillis())
            check(pendingPrefs.edit().putString(sessionId.toString(), pending.toString()).commit()) {
                "保存安装会话失败"
            }
            val intent = Intent(context, InstallReceiver::class.java)
                .setPackage(context.packageName)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(callback.intentSender)
            committed = true
            logs.log("install", "安装会话已提交", apk.packageName, url, apk.versionCode)
            return sessionId
        } catch (error: Exception) {
            if (!committed) {
                runCatching { session.abandon() }
                pendingPrefs.edit().remove(sessionId.toString()).apply()
                cache.unpin(file)
                cache.trim()
            }
            db.apps().record(
                InstallRecord(packageName = apk.packageName, at = System.currentTimeMillis(),
                    versionCode = apk.versionCode, result = error.message ?: "安装会话失败", apkSha256 = sha256)
            )
            logs.log("install", error.message ?: "安装会话失败", apk.packageName, url,
                apk.versionCode, errorCode = error.javaClass.simpleName, level = "ERROR")
            throw error
        } finally {
            session.close()
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(
            PackageInstaller.EXTRA_SESSION_ID,
            intent.getIntExtra("sessionId", -1)
        )
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirmation != null) {
                runCatching { context.startActivity(confirmation) }
                    .onFailure { Log.e(TAG, "打开安装确认界面失败", it) }
            } else {
                Log.e(TAG, "PackageInstaller 未返回安装确认 Intent，sessionId=$sessionId")
                finishSession(context, sessionId, "系统未提供安装确认界面")
            }
            return
        }
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                processResult(context, intent, sessionId, status)
            } catch (error: Exception) {
                Log.e(TAG, "处理安装结果失败，sessionId=$sessionId", error)
                val app = context.applicationContext as? CarApp
                app?.services?.logs?.log(
                    "install-result",
                    error.message ?: "处理安装结果失败",
                    errorCode = error.javaClass.simpleName,
                    level = "ERROR"
                )
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun processResult(
        context: Context,
        intent: Intent,
        sessionId: Int,
        status: Int
    ) {
        val app = context.applicationContext as? CarApp
            ?: error("应用上下文类型错误")
        val services = app.services
        val prefs = context.getSharedPreferences("install-pending", Context.MODE_PRIVATE)
        val text = prefs.getString(sessionId.toString(), null)
            ?: error("找不到安装会话记录：$sessionId")
        val pending = JSONObject(text)
        val name = pending.getString("packageName")
        val url = pending.getString("url")
        val path = File(pending.getString("path"))
        val version = pending.getLong("versionCode")
        val sha = pending.getString("apkSha256")
        val success = status == PackageInstaller.STATUS_SUCCESS
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?: if (success) "安装成功" else "安装失败（状态码 $status）"
        val old = services.db.apps().get(name)
        val installerPkg = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(name).installingPackageName.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(name).orEmpty()
            }
        }.getOrDefault("")
        val resultText = "$message · via=direct · installer=${installerPkg.ifBlank { context.packageName }}"
        try {
            if (success) {
                val actual = services.packages.find(name)
                services.db.apps().put(
                    ManagedApp(
                        packageName = name,
                        name = actual?.name ?: pending.getString("name"),
                        url = url,
                        origin = old?.origin ?: "本应用安装",
                        versionCode = actual?.versionCode ?: version,
                        versionName = actual?.versionName ?: pending.getString("versionName"),
                        certificateSha256 = actual?.certificateSha256
                            ?: pending.getString("certificateSha256"),
                        apkSha256 = sha,
                        installedAt = old?.installedAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        lastResult = resultText
                    )
                )
                path.delete()
            } else if (old != null) {
                services.db.apps().put(old.copy(lastResult = resultText))
            }
            services.db.apps().record(
                InstallRecord(packageName = name, at = System.currentTimeMillis(),
                    versionCode = version, result = resultText, apkSha256 = sha)
            )
            services.logs.log("install-result", resultText, name, url, version,
                errorCode = status.toString(), level = if (success) "INFO" else "ERROR")
        } finally {
            services.cache.unpin(path)
            services.cache.trim()
            prefs.edit().remove(sessionId.toString()).apply()
        }
    }

    private fun finishSession(context: Context, sessionId: Int, message: String) {
        if (sessionId < 0) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? CarApp ?: return@launch
                val services = app.services
                val prefs = context.getSharedPreferences("install-pending", Context.MODE_PRIVATE)
                val text = prefs.getString(sessionId.toString(), null) ?: return@launch
                val pending = JSONObject(text)
                val path = File(pending.getString("path"))
                val packageName = pending.getString("packageName")
                val version = pending.getLong("versionCode")
                val sha = pending.getString("apkSha256")
                try {
                    services.db.apps().record(
                        InstallRecord(
                            packageName = packageName,
                            at = System.currentTimeMillis(),
                            versionCode = version,
                            result = message,
                            apkSha256 = sha
                        )
                    )
                    services.logs.log("install-result", message, packageName,
                        pending.getString("url"), version, errorCode = "NO_CONFIRMATION", level = "ERROR")
                } finally {
                    services.cache.unpin(path)
                    services.cache.trim()
                    prefs.edit().remove(sessionId.toString()).apply()
                }
            } catch (error: Exception) {
                Log.e(TAG, "记录安装确认失败", error)
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ZEEloadInstaller"
    }
}
