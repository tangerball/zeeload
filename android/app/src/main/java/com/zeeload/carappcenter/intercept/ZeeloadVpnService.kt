package com.zeeload.carappcenter.intercept

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.zeeload.carappcenter.intercept.net.TunEngine

/**
 * 车机端拦截入口（不需要 root / 不需要 adb）：
 *
 *  · 用 VpnService 的 per-app 能力，只把「用户选中的应用市场」的流量接进来
 *    —— 这就是「动态选择市场 app」在系统层面唯一的合法实现方式；
 *  · 进来的流量在车机本地做 TLS 解密（叶子证书由车机本来就信任的中间 CA 现签）
 *    + HTTP 改写：把某个应用改成「有新版本」，下载响应换成要装的 APK；
 *  · 安装仍由商店自己发起 → installerPackageName = 商店包名，权限按官方渠道发放。
 */
class ZeeloadVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.zeeload.intercept.START"
        const val ACTION_STOP = "com.zeeload.intercept.STOP"
        const val EXTRA_MARKETS = "markets"
        private const val CHANNEL_ID = "zeeload-intercept"
        private const val NOTIFICATION_ID = 0x5A17
        private const val TUN_ADDRESS = "10.111.0.1"
        private const val DNS = "223.5.5.5"

        @Volatile var lastError: String? = null
            private set

        @Volatile var activeMarkets: List<String> = emptyList()
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private var engine: TunEngine? = null
    private val rules by lazy { ArmRepository.get(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val markets = intent?.getStringArrayListExtra(EXTRA_MARKETS)
                    ?: rules.state.value.marketPackages.toList()
                startTunnel(markets.ifEmpty { rules.state.value.marketPackages.toList() })
            }
        }
        return START_STICKY
    }

    private fun startTunnel(markets: List<String>) {
        stopTunnel()
        if (markets.isEmpty()) {
            lastError = "还没有选择要拦截的应用市场"
            rules.onEvent("error", lastError!!)
            stopSelf()
            return
        }
        activeMarkets = markets
        val builder = Builder().apply {
            setSession("ZEEload 动态拦截")
            setMtu(1500)
            addAddress(TUN_ADDRESS, 32)
            addRoute("0.0.0.0", 0)
            addDnsServer(DNS)
            markets.forEach { pkg ->
                runCatching { addAllowedApplication(pkg) }.onFailure {
                    rules.onEvent("error", "无法拦截 $pkg：${it.message}")
                }
            }
        }
        val fd = runCatching { builder.establish() }.getOrElse { e ->
            lastError = "建立 VPN 失败：${e.message}"
            rules.onEvent("error", lastError!!)
            stopSelf()
            return
        }
        if (fd == null) {
            lastError = "系统未授权建立 VPN（需要在界面上点一次「允许」）"
            rules.onEvent("error", lastError!!)
            stopSelf()
            return
        }
        tun = fd
        startForegroundSafely(markets)
        val ca = loadCa()
        if (ca == null) {
            rules.onEvent("warn", "未放入 CA 材料（assets/ca/*.pem）：只能做明文转发，无法解密与改写")
        }
        engine = TunEngine(fd, rules, rules, ca, log = { msg -> rules.onEvent("net", msg) }).also { it.start() }
        rules.onEvent("vpn", "开始拦截：${markets.joinToString()}")
    }

    private fun stopTunnel() {
        engine?.stop()
        engine = null
        runCatching { tun?.close() }
        tun = null
        activeMarkets = emptyList()
        runCatching { stopForeground(true) }
    }

    override fun onRevoke() {
        rules.onEvent("vpn", "系统撤销了 VPN 授权，拦截已停止")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun loadCa(): CaMaterial? {
        return runCatching {
            val certBlob = assets.open("ca/zeeload-int.pem").use { it.readBytes() }
            val keyPem = assets.open("ca/zeeload-int.key").use { it.readBytes().toString(Charsets.US_ASCII) }
            CaMaterial.load(certBlob, keyPem)
        }.getOrElse { e ->
            rules.onEvent("error", "读取 CA 材料失败：${e.message}")
            null
        }
    }

    private fun startForegroundSafely(markets: List<String>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ZEEload 拦截", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = Intent(this, com.zeeload.carappcenter.InterceptActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ZEEload 正在拦截应用市场")
            .setContentText("目标：${markets.joinToString()}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification) }
    }
}
