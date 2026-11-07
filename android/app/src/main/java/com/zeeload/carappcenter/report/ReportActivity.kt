package com.zeeload.carappcenter.report

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeload.carappcenter.ui.CarPalette
import com.zeeload.carappcenter.ui.GhostButton
import com.zeeload.carappcenter.ui.PrimaryButton
import com.zeeload.carappcenter.CarApp
import com.zeeload.carappcenter.Services
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 车机上的「在手机上取得报告」页：整块深色面板 + 二维码 + 验证码 + 备用地址。
 * 手机扫码后由 [ReportServer] 把 ZIP 报告直接发给手机。
 */
class ReportActivity : ComponentActivity() {
    private lateinit var services: Services
    private lateinit var collector: ReportCollector
    private lateinit var cache: ReportCache
    private var server: ReportServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        services = (application as CarApp).services
        collector = ReportCollector(this, services)
        cache = ReportCache(File(cacheDir, "report"))
        val srv = ReportServer(cache, ReportServer.DEFAULT_PORT)
        server = srv
        val html = runCatching {
            assets.open("report/landing.html").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrDefault("<h1>报告页面缺失</h1>")

        setContent {
            val scale = runCatching {
                com.zeeload.carappcenter.ui.UiScale.of((application as CarApp).services.settings.uiScale)
            }.getOrDefault(com.zeeload.carappcenter.ui.UiScale.Large)
            com.zeeload.carappcenter.ui.CarTheme(scale) {
                Surface(Modifier.fillMaxSize(), color = CarPalette.bg) {
                    ReportScreen(
                        server = srv,
                        onRegenerate = { regenerate(srv, html) },
                        onSwitchPort = { switchPort(srv, html) },
                        onBack = { finish() },
                    )
                }
            }
        }
        regenerate(srv, html)
    }

    private fun regenerate(srv: ReportServer, html: String) {
        Thread {
            runCatching {
                val input = kotlinx.coroutines.runBlocking { collector.collect() }
                val bytes = ReportBuilder.build(input)
                cache.write(
                    ReportBuilder.fileName(input),
                    bytes,
                    mapOf(
                        "device" to input.deviceName,
                        "exceptions" to input.exceptions.size.toString(),
                        "logs" to input.debugLogs.size.toString(),
                        "version" to input.appVersion,
                    ),
                )
                runOnUiThread { srv.start(html) }
            }.onFailure { e ->
                runOnUiThread { srv.markError("生成报告失败：${e.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 手动换端口：从当前端口下一个开始重新尝试（8888 被占用时也能一键换）。 */
    private fun switchPort(srv: ReportServer, html: String) {
        val next = srv.state.value.port + 1
        srv.stop()
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking { collector.collect() }
                runOnUiThread { srv.start(html, fromPort = next) }
            }.onFailure { runOnUiThread { srv.markError("换端口失败：${it.message}") } }
        }.apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }
}

@Composable
private fun ReportScreen(
    server: ReportServer,
    onRegenerate: () -> Unit,
    onSwitchPort: () -> Unit,
    onBack: () -> Unit,
) {
    val state by server.state.collectAsState()
    val qr = remember(state.urls.firstOrNull(), state.code) {
        state.urls.firstOrNull()?.let { url -> runCatching { QrCode.encode(url) }.getOrNull() }
    }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 18.dp)) {
        com.zeeload.carappcenter.ui.BackBar(
            title = "取得报告（扫码）",
            subtitle = "异常与调试日志打包成 ZIP，只在本机局域网内传输；手机扫码即可取走。",
        ) { onBack() }

        Row(
            Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 左：说明 + 验证码 + 备用地址（内容多时可滚动，状态条固定在底部）
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "用手机相机扫码，在浏览器中打开此页面，然后选择「下载报告」。下载完成前请保持此画面开启。",
                    color = Color(0xFFD7E1EC), fontSize = 17.sp, lineHeight = 26.sp,
                )

                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CarPalette.card)
                        .padding(14.dp),
                ) {
                    Column {
                        Text("没有相机？在浏览器中打开", color = CarPalette.dim, fontSize = 15.sp)
                        Text(
                            "http://${primaryHost(state.urls)}:${state.port}",
                            color = CarPalette.info, fontSize = 19.sp, fontFamily = FontFamily.Monospace,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Text("验证码", color = CarPalette.dim, fontSize = 15.sp)
                            Text(
                                ReportServer.formatCode(state.code).ifBlank { "— — —" },
                                color = CarPalette.accentSoft, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }

                if (state.urls.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("若扫码无法打开，请尝试将浏览器中的 IP 地址改为：", color = CarPalette.dim, fontSize = 14.sp)
                        state.urls.drop(1).take(7).forEach { url ->
                            Text(
                                "· " + url.substringAfter("//").substringBefore(":"),
                                color = CarPalette.dimmer, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }


            }

            // 右：二维码
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(272.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (qr != null) {
                        QrCanvas(qr)
                    } else {
                        CircularProgressIndicator(color = CarPalette.bg)
                    }
                }
                Text(
                    if (state.running) "扫码即可在手机上打开" else "服务未启动",
                    color = CarPalette.dimmer, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton("重新生成报告") {
                busy = true
                onRegenerate()
                busy = false
            }
            GhostButton("换一个端口") { onSwitchPort() }
            if (state.portSwitched) {
                Text(
                    "端口 ${state.requestedPort} 被占用，已改用 ${state.port}",
                    color = CarPalette.warn, fontSize = 14.sp,
                )
            }
        }

        StatusBar(state)
    }
    if (busy) { /* 保持编译期引用 */ }
    scope.hashCode()
}

@Composable
private fun QrCanvas(matrix: QrMatrix) {
    val quiet = 2
    Canvas(Modifier.fillMaxSize()) {
        val total = matrix.size + quiet * 2
        val module = size.minDimension / total
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.get(x, y)) continue
                drawRect(
                    color = CarPalette.bg,
                    topLeft = Offset((x + quiet) * module, (y + quiet) * module),
                    size = Size(module + 0.5f, module + 0.5f),
                )
            }
        }
    }
}

@Composable
private fun StatusBar(state: ReportServer.ServerState) {
    val (text, color) = when {
        !state.running -> "等待启动…" to CarPalette.dim
        state.done -> "下载完成（${ReportServer.formatBytes(state.bytesSent)}）" to CarPalette.accentSoft
        state.downloading -> "正在下载 ${percent(state)}…" to CarPalette.info
        state.aborted -> "下载中断（${ReportServer.formatBytes(state.bytesSent)}），请让手机重新点下载" to CarPalette.warn
        state.pageOpened -> "手机已连接（${state.clientIp}），等待其点「下载报告」" to CarPalette.info
        else -> "等待手机扫码" to CarPalette.dim
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CarPalette.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (state.totalBytes > 0) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.height(8.dp).fillMaxWidth().weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CarPalette.bg),
                ) {
                    Box(
                        Modifier.fillMaxWidth(percent(state) / 100f).height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(CarPalette.accent),
                    )
                }
                Text(
                    "  " + percent(state) + "%",
                    color = CarPalette.dim, fontSize = 14.sp,
                )
            }
        }
        if (state.lastError.isNotBlank()) {
            Text(state.lastError, color = CarPalette.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (state.running && state.message.isNotBlank()) {
        Text(
            state.message,
            color = CarPalette.dimmer, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun percent(state: ReportServer.ServerState): Int {
    if (state.totalBytes <= 0) return 0
    return ((state.bytesSent * 100) / state.totalBytes).toInt().coerceIn(0, 100)
}

private fun primaryHost(urls: List<String>): String {
    val url = urls.firstOrNull() ?: return "127.0.0.1"
    return url.substringAfter("//").substringBefore(":")
}
