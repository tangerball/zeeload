package com.zeeload.carappcenter.intercept.dynamic

import java.io.File

/**
 * 从局域网取模块并校验落盘。不做 Android 相关调用（fetcher 注入），因此可完整单测。
 *
 * 目录结构：
 *   <cacheRoot>/modules/<id>/<version>/manifest.json | plugin.dex | rules.json
 *   <cacheRoot>/modules/active             ← "id|version" 指针（原子切换）
 */
class ModuleRepository(
    private val cacheRoot: File,
    private val verifier: ModuleVerifier,
    private val fetch: (String) -> ByteArray,
    private val log: (String) -> Unit = {},
) {
    data class Installed(
        val manifest: ModuleManifest,
        val rules: RewriteRules,
        val dir: File,
    ) {
        val dexPath: File get() = File(dir, manifest.dexFile)
        val rulesPath: File get() = File(dir, manifest.rulesFile)
    }

    fun install(baseUrl: String): Installed {
        val base = baseUrl.trimEnd('/')
        log("拉取模块清单：$base/index.json")
        val manifestText = String(fetch("$base/index.json"), Charsets.UTF_8)
        val manifest = ModuleManifest.fromJson(manifestText)
            ?: throw IllegalArgumentException("清单格式不对（需要 id + entry）")

        val dex = fetch("$base/${manifest.dexFile}")
        val rulesBytes = runCatching { fetch("$base/${manifest.rulesFile}") }.getOrNull()
        val result = verifier.verify(manifest, dex, rulesBytes)
        if (!result.ok) throw SecurityException("模块校验失败：" + result.problems.joinToString("；"))

        val dir = File(cacheRoot, "modules/${manifest.id}/${manifest.version}")
        val tmp = File(cacheRoot, "modules/${manifest.id}/.${manifest.version}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        File(tmp, "manifest.json").writeText(manifestText)
        val dexFile = File(tmp, manifest.dexFile)
        dexFile.writeBytes(dex)
        // ART 拒绝加载「可写」的 dex（Attempt to load writable dex file）——落盘后立刻置为只读
        dexFile.setWritable(false, false)
        dexFile.setReadable(true, false)
        rulesBytes?.let { File(tmp, manifest.rulesFile).writeBytes(it) }
        dir.deleteRecursively()
        dir.parentFile?.mkdirs()
        if (!tmp.renameTo(dir)) {
            tmp.copyRecursively(dir, overwrite = true)
            tmp.deleteRecursively()
        }
        writeActive(manifest.id, manifest.version)

        val rules = rulesBytes?.let { RewriteRules.fromJson(String(it, Charsets.UTF_8)) } ?: RewriteRules.default()
        log("模块已安装：${manifest.id}@${manifest.version}（规则 v${rules.version}）")
        return Installed(manifest, rules, dir)
    }

    fun current(): Installed? {
        val pointer = File(cacheRoot, "modules/active")
        if (!pointer.isFile) return null
        val parts = pointer.readText().trim().split('|')
        if (parts.size != 2) return null
        return load(parts[0], parts[1])
    }

    fun load(id: String, version: String): Installed? {
        val dir = File(cacheRoot, "modules/$id/$version")
        if (!dir.isDirectory) return null
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return null
        val manifest = ModuleManifest.fromJson(manifestFile.readText()) ?: return null
        val rulesFile = File(dir, manifest.rulesFile)
        val rules = if (rulesFile.isFile) RewriteRules.fromJson(rulesFile.readText()) else RewriteRules.default()
        val dex = File(dir, manifest.dexFile)
        if (dex.isFile && dex.canWrite()) dex.setWritable(false, false)
        return Installed(manifest, rules, dir)
    }

    fun listInstalled(): List<Pair<String, String>> {
        val modulesRoot = File(cacheRoot, "modules")
        if (!modulesRoot.isDirectory) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        modulesRoot.listFiles()?.filter { it.isDirectory && it.name != "active" }?.forEach { idDir ->
            idDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { vDir ->
                if (File(vDir, "manifest.json").isFile) out.add(idDir.name to vDir.name)
            }
        }
        return out.sortedBy { "${it.first}@${it.second}" }
    }

    fun activate(id: String, version: String): Installed? {
        val installed = load(id, version) ?: return null
        writeActive(id, version)
        return installed
    }

    private fun writeActive(id: String, version: String) {
        val pointer = File(cacheRoot, "modules/active")
        pointer.parentFile?.mkdirs()
        val tmp = File(pointer.parentFile, "active.tmp")
        tmp.writeText("$id|$version")
        if (!tmp.renameTo(pointer)) {
            pointer.writeText("$id|$version")
            tmp.delete()
        }
    }
}
