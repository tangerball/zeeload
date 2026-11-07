package com.zeeload.carappcenter.report

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常收集器：装到 `Thread.setDefaultUncaughtExceptionHandler` 上，
 * 把崩溃现场（主线程堆栈、全部线程、异常链）落盘，供「报告」带走。
 *
 * 只保留最近 [MAX_FILES] 条，避免长期跑把存储写满。
 */
class CrashLog(private val dir: File) {

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun record(thread: Thread, throwable: Throwable) {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        val text = buildString {
            appendLine("==== ZEEload 崩溃记录 ====")
            appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("线程：${thread.name}（id=${thread.id}，priority=${thread.priority}）")
            appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine("---- 异常链 ----")
            appendLine(stackTraceOf(throwable))
            appendLine("---- 崩溃线程堆栈 ----")
            thread.stackTrace.forEach { appendLine("    at $it") }
            appendLine()
            appendLine("---- 全部线程（截断）----")
            Thread.getAllStackTraces().entries
                .sortedBy { it.key.name }
                .take(40)
                .forEach { (t, frames) ->
                    appendLine("[${t.name}] state=${t.state}")
                    frames.take(25).forEach { appendLine("    at $it") }
                }
        }
        file.writeText(text)
        trim()
    }

    /** 已有崩溃文件 + 最近一次现场，供报告打包。 */
    fun recent(limit: Int = 20): List<String> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }?.take(limit) ?: emptyList()
        return files.mapNotNull { f -> runCatching { f.readText() }.getOrNull() }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        return sw.toString()
    }

    companion object {
        const val MAX_FILES = 20
    }
}
