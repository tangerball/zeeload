package com.zeeload.carappcenter

import android.content.Context

/** 拦截相关设置的轻量读取入口（给 InterceptActivity 与设置页共用）。 */
object ArmSettings {
    fun timeoutSec(context: Context): Int =
        (context.applicationContext as? CarApp)?.services?.settings?.armWatchTimeoutSec ?: 600
}
