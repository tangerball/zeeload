package com.zeeload.carappcenter

import android.content.Context
import java.util.UUID

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var cacheLimitMb: Int
        get() = prefs.getInt("cacheLimitMb", 512)
        set(value) { prefs.edit().putInt("cacheLimitMb", value.coerceIn(64, 2048)).apply() }

    var logUrl: String
        get() = prefs.getString("logUrl", "") ?: ""
        set(value) { prefs.edit().putString("logUrl", value.trim()).apply() }

    var remoteEnabled: Boolean
        get() = prefs.getBoolean("remoteEnabled", false)
        set(value) { prefs.edit().putBoolean("remoteEnabled", value).apply() }

    var debugUntil: Long
        get() = prefs.getLong("debugUntil", 0)
        set(value) { prefs.edit().putLong("debugUntil", value).apply() }

    /** 车机字号档位（1.0 标准 / 1.15 大 / 1.30 特大 / 1.45 超大） */
    var uiScale: Float
        get() = prefs.getFloat("uiScale", 1.15f)
        set(value) { prefs.edit().putFloat("uiScale", value.coerceIn(1.0f, 1.45f)).apply() }

    /** 拦截武装后等待商店安装的超时（秒），超时自动解除武装。 */
    var armWatchTimeoutSec: Int
        get() = prefs.getInt("armWatchTimeoutSec", 600)
        set(value) { prefs.edit().putInt("armWatchTimeoutSec", value.coerceIn(30, 1800)).apply() }

    /** 拦截事件保留条数上限（200–20000）。 */
    var interceptEventLimit: Int
        get() = prefs.getInt("interceptEventLimit", 2000)
        set(value) { prefs.edit().putInt("interceptEventLimit", value.coerceIn(200, 20000)).apply() }

    /** 用户自己挑的"打开商店"目标（车机换市场包时改这个即可，不用改代码）。 */
    var storePackage: String
        get() = prefs.getString("storePackage", "").orEmpty()
        set(value) { prefs.edit().putString("storePackage", value).apply() }

    var lastUpload: String
        get() = prefs.getString("lastUpload", "尚未上传") ?: "尚未上传"
        set(value) { prefs.edit().putString("lastUpload", value).apply() }

    val deviceId: String
        get() {
            val old = prefs.getString("deviceId", null)
            if (old != null) return old
            return UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
        }
}
