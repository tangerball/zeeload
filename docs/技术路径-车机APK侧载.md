# ZEEload · 车机 APK 侧载技术路径（通用版）

> ⚠️ **适用范围**：本项目**仅适用于自行搭建的车机平台**（自建热点 + 模拟车机 + 模拟商店响应），**仅是系统安全防护思路**的研究记录，**不得用于任何生产环境**，也不针对任何真实厂商设备与线上服务。
>
> **研究对象**：某厂商车机「应用商店」的更新 / 安装链路
> **复现环境**：自建模拟环境（本机代理网关 + 模拟车机 + 模拟商店响应）
> **约定**：文中厂商一律写作「某厂商」，域名、包名、App 名称均为占位符（`example.com`、`com.example.*`），不指代任何具体在用设备；文中出现的字节数、哈希、时间戳均为模拟数据，仅用于说明流程。

---

## 1. 一句话结论

车机应用商店把「安装第三方 APK」这件事拆成了两段校验：

- **传输层**依赖一条已被证明不可信的公共 CA 证书链 → 网关可以解密并改写商店 HTTPS；
- **应用层**的「更新安装」路径不校验服务端签名 → 改写后的第三方 APK 能以「更新」名义被安装。

两段各自都不致命，组合起来就构成了「无需 root / 无需 adb / 无需证书，把任意 APK 装进车机」的完整路径。

## 2. 前提与边界

| 项目 | 说明 |
|---|---|
| 目标 | Android 车机的系统级应用商店（随车机 OTA 预装，非 Google Play） |
| 车机侧 | 无需 root、无需 adb、无需安装用户证书、无需打开「未知来源」 |
| 电脑侧 | 提供热点 / 互联网共享，并能透明转发车机的 443 流量（macOS 用 pf，Windows 用 WinDivert） |
| 前提 A | 车机信任库仍包含某公共 CA 的中间证书（该中间 CA 私钥于 2026-10 被公开披露可恢复） |
| 前提 B | 商店「更新已安装应用」路径对服务端签名（`sign`）不做强制校验 |
| 研究性质 | 学术研究与技术探讨；全部复现在**自建模拟环境**与研究者自有设备上完成 |

## 3. 网络拓扑

```text
        ┌────────────┐    Wi-Fi    ┌──────────────────────────────┐
        │  目标车机   │ ──────────> │ 电脑（热点 / 互联网共享）      │
        │  应用商店   │             │  ├─ pf 或 WinDivert 透明转发   │
        └────────────┘             │  ├─ 本地 SNI 分流器            │
                                   │  └─ mitmproxy（解密 + 注入）   │
                                   └──────────────┬───────────────┘
                                                  │ 正常出网
                                                  ▼
                                        商店 API / 下载 CDN
```

透明转发只作用于**热点侧**（车机 → 外网），不改系统路由、不碰电脑自身的上游连接。

## 4. 技术路径

### 4.1 透明拦截与解密

- 车机到商店域名的 443 流量被重定向到本机代理；
- mitmproxy 以「泄露的中间 CA + 其根证书链」签发叶证书，车机信任该链，因此握手通过；
- 典型链：`leaf ← <中间 CA> ← <根 CA>`（另有交叉签名路径用于老信任库）；
- 结果：商店 API 与下载 CDN 的明文请求/响应对所有被劫持连接可见。

要点：**不需要在车机安装任何证书**，证书链本身就落在车机已有的信任集里。

### 4.2 商店下载链路

```text
POST /app-market/clientApi/v2/app/appList
     body: {"appPackageNames": [ ...已安装包名... ], ...}
     → data.list[*] 每条含 apkPackageName / apkVersion / apkVersionName /
                     apkSize / apkUrl / sha256 / apkMd5 / apkSign

POST /app-market/clientApi/v2/app/signature
     body: {"apkSha256": "...", "apkVersion": ..., "packageName": "...", ...}
     → {"data": {"sign": "<base64>", "signType": 0}}      # 官方哈希才有 sign

GET  https://cdn.example.com/app-market/<id>/<md5>.apk
     Range: bytes=0-0                → 206，Content-Range 给出总大小（探测）
     Range: bytes=0-<total-1>        → 206，完整内容
```

观察到的顺序：

1. 商店**下载前**就用元数据里的 `sha256` 去申请签名；
2. 下载后再用**同一份元数据**校验内容哈希；
3. 服务端对非官方哈希返回 `{"sign": null}`；
4. 商店对 `sign: null` 不拦截，继续下载并进入安装流程。

### 4.3 更新注入（步骤一）

「检查更新 / 我的」会用 `appPackageNames` 拉已安装应用的元数据。把命中条目改成：

```json
{
  "apkVersion": 999999999,
  "apkVersionName": "9.9.9",
  "sha256": "<目标 APK 的 sha256>",
  "apkMd5": "<目标 APK 的 md5>",
  "apkSize": <目标 APK 大小>,
  "apkSign": "sign"
}
```

其余字段（`apkUrl`、图标、名称等）保持原样，商店即认为这些应用「有可用更新」。

### 4.4 下载内容替换（步骤二）

商店开始下载该更新时，代理在响应头阶段接管 `.apk` 响应：

- 解析客户端 `Range`，返回 `206` + `Content-Range: bytes <start>-<end>/<目标大小>`；
- 用本地 APK 的对应切片作为响应体，`Content-Length` 同步改写；
- 分块流式发送，避免客户端超时。

**安全闸门**：只有当商店「用目标 APK 的哈希取过签名」（说明元数据改写已被采纳）时才替换下载；否则按官方内容原样透传，避免无谓失败。

### 4.45 动态拦截与「安装者身份」

到 4.4 为止，链路是静态的：启动时定死一个注入目标与一个替换包。实际使用里更需要的是
**运行期动态决定**——先观察商店认识哪些应用，再挑一个当「更新外壳」，并且随时换包。

控制面（`addons/store_log.py` 的 `LiveArm` + `ControlServer`）把三件事搬到运行期：

| 运行期状态 | 作用 |
|---|---|
| 注入目标包 `pkg` | 商店 appList / 详情页里哪一条被改写成「有新版本」；`ALL` = 任意条目 |
| 替换包 `data/sha256/md5/size` | 下载响应体、元数据哈希、签名请求三者同源，保证商店自洽 |
| TTL | 武装有效期，过期自动放行原样流量 |

要点：**改动只发生在网络上**，安装动作、安装会话、安装完成广播仍由商店自己发起，因此
`PackageManager` 记录的 `installerPackageName` 是商店包名。这解释了为什么「装得上但没权限」
的应用，走商店链路后权限就跟着来了——系统按安装者身份决定是否按官方应用对待。

权限若仍然没到位，只有两种可能：

1. 系统在安装完成回调里按**元数据包名**发权限 → 权限落在作为外壳的那款应用上。
   对策：注入目标改成目标 APK 自己的包名（该应用需已安装，走「更新已装应用」分支）。
2. 系统按**服务端下发的白名单**（包名+签名+版本）发权限，与安装者无关。
   这种情况网络层改写拿不到，属于服务端策略。

验证脚本：`bash scripts/check-installer.sh <包名>`，看 `installerPackageName` 与
`runtime permissions` / `cmd appops get` 的落地情况。

### 4.46 只升级不可重装：为什么元数据版本必须等于 APK 内部版本

「同一款应用第二次以后只能升级」是很常见的车机策略：包名 + 签名一致、`versionCode` 递增。
在这条策略下，网络层改写要额外守一条一致性：

```text
商店判断「有没有新版」 → 看元数据 apkVersion
安装器判断「能不能装」 → 看下载到的 APK 里的 android:versionCode
两者不一致 → 商店弹出更新，安装器直接报「版本未提高」
```

所以升级模式（`mode=update`）不能再用哈希派生的假版本号，必须把元数据写成 APK 自己的
`versionCode / versionName`；同时武装阶段就要拒绝「版本没有提高」和「签名与已安装不一致」的组合，
否则到安装那一刻才失败，白白跑一遍 25 MB 下载。

`addons/apkmeta.py` 负责读这三件事（纯 Python，无 aapt 依赖）：

| 字段 | 来源 |
|---|---|
| `package` / `versionCode` / `versionName` | `AndroidManifest.xml` 二进制 AXML 解析（字符串池 + RES_XML 元素） |
| `signer_sha256` | APK Signing Block（v2/v3）里首个 signer 的证书 DER，sha256；v1 走 `META-INF/*.RSA` + openssl |

与官方工具对齐：`apkanalyzer manifest application-id/version-code` 与
`apksigner verify --print-certs` 的 SHA-256 摘要，和本实现的输出一致。

### 4.47 多应用市场：按「谁在发应用数据」识别目标

不同车机（甚至同一台车的不同版本）会挂不同商店：厂商市场、应用中心、第三方市场，域名与路径各异。
把域名写死会在协议更换时直接失效，因此识别方式是**内容特征**而不是域名：
响应是小 JSON 且含 `apkPackageName` → 判定为应用市场接口 → 该域名自动纳入拦截范围，
可在控制台单个开关；停止开关后该域名流量完全不改写。

注入匹配同样不依赖具体路径（遍历 JSON 里的应用条目），下载替换在「注入后、TTL 内、首个非 JSON 响应」
时兜底，用于商店把下载地址放在未知字段名的情况下。

### 4.49 排查：脚本启动即报「没有可用的互联网共享 rdr-anchor」

**现象**：`transparent-test.sh` 在环境预检阶段退出 1，输出 `pf 规则集没有可用的互联网共享 rdr-anchor`。

**原因**：脚本刻意不改 `/etc/pf.conf` 和主规则集，只把规则挂到 macOS 互联网共享创建的父 anchor 里
（`rdr-anchor "com.apple.internet-sharing"`）。互联网共享**没有真正运行时**，主 nat 规则集里就没有这一行，
脚本无处挂接。注意 `bridge100` 上的 `192.168.2.1` 可能还在（接口残留），但：

| 判据 | 共享在跑 | 共享没跑 |
|---|---|---|
| `sysctl -n net.inet.ip.forwarding` | `1` | `0` |
| `sudo pfctl -s nat \| grep rdr-anchor` | 有 `com.apple.internet-sharing` | 没有 |
| 车机能否上网 | 能 | 不能（没有 NAT） |

**修法**：

```bash
# a) GUI：系统设置 → 通用 → 共享 → 互联网共享，关一次再打开
#    「共享以下来源的连接」选 iPhone USB / Wi-Fi；「用以下端口共享给电脑」勾选 Thunderbolt Bridge
# b) CLI：
sudo launchctl kickstart -k system/com.apple.InternetSharing

# 自检
sysctl -n net.inet.ip.forwarding        # 期望 1
sudo pfctl -s nat | grep rdr-anchor     # 期望出现 com.apple.internet-sharing
```

脚本现在会在失败分支自动打印这三项判据与修法（`diag_sharing`），`--check-only` 在非 root 下也会打印。

## 4.48 「伪装成应用市场」可行性（实测，Android 15 / API 35）

结论：**普通应用无法把安装归属写成别的包名**，因此"伪装成商店去装"这条路在应用层是堵死的；
能拿到商店身份的只有"让商店自己发安装"这一条。

#### 4.48.1 机制

`installerPackageName` 不是应用可自由填写的字符串，而是 `PackageInstallerService` 在
`createSession` 时按**调用者 uid** 归属的：

```text
PackageInstaller.SessionParams.setInstallerPackageName("其他包名")   → 只是"申请"，不校验
PackageInstaller.createSession(params)                              → 系统校验，失败即 SecurityException
```

放行条件只有两种：目标 installer 包**属于调用者 uid**，或调用者持有 `INSTALL_PACKAGES`
（`signature|privileged`，普通应用拿不到；`device owner` 也不带这个权限）。
参考 AOSP `PackageInstaller.SessionParams#setInstallerPackageName` 的语义：
"By default this is the app that created the PackageInstaller object."

#### 4.48.2 实测记录

环境：Android 15 模拟器（API 35），被测应用 uid `10208`，用一次性探针页（见 4.48.5）执行。

| 目标 installer | `setInstallerPackageName` | `createSession` | 结果 |
|---|---|---|---|
| `com.android.vending`（Play 商店） | 未抛异常 | `SecurityException: Package com.android.vending does not belong to 10208` | 拒绝 |
| `com.google.android.gms`（系统 GMS） | 未抛异常 | `SecurityException: Package com.google.android.gms does not belong to 10208` | 拒绝 |
| `com.zeeload.carappcenter`（自己） | 未抛异常 | 成功，`session installerPackageName=com.zeeload.carappcenter` | 通过 |

对照组（第三行）说明失败原因不是"API 不可用"，而是**归属校验**：只有自己可以。

#### 4.48.3 五条"伪装"路线为什么都不通

| 路线 | 卡点 |
|---|---|
| 改 `installerPackageName` | 归属绑定调用者 uid；例外要 `INSTALL_PACKAGES`（实测见上表） |
| 把 APK 包名改成商店包名 | 商店是系统签名 / priv-app，签名不同 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| 伪造商店签名 | 无私钥 |
| 与商店共用 `sharedUserId` | 需要同签名 |
| 伪造商店的 UI / 协议让用户"以为"是商店 | 系统只认包名 + 签名 + uid，与观感无关 |

#### 4.48.4 仍然可行的两条

1. **让商店自己发安装**（就是本项目的动态拦截链路）：商店 → `PackageInstaller` →
   归属天然是商店包名 → 系统按官方渠道发权限。确定性最高。
2. **有特权通道时改归属记录**：`adb shell pm install -i <商店包名> app.apk`（shell 持
   `INSTALL_PACKAGES`）。注意这只改"归属记录"，**权限是否下发取决于厂商实现**：
   - 按 installer 名在白名单里发 → 能吃到；
   - 商店安装完成回调里调自家私有 `grant` 接口 → 吃不到（必须让商店自己走流程）；
   - 服务端按包名 + 签名下发白名单 → 与安装者无关，怎么改都没用。

#### 4.48.5 复现探针（临时用，验证完记得删）

```kotlin
// app/src/main/java/com/zeeload/carappcenter/ProbeActivity.kt（无 UI）
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.getStringExtra("target") ?: "com.android.vending"
        val log = StringBuilder()
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName("com.example.probe.victim")
            params.setSize(4096)
            params.setInstallerPackageName(target)
            log.appendLine("setInstallerPackageName($target): OK")
            val pi = packageManager.packageInstaller
            val id = pi.createSession(params)
            log.appendLine("createSession: OK id=$id")
            log.appendLine("session installerPackageName=${pi.getSessionInfo(id)?.installerPackageName}")
            pi.abandonSession(id)
        } catch (t: Throwable) {
            log.appendLine("createSession 失败：${t.javaClass.name}: ${t.message}")
        }
        File(filesDir, "probe.txt").writeText(log.toString())
        finish()
    }
}
```

```bash
# 清单里临时加：<activity android:name=".ProbeActivity" android:exported="true" />
adb install -r app-debug.apk
adb shell am start -n com.zeeload.carappcenter/.ProbeActivity --es target com.android.vending
adb shell run-as com.zeeload.carappcenter cat files/probe.txt
```

#### 4.48.6 落地判据

装完立刻看身份，别靠猜：

```bash
bash scripts/check-installer.sh <包名>          # installerPackageName + 运行期权限
adb shell dumpsys package <包名> | grep -E "installerPackageName|pkgFlags|privateFlags"
adb shell cmd appops get <包名>
```

- `installerPackageName = 商店包名` → 官方渠道口径，权限可能跟着走；
- `installerPackageName = com.zeeload.carappcenter`（本应用）→ 第三方口径，不要指望商店特权。

### 4.5 两个漏洞的组合（核心结论）

#### 漏洞 A：CA 证书链不可信（传输层）

- 某公共 CA 的部分中间证书私钥存在缺陷，可被恢复（2026-10 公开披露）；
- 主流浏览器与桌面系统在数日内陆续封禁该链；
- 但**部分车机（国内 Android，无 GMS）没有收到封禁更新**，仍然信任对应根证书；
- 后果：中间人可以在不解锁车机、不安装任何证书的前提下，完整解密并改写车机与商店之间的 HTTPS。

#### 漏洞 B：商店安装授权逻辑不一致（应用层）

商店有两条会触发安装的路径，对 `sign` 的处理不一致：

| 路径 | 是否校验 `sign` | 结果 |
|---|---|---|
| 直接安装（侧载 / 文件安装） | 强制校验 | 第三方 APK 无法通过 |
| 更新已安装应用（商店内部） | 不校验（`sign: null` 也继续） | 第三方 APK 被当成「更新」装上 |

#### 组合

```text
A：解密 + 改写 TLS
   └─ 把「安装任意 APK」伪装成「某个已安装应用有新版本」
        └─ B：更新路径不校验 sign
             └─ 目标 APK 以「更新」名义被安装
```

缺一不可：

- 只有 A：能看能改，但拿不到有效签名，卡在授权；
- 只有 B：无法改写商店流量，无从把「更新」指向自己的 APK；
- A + B：才能把任意 APK 送进安装流程。

> 这不是「破解签名」：APK 签名与服务端签名服务本身都没有被攻破，被绕过的是**校验点**。

## 5. 模拟环境复现记录

在自建模拟环境（本机代理网关 + 模拟车机 + 模拟商店响应）中复现的流程如下，数值为示例：

| 步骤 | 现象 |
|---|---|
| 1 | appList 响应被注入，日志输出 `[inject-update] 已为 ALL 注入新版本` |
| 2 | 商店用改写后的 `sha256` 请求签名，服务端返回 `sign: null` |
| 3 | 商店继续下载，`Range: bytes=0-0` 探测后被替换为目标 APK |
| 4 | 下载完成，安装器以「新应用」路径完成安装 |

对照实验：保持元数据不改、只替换下载内容时，商店因哈希不匹配下载失败；保持哈希不改、只改版本号时，商店不认为有更新。**两者都改**才成立。

## 6. 工具用法

### 6.1 一键侧载（推荐，macOS）

双击 `一键侧载安装.command`，把 APK **拖到这个文件上**（或双击后按提示输入路径）。

工具会自动：校验 APK → 计算 sha256/md5/大小 → 启动透明代理（注入 ALL + 替换下载）→ 提示在车机上操作。

### 6.2 车机侧操作

1. 打开应用商店 → 我的 / 检查更新（或直接点开某个应用的详情页）；
2. 出现「有更新」提示后，点**任意一个**应用的更新；
3. 等进度走完（实际下载的是你的 APK），安装完成后按终端提示 Ctrl-C。

### 6.25 动态拦截（控制台）

```bash
sudo bash scripts/sideload.sh --live          # 或双击 动态拦截.command
```

代理起来后打开 `http://127.0.0.1:18082/`：`/apps` 实时列出商店已知应用，选定外壳包 + APK 路径
后 `POST /arm` 立即生效；`/disarm` 解除；`/reload` 在 APK 换文件后热重载。整个过程不重启代理。

### 6.3 命令行方式

```bash
sudo bash scripts/sideload.sh --apk /path/to/app.apk            # 注入所有已安装应用
sudo bash scripts/sideload.sh --apk app.apk --pkg com.example.app
```

### 6.4 多机 / 多车适配

macOS 版不写死设备参数：

| 参数 | 自动探测方式 | 覆盖方式 |
|---|---|---|
| 共享接口 | 扫描 `bridge*`，取有私有 IPv4 且 active 的 | `SIDELOAD_IFACE` |
| 车机 IP | 读 DHCP 租约，按接口网段挑选 | `SIDELOAD_CAR_IP` |
| 车机 MAC | 同一租约里解析 | `SIDELOAD_CAR_MAC` |
| 目标域名 | 默认 `store.example.com` / `cdn.example.com` | `--domains "a.com b.com"` |
| 域名后缀过滤 | 默认 `example.com` | `--hosts "example.com"` |

检测不到车机时，pf 规则会退化为「整个热点网段」作为源，仍然只作用于热点侧。

### 6.5 Windows 版

`windows/` 目录是自包含的 Windows 版本：

- 用 **WinDivert**（pydivert）在 ICS 转发层抓包，把发往 443 的包改写到本机，回程再把源地址改回去；
- 本地 **SNI 分流器**读出域名，用 HTTP CONNECT 交给 mitmproxy（Windows 没有 pf）；
- 注入 / 替换逻辑与 macOS 版共用同一个 addon（`addons/store_log.py`）；
- 依赖：Python 3.12+、`pip install mitmproxy pydivert`、管理员权限。

详见 `windows/README.md`。

## 7. 成功 / 失败判读

| 现象 | 含义 |
|---|---|
| 终端出现 `[inject-update] 已为 ALL 注入新版本` | 更新注入生效 |
| 出现 `[replace-apk] 商店已用替换包哈希取签名` | 商店采纳了改写后的元数据，替换已武装 |
| 出现 `[replace-apk] 以 xxx.apk 替换下载` | 正在把目标 APK 发给车机 |
| 商店提示「下载失败」 | 元数据没被采纳（缓存）或内容哈希不匹配 |
| 安装报「包名冲突 / 签名不一致」 | 目标 APK 与已安装应用同名，需换包名不同的 APK |

## 8. 风险与回滚

- 测试期间车机的商店流量经电脑代理，电脑关机 / 睡眠会导致车机断网；
- 注入会让商店认为**所有**已安装应用都有更新，可能连续发起下载；
- 退出工具（Ctrl-C / 关闭窗口）即卸载转发规则并停止代理，车机网络恢复正常；
- 不建议在非研究目的下对日常使用的车辆操作（见 README 的「安全与合规」）。

## 9. 修复建议（给厂商与平台方）

- 更新安装路径同样强制校验 `sign` 非空且与包名 / 版本 / 哈希绑定；
- 对 appList / detail 等元数据响应做端到端签名，防止校验基准被改写；
- 强制证书透明度（CT）与吊销检查，及时更新根信任库；
- 安装授权与「是否更新」解耦：所有安装路径统一走同一套校验；
- 商店关键请求启用证书固定（需考虑轮换与可运维性）。

## 10. 涉及文件

| 文件 | 作用 |
|---|---|
| `addons/store_log.py` | mitmproxy 插件：注入 / 替换 / 记录 |
| `scripts/transparent-test.sh` | pf 转发 + mitmdump 生命周期 |
| `scripts/sideload.sh` | 侧载入口（算哈希、调引擎） |
| `windows/win_sideload.py` | Windows 版：WinDivert 重定向 + SNI 分流 |
| `windows/zeeload_gui.py` | Windows 图形界面 |

## 11. 伦理与合规

- 仅在**自建模拟环境**与研究者**自有设备**上复现；
- 不得用于他人车辆、不得用于规避付费或授权、**不得用于任何商业用途与盈利**；
- 公开讨论时请沿用本文的通用化表述，不要附带真实域名、包名、设备标识或抓包内容；
- 项目仅做技术探讨，代码未经第三方安全审计，风险自负。
