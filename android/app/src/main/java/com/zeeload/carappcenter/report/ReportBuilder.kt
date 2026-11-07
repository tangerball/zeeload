package com.zeeload.carappcenter.report

import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 生成报告所需的数据（由 App 侧采集；纯数据，便于单测）。 */
data class ReportInput(
    val deviceName: String,
    val appVersion: String,
    val androidVersion: String,
    val model: String,
    val generatedAt: Long,
    val exceptions: List<String>,
    val debugLogs: List<String>,
    val installs: List<String>,
    val interceptEvents: List<String>,
    val packages: List<String>,
    val installDetails: List<InstallDetail> = emptyList(),
    /** 运行状态快照（现场状态，方便没有 adb 时定位问题）。 */
    val runtime: Map<String, String> = emptyMap(),
    val appName: String = "应用助手",
    val extra: Map<String, String> = emptyMap(),
)

/** 一条安装记录 + 关联到的拦截事件与日志（报告里内嵌，省得自己对时间）。 */
data class InstallDetail(
    val packageName: String,
    val at: Long,
    val versionCode: Long,
    val result: String,
    val sha256: String,
    val events: List<String> = emptyList(),
    val logs: List<String> = emptyList(),
)

/**
 * 「异常 + 调试日志」报告：一个 ZIP，里面分文件放，手机上打开就能看。
 *
 * 结构：
 *   summary.md          人读摘要（先看这个）
 *   exceptions.log      未捕获异常 / 崩溃现场（最近 20 条，含堆栈）
 *   debug.log           应用内结构化调试日志（时间倒序，最多 5000 行）
 *   installs.json       安装/更新记录
 *   intercept.json      动态拦截事件
 *   packages.json       已安装应用与权限来源
 *   manifest.json       报告元信息（条数、生成时间、版本）
 */
object ReportBuilder {
    const val MAX_DEBUG_LINES = 5000
    const val MAX_EXCEPTION_LINES = 2000

    fun build(input: ReportInput): ByteArray {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            put(out, "summary.md", summary(input).toByteArray(Charsets.UTF_8))
            put(out, "exceptions.log", join(input.exceptions, MAX_EXCEPTION_LINES).toByteArray(Charsets.UTF_8))
            put(out, "debug.log", join(input.debugLogs, MAX_DEBUG_LINES).toByteArray(Charsets.UTF_8))
            put(out, "installs.json", installsJson(input).toByteArray(Charsets.UTF_8))
            put(out, "runtime.json", runtimeJson(input).toByteArray(Charsets.UTF_8))
            put(out, "intercept.json", jsonArray(input.interceptEvents).toByteArray(Charsets.UTF_8))
            put(out, "packages.json", jsonArray(input.packages).toByteArray(Charsets.UTF_8))
            put(out, "manifest.json", manifest(input).toByteArray(Charsets.UTF_8))
        }
        return zip.toByteArray()
    }

    fun fileName(input: ReportInput): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(input.generatedAt))
        val device = input.deviceName.ifBlank { "car" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(24)
        return "zeeload-report-$device-$stamp.zip"
    }

    fun manifest(input: ReportInput): String {
        val sb = StringBuilder("{\n")
        sb.append("  \"device\": ").append(quote(input.deviceName)).append(",\n")
        sb.append("  \"model\": ").append(quote(input.model)).append(",\n")
        sb.append("  \"android\": ").append(quote(input.androidVersion)).append(",\n")
        sb.append("  \"appVersion\": ").append(quote(input.appVersion)).append(",\n")
        sb.append("  \"generatedAt\": ").append(input.generatedAt).append(",\n")
        sb.append("  \"generatedAtText\": ")
            .append(quote(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(input.generatedAt))))
            .append(",\n")
        sb.append("  \"exceptionLines\": ").append(input.exceptions.size).append(",\n")
        sb.append("  \"debugLogLines\": ").append(input.debugLogs.size).append(",\n")
        sb.append("  \"installRecords\": ").append(input.installs.size).append(",\n")
        sb.append("  \"interceptEvents\": ").append(input.interceptEvents.size).append(",\n")
        sb.append("  \"packages\": ").append(input.packages.size).append(",\n")

        if (input.extra.isNotEmpty()) {
            sb.append(",\n  \"extra\": {")
            sb.append(input.extra.entries.joinToString(",") { " " + quote(it.key) + ": " + quote(it.value) })
            sb.append(" }")
        }
        sb.append("\n}\n")
        return sb.toString()
    }

    fun summary(input: ReportInput): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(input.generatedAt))
        val crashHint = if (input.exceptions.isEmpty()) "无" else "${input.exceptions.size} 段"
        return buildString {
            appendLine("# ${input.appName} 诊断报告")
            appendLine()
            appendLine("- 生成时间：$stamp")
            appendLine("- 设备：${input.deviceName.ifBlank { "车机" }}（${input.model}）")
            appendLine("- 系统：Android ${input.androidVersion}")
            appendLine("- 应用版本：${input.appVersion}")
            appendLine()
            appendLine("## 内容概览")
            appendLine()
            appendLine("| 文件 | 内容 | 条数 |")
            appendLine("| --- | --- | --- |")
            appendLine("| exceptions.log | 未捕获异常 / 崩溃现场 | ${input.exceptions.size} |")
            appendLine("| debug.log | 应用内调试日志 | ${input.debugLogs.size} |")
            appendLine("| installs.json | 安装与更新记录 | ${input.installs.size} |")
            appendLine("| intercept.json | 动态拦截事件 | ${input.interceptEvents.size} |")
            appendLine("| packages.json | 已安装应用 | ${input.packages.size} |")
            appendLine("| runtime.json | 现场运行状态（武装/VPN/模块/设置/设备） | ${input.runtime.size} |")
            appendLine()
            appendLine("## 先看哪一段")
            appendLine()
            appendLine("1. 有异常时先看 `exceptions.log` 顶部的堆栈（$crashHint）；")
            appendLine("2. 再看 `debug.log` 里异常发生前 30 行；")
            appendLine("3. 只想确认装了哪些东西 → `installs.json`。")
            appendLine()
            input.extra.forEach { (k, v) -> appendLine("- $k：$v") }
        }
    }

    private fun put(out: ZipOutputStream, name: String, bytes: ByteArray) {
        out.putNextEntry(ZipEntry(name))
        out.write(bytes)
        out.closeEntry()
    }

    private fun join(lines: List<String>, limit: Int): String =
        lines.take(limit).joinToString("\n").ifBlank { "（无）" } + "\n"

    /** 有结构化详情时输出对象数组（内嵌 events/logs），否则退回字符串数组。 */
    private fun installsJson(input: ReportInput): String {
        if (input.installDetails.isEmpty()) return jsonArray(input.installs)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            append("[\n")
            input.installDetails.forEachIndexed { index, detail ->
                if (index > 0) append(",\n")
                append("  {\n")
                append("    \"packageName\": ").append(quote(detail.packageName)).append(",\n")
                append("    \"at\": ").append(detail.at).append(",\n")
                append("    \"atText\": ").append(quote(stamp.format(Date(detail.at)))).append(",\n")
                append("    \"versionCode\": ").append(detail.versionCode).append(",\n")
                append("    \"sha256\": ").append(quote(detail.sha256)).append(",\n")
                append("    \"result\": ").append(quote(detail.result)).append(",\n")
                append("    \"events\": ").append(stringArray(detail.events)).append(",\n")
                append("    \"logs\": ").append(stringArray(detail.logs)).append("\n")
                append("  }")
            }
            append("\n]\n")
        }
    }

    private fun runtimeJson(input: ReportInput): String = buildString {
        append("[\n")
        input.runtime.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(",\n")
            append("  {\"key\": ").append(quote(key))
                .append(", \"value\": ").append(quote(value)).append("}")
        }
        append("\n]\n")
    }

    private fun stringArray(items: List<String>): String =
        "[" + items.joinToString(", ") { quote(it) } + "]"

    private fun jsonArray(items: List<String>): String =
        "[\n" + items.joinToString(",\n") { "  " + quote(it) } + "\n]\n"

    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

/** 把报告写进本地文件（下载时直接读文件，避免每次请求都重新打包）。 */
class ReportCache(private val dir: File) {
    data class Entry(
        val file: File,
        val fileName: String,
        val createdAt: Long,
        val size: Long,
        val meta: Map<String, String> = emptyMap(),
    )

    fun write(name: String, bytes: ByteArray, meta: Map<String, String> = emptyMap()): Entry {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // 只保留最近一份
        val file = File(dir, "report.bin")
        file.writeBytes(bytes)
        File(dir, "report.name").writeText(name)
        File(dir, "report.meta").writeText(meta.entries.joinToString("\n") { "${it.key}=${it.value}" })
        return Entry(file, name, System.currentTimeMillis(), file.length(), meta)
    }

    fun current(): Entry? {
        val file = File(dir, "report.bin")
        if (!file.isFile) return null
        val name = File(dir, "report.name").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val meta = File(dir, "report.meta").takeIf { it.isFile }?.readLines()
            ?.mapNotNull { line ->
                if (!line.contains('=')) return@mapNotNull null
                line.substringBefore('=') to line.substringAfter('=')
            }?.toMap().orEmpty()
        return Entry(file, name.ifBlank { "zeeload-report.zip" }, file.lastModified(), file.length(), meta)
    }
}
