# ZEEload 车机应用中心

车机端 Android 9+ 应用，用于从 APK URL 下载、检查、安装和更新应用。

## 构建

~~~bash
cd android
cp local.properties.example local.properties
# 将 sdk.dir 改为本机 Android SDK 路径
./gradlew :app:testDebugUnitTest :app:assembleDebug
~~~

本机没有 Gradle wrapper 时，可使用已安装的 Gradle：

~~~bash
gradle :app:testDebugUnitTest :app:assembleDebug
~~~

Debug APK 输出在：

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

发布版必须使用稳定的签名密钥。签名配置通过 Gradle properties 提供，密钥文件和密码不提交到仓库：

~~~text
zeeloadKeystore=/absolute/path/zeeload-release.jks
zeeloadStorePassword=...
zeeloadKeyAlias=...
zeeloadKeyPassword=...
~~~

## 功能

- 车机原有应用导入和本 App 安装应用记录；
- APK HTTP/HTTPS 地址下载、断点续传、SHA-256 和包信息解析；
- 同包名更新走 Android PackageInstaller，失败时保留原应用和数据；
- 默认 512 MB APK LRU 缓存，设置页可调整和清理；
- 本地诊断日志最多 20 MB 或 7 天；
- 可配置 HTTP/HTTPS JSON 日志接收地址；
- 远程日志默认关闭，支持批量上传和 30 分钟实时调试；
- 权限状态查看与系统权限设置入口。

~~~text
127.0.0.1 在车机 App 中表示车机本机。Mac 或局域网接收端应填写车机可访问的 IP 或域名。
~~~

## 首次安装

首次将本 App 安装到车机仍使用仓库现有的 Mac/Windows 侧载工具。后续应用下载和更新由车机端应用中心执行。

## 动态拦截（车机本机，1.2.0）

车机上不需要 root、不需要 adb、不需要电脑：本 App 自己把「应用市场」的流量接进来改写，
安装仍由商店完成（`installerPackageName` = 商店包名 → 系统按官方渠道发权限）。

```text
用户勾选应用市场 ──> VpnService（per-app：addAllowedApplication）
                        └─ 本地 TLS 解密：用 assets/ca 里的中间 CA 现签叶子证书
                             └─ HTTP 改写：某个应用「有新版本」/ 下载响应换成目标 APK
                                  └─ 商店自己下载 → 自己安装（权限跟着官方渠道走）
```

### 放 CA 材料

```
android/app/src/main/assets/ca/zeeload-int.pem   中间 CA 证书（PEM，可多张）
android/app/src/main/assets/ca/zeeload-int.key   中间 CA 私钥（PEM，PKCS#8 或 PKCS#1）
```

没有这两个文件时 VPN 仍能启动，但只能做明文转发（无法解密/改写），事件里会提示。
这两个文件已在 `.gitignore` 里排除，不会误提交。

### 使用步骤

1. 主界面 →「动态拦截（应用市场）」；
2. 勾选要拦的那个应用市场（列表按「能上网 + 系统应用 + 名称含商店语义」打分）；
3. 填 APK 绝对路径 →「检查 APK」看包名/versionCode/签名；
4. 选模式（`AUTO` / `UPDATE` / `NEW`）→「武装」；
   - `UPDATE`：同包升级，要求 versionCode 高于已装、签名一致，工具会当场校验并拒绝不合规的组合；
   - `NEW`：换包名新装，选一个已装应用当「更新外壳」；
5. 「启动拦截」→ 系统弹一次 VPN 授权 → 允许；
6. 到车机商店点那个应用的「更新」，装完即目标 APK；
7. 完事「停止拦截」。

### 实现与边界

| 组件 | 作用 |
|---|---|
| `intercept/ZeeloadVpnService` | per-app VPN，只接入选中的市场应用 |
| `intercept/net/TunEngine` | 车机上的最小 IPv4/TCP 栈 + UDP 53 转发 + 重传 |
| `intercept/net/TlsEngines` | 服务端：用现签证书解密；客户端：信任任意证书取上游 |
| `intercept/CertMinter` + `Der` | 纯 Kotlin（无 BouncyCastle）DER 编码 + 叶子证书签发 |
| `intercept/StoreRewriter` | 商店 JSON 注入（升级用 APK 自身版本、新装用哈希派生版本） |
| `intercept/DownloadReplacer` | 下载响应按 Range 切片替换 |
| `intercept/ArmRepository` | 武装状态持久化 + 规则下发 + 商店已知应用学习 |

已知边界（真机 bring-up 时按这个顺序排查）：

- 一个连接只处理一个请求（响应后即关连接），商店若强依赖 keep-alive 会重连，不影响功能；
- 只改写「Content-Length 且 ≤4 MB 的 JSON」；chunked 响应原样透传（不做改写）；
- 只处理 IPv4；IPv6 需要扩展（可先让商店走 IPv4）；
- DNS 固定转发到 `223.5.5.5`，内网/受限网络请改 `ZeeloadVpnService.DNS`；
- 商店应用若做了证书固定（certificate pinning）会握手失败 —— 此时只能走电脑端侧载路径。

## 动态模块（局域网热加载，1.3.0）

车机上的 App 只能升级、不能重装，商店协议又会变。所以「怎么改」抽成模块，Mac 上改、局域网下发：

```text
车机「动态拦截」面板 ④ 动态模块
   ↓ 填 http://<Mac 的局域网 IP>:8770
index.json（清单 + ECDSA 签名）
   ↓ 验签（公钥固化在 assets/module-pubkey.txt）+ 每个文件 sha256
plugin.dex（DexClassLoader 加载） + rules.json（声明式规则）
   ↓ 原子切换 + 指针文件
立刻生效（不用重装 App、不用重启拦截）；失败保留上一版，可回滚
```

| 改动类型 | 改什么 | 车机操作 |
|---|---|---|
| 商店字段名/路径/域名变了 | `rules.json` | 面板「拉取并启用」 |
| 注入/绕过/劫持逻辑要变 | 插件 Java 源码 → `build_plugin.sh` | 同上 |
| 只要规则不要代码 | 只放 `rules.json` | 同上 |
| 出问题 | —— | 面板「回滚 / 停用（回内置）」 |

规则字段（节选）：`storeKeys / versionFields / versionNameFields / hashFields / md5Fields /
sizeFields / signFields / urlFields / downloadPathPatterns / downloadContentTypes / hosts / addSignField`。

工具在 [tools/module-server](../tools/module-server/README.md)：`gen-keys.sh`（生成签名密钥）、
`build_plugin.sh`（javac + d8 + 签名 → `out/current`）、`serve.py`（局域网 HTTP）。

插件接口（`intercept/dynamic/PluginApi.kt`）：

```kotlin
interface InterceptPlugin {
    fun id(): String
    fun onStoreJson(json: String, ctx: PluginContext): String?      // 注入
    fun onDownloadRequest(method: String, path: String, headers: Map<String,String>, ctx: PluginContext): Boolean  // 劫持
    fun onUpstreamRequest(method: String, path: String, headers: Map<String,String>, body: ByteArray, ctx: PluginContext): UpstreamOverride?  // 绕过
}
```

插件跑在 App 进程内（无沙箱），所以只加载自己签的模块；插件抛异常会被熔断（5 次后停用该插件，
规则仍然生效），不影响拦截主链路。

## 取得报告（扫码下载，1.4.0）

车机上打开「取得报告（扫码）」：整块深色页面显示标题、说明、二维码、6 位验证码，
以及「没有相机就手输」的备用地址和多个备用 IP（对应界面截图里那串地址列表）。

```text
手机相机扫码 ──> http://<车机IP>:8888/r?c=321112
                └─ 浏览器页面：报告摘要 + 「下载报告」按钮 + 进度条
                     └─ 点击后 GET /report?c=321112（Content-Length + ZIP）
                          └─ 页面盯着进度，关页面会提示「下载完成前请保持此页面开启」
车机侧状态条：等待手机扫码 → 手机已连接 → 正在下载 45% → 下载完成 / 中断
```

报告内容（一个 ZIP，手机下载后直接看）：

| 文件 | 内容 |
|---|---|
| `summary.md` | 人读摘要：生成时间、设备、条数、先看哪一段 |
| `exceptions.log` | 未捕获异常 / 崩溃现场（`CrashLog` 装的全局 handler，最近 20 段，含全部线程栈） |
| `debug.log` | 应用内结构化调试日志（Room `events` 表，最多 5000 行） |
| `installs.json` | 安装与更新记录（**每条内嵌关联的拦截事件与日志**：`events` / `logs`） |
| `runtime.json` | **现场运行状态**：版本、设备、武装态（mode/target/vc/markets/warnings）、VPN 是否在拦、模块 enabled/id/version/plugin/url、设置项、库内条数 |
| `intercept.json` | 动态拦截事件（注入/替换/插件/模块） |
| `packages.json` | 已安装应用与证书指纹 |
| `manifest.json` | 报告元信息 |

实现要点：

- `report/QrCode.kt`：纯 Kotlin QR 编码器（字节模式 / 纠错 M / 版本 1–10），无 zxing 依赖；
  单测用**独立的读取器**做往返校验，开发机上再用 OpenCV 解码确认 8 种掩码都能扫出原文；
- `report/ReportServer.kt`：`ServerSocket` 写的极简 HTTP 服务（只 GET），验证码换报告、
  `Content-Length` 保证手机端能显示进度，并把「已连接/下载中/完成/中断」推给车机界面；
- `report/ReportBuilder.kt` + `ReportCache.kt`：ZIP 打包与缓存（只保留最近一份）；
- `report/CrashLog.kt`：`Thread.setDefaultUncaughtExceptionHandler` 落盘崩溃现场；
- 端口默认 `8888`；**被占用时自动顺延**（8889、8890…，最多试 10 个），二维码与所有地址同步换成实际端口，
  界面会提示「端口 8888 被占用，已自动改用 8889」；也可以用「换一个端口」按钮手动从下一个端口起重试。

Mac 上想先看页面效果（不起车机）：

```bash
python3 tools/report-portal/preview.py --port 8902
open "http://127.0.0.1:8902/r?c=321112"
```

预览服务的首页就是 App 里那份 `assets/report/landing.html`，改动只会有一处。

## 安装身份与权限口径（重要）

车机上同一个 APK，走哪条路装进去，`installerPackageName` 完全不同，权限也完全不同：

| 安装路径 | installerPackageName | 能不能拿到市场专属权限 |
|---|---|---|
| 应用中心「按地址安装」 | `com.zeeload.carappcenter`（本应用） | 不能。等同第三方应用，权限要用户逐个授 |
| 动态拦截 → 商店自己安装 | 车机应用市场的包名 | 能，系统按官方渠道对待 |
| 特权通道 `adb shell pm install -i <市场包名>` | 市场包名（仅记录） | 看厂商实现：按 installer 白名单发 → 能；按签名/服务端白名单 → 不能 |

**伪装成市场在应用层做不到**：`SessionParams.setInstallerPackageName()` 只是"申请"，
`createSession()` 时系统校验"installer 包必须属于调用者 uid"，否则直接
`SecurityException: Package <市场包名> does not belong to <uid>`（Android 15 实测，
详见 `docs/技术路径-车机APK侧载.md` 的 4.48 节）。

所以：

- 装新包、更新应用中心自己 → 「按地址安装」（它是唯一自更新通道）；
- 要市场那套权限（保活 / 自启动 / 后台弹窗）→ 必须走「动态拦截」，让商店自己发起安装；
- 想免确认、想降级 → 只能 device owner（`dpm set-device-owner`），普通应用无解。

装完用一条命令核对身份：

```bash
bash scripts/check-installer.sh <包名>
```

### 安装历史页（1.9.0）

侧栏新增「安装历史」：最近 200 条安装记录，按 `via=` 分三类筛选（全部 / 按地址安装 / 拦截安装），
每条显示包名、`vc`、通道徽标、时间、`installer` 与完整结果串。两条路的结果在**同一张表**里对账。

报告现在会把 `files/exports/` 里最近的导出文件一起打进 ZIP 的 `exports/` 目录（最多 5 个、总量 ≤2 MB），
交报告时连导出明细一起走。

拦截事件页新增 **导出 / 清空**：导出写成 `files/exports/intercept-events-<时间>.jsonl`（一行一条 JSON），
清空会同时清库与内存环形；保留上限在 **设置 → 动态拦截 → 事件保留条数**（200–20000，默认 2000）。

拦截事件现在**落库**（`intercept_events` 表，DB v1→v2 迁移，索引 `ts`，保留最近 2000 条 / 7 天），
所以**跨重启**也能在历史里关联上；报告里的 `intercept.json` 同时取「内存 + 库里」并去重。

每条记录**点一下展开「这条是怎么来的」**（自动滚动到该条，调试日志默认收起、按需展开）：列出同包的拦截事件（武装/注入/替换/对账）与最近 8 条调试日志
（`apk-checked` / `install` / `install-result` …），报告里有的一眼也能在界面上看到。

**导出**：按当前筛选导出到 `files/exports/install-history-<时间>.json` / `.csv`（只保留最近 10 个，另有「清理导出」一键清空）
- JSON：每条记录含 `channel` / `installer` / `result` / `sha256` 以及内嵌的 `events`、`logs`（各最多 20 条）；
- CSV：主字段 + `eventCount` / `logCount`（关联明细留 JSON，避免多行撑破表格）。

对应截图：`runs/ui/36-history-page.png`、`38-history-detail.png`、`44-history-export.png`。

### 武装超时自动解除（1.9.0）

拦截链路武装后如果没有等到商店安装，不能一直挂着（TTL 到期前会持续改写元数据）。
`MarketInstallWatch` 现在带超时：默认 **10 分钟**，超时且状态未变就自动 `disarm()` 并写一条 warn 事件
「等待 N 秒未检测到商店安装，已自动解除武装（可重新武装）」。

**流程落库**：六段状态写进 `flow_runs` 表（一次运行一行：`apkName / targetPackage / steps / message`，
DB v2→v3 迁移），历史详情里以「· 一键流程（N 次）」重放，报告 `installs.json` 里也带 `flows` 字段：
实测一行 `probe.apk → com.shihab.diplay.hudtest | 武装=ok,拦截=ok,商店=ok,等安装=ok,对账=ok,收尾=ok | 对账：商店已安装 …`。

**流程进度条**：底栏上方一条六段进度 `武装 → 拦截 → 商店 → 等安装 → 对账 → 收尾`，
`○ 待执行 / ◐ 进行中 / ✓ 完成 / ! 失败`，右侧跟一行当前消息（走任意一步后才出现）。
实测跑完一轮：`✓ 武装 → ✓ 拦截 → ! 商店（该市场没有启动入口）→ ✓ 等安装 → ✓ 对账 → ✓ 收尾`。

**一键开始**：底部固定条的绿色按钮把三步合成一步——武装 → 启动拦截（首次会弹系统 VPN 授权）→ 打开商店；
按钮只在「有 APK 路径」时出现（手动填或「经市场安装」带入），市场没勾会直接提示先勾。
**兜底提示**：目标市场没有启动入口（或被系统拒绝）时不再静默——底栏消息条变黄显示
`<包名> 打不开，请手动进商店`，同时写一条 `warn` 事件（一键开始的结果也在这条消息条里）。
之后对账命中会自动收尾（下面那条）。

**对账命中即收尾**：检测到商店装完 → 写记录 → 自动 `disarm()` + 停止 VPN 并写 `done` 事件
（「对账完成：已解除武装 + 停止拦截」），避免装完还继续改商店流量。

超时时长可在 **设置 → 动态拦截** 里改（30–1800 秒，默认 600），车机上不用连 adb；
调试时仍可用 `--ei watch_timeout_sec 10` 临时覆盖。对应截图：`runs/ui/37-arm-timeout.png`、`runs/ui/39-settings-timeout.png`。

两条路的结果现在**在同一处对账**：每条安装历史都带 `via=direct` 或 `via=market` 与真实 `installer`。

- **按地址安装**：结果串形如 `安装成功 · via=direct · installer=com.zeeload.carappcenter`；
- **拦截安装**：武装后 `MarketInstallWatch` 盯住目标包，检测到版本变化即回写
  `商店已安装 · via=market · installer=<市场包名> · vc=43`，同时更新应用列表（来源标「应用市场（拦截安装）」）；
- 侧栏底部压缩显示：`最近：<包名尾段> · via=market · <installer>`，完整内容在历史里。

两条路现在也能在界面上直接串起来：

- 「待安装包」卡片上的 **「经市场安装（商店身份）」** 按钮：把这个包、UPDATE 模式和包名一起带进「动态拦截」，
  到了那边**顶部横幅**显示 `从应用中心带入 <应用名> / <包名> · UPDATE · 先勾选市场 | 待武装 | 已武装`，
  市场已勾选时自动武装——接着启动拦截、回车机商店点更新，安装者就变成商店；
- 设置页新增 **「安装身份（决定能拿到哪些权限）」** 卡片：三条路径 × installer × 权限口径一张表，
  外加"伪装不可行"的实测结论与核对命令，装在车上就能对着看。

界面上也把口径标死了，不用记：

- 「通过地址安装」卡片下方常驻一行：`安装者：com.zeeload.carappcenter（本应用 · 第三方口径）`，并指向「动态拦截 → UPDATE」；
- 「待安装包」卡片新增 `安装者` 一项，装前就能看到这次会是第三方口径；
- 「动态拦截 ③ 武装」下新增一行：`安装由商店发起 → installerPackageName = 商店包名，权限按官方渠道发放`；
- 支持 `am start -n com.zeeload.carappcenter/.MainActivity --es apk_url <URL>` 预填地址（脚本/演示用）。

## 发布打包（1.23.2）

```bash
# 一次性：生成签名（已生成，勿重做；丢了就再也升级不了）
keytool -genkeypair -keystore keystore/zeeload-release.jks -alias zeeload \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass '<密码>' -keypass '<密码>' -dname "CN=ZEEload, O=ZEEload Research, C=CN"

# 打包（读取 android/keystore.properties）
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease --offline
```

- 产物：`app/build/outputs/apk/release/app-release.apk`，再复制到仓库 `release/` 并出 `SHA256SUMS`
- **车机只允许升级**：签名一旦换了就装不上新版本 → `android/keystore/zeeload-release.jks` 与
  `android/keystore.properties` **已随仓库提交**（私有仓库，刻意入库，见 `android/keystore/README.md`），不依赖某台机器本地备份
- 若车机上已装旧的**调试签名**包，正式包会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（实测确认），
  要么用 `debugkey` 包升级，要么先卸载（丢应用内历史/日志，APK 不受影响）

## HD 大屏适配（1.24.0）

`ui/Adaptive.kt` 的 `layoutFor(width, height)` 按宽度给三档规格：

| 档位 | 触发宽度 | 侧栏 | 页边距 | 卡片间距 | 字号系数 |
|---|---|---|---|---|---|
| Compact | < 980dp | 240dp | 14dp | 12dp | 1.00 |
| Medium | 980–1500dp | 296dp | 22dp | 16dp | 1.00 |
| Expanded | ≥ 1500dp | 380dp | 34dp | 24dp | 1.18 |

规则：
- 内容限宽 `contentMaxWidth`（900 / 1400 / 1680dp），超宽屏左右补 inset 居中，卡片不会被拉成横带；
- 字号系数与「设置 → 显示」的用户档位相乘（用户调多大，HD 再加成）；
- 动态拦截页左栏在 Expanded 时 780dp，避免 ④ 动态模块挤在小栏里；
- 1280×720（853dp 画布）落 Compact，与旧版布局一致（已回归截图核对）。

## 应用名称（1.23.3）

名称集中在 `app/src/main/res/values/strings.xml`：

```xml
<string name="app_name">应用助手</string>
<string name="app_subtitle">车机应用助手</string>
```

`AndroidManifest` 的 `android:label`、侧栏标题、报告标题都读它，改名只改这一处；
**包名 `com.zeeload.carappcenter` 不变**，车机升级不受影响。

## 应用图标（1.23.2）

`res/drawable/ic_launcher_foreground.xml`（深底 + 绿色 Z + 落点横线，含 monochrome）+ `mipmap-anydpi-v26` 自适应图标，
外加 `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` 位图兜底；`AndroidManifest` 配 `android:icon` / `android:roundIcon`。

## 交互修正（1.23.2）

- 市场候选**整行可点选**（Checkbox 仅展示），拖动列表也能正常滚动整页，不会误勾选；
- 武装告警去重：拿不到已装版本号时只提示这一条，不再附加"签名一致"的说明（避免看起来自相矛盾）。

## 动态模块状态恢复（1.23.1）

`ModuleManager.activate()` 现在会把结果**写回 `_status`**（此前只 return，导致界面与 `runtime.json` 永远是"未启用"，
而事件里又显示"已生效"）；`restore()` 同样写回，且 `CarApp.onCreate` 会在启动时自动恢复缓存模块（断网也能恢复）。
界面里的模块 URL 跟随 `status.baseUrl`，不会再退回默认 `192.168.2.1`。

## 没有 adb 怎么验证（车机现场必须这样走）

车机上通常连不了 adb，所以验证闭环全部放在 App 内：

1. **操作**：按功能点正常点（安装 / 拦截 / 武装 / 打开商店 / 动态模块）；
2. **现场看**：`安装历史`（via=direct / via=market）、`动态拦截 → 事件`（arm / watch / install / done / warn）；
3. **取证据**：侧栏 **取得报告（扫码）** → 手机扫码下载 ZIP；
4. ZIP 里就够定问题了：`runtime.json`（当时武装/VPN/模块/设置是什么状态）、`debug.log`（每步 stage）、
   `exceptions.log`、`installs.json`（含关联事件与日志）、`intercept.json`、`packages.json`、`manifest.json`。

对着功能点该看哪份文件：安装类看 `installs.json`；拦截/武装看 `intercept.json` + `runtime.json`；
权限/安装者口径看 `packages.json` 与 `imports` 记录；异常看 `exceptions.log`。

## 打开商店：用户自己挑（1.22.0）

车机厂商随时可能换市场 App 或换包名，所以「打开商店」不再绑定第一个勾选的市场：
点一下弹**候选列表**（已勾选的市场排最前，后面跟全部检测到的候选，标「已勾选」），
选哪个就开哪个，选择写进 `settings.storePackage`，按钮随即显示 `打开商店：<名字>`，下次沿用。

打不开的包会在底栏提示「<包名> 打不开，请手动进商店」并写 warn 事件——换一个再点即可。

## 功能收敛（1.21.0）

只留三条主线：**安装**（按地址安装 / 待安装包 / 安装历史）、**调试日志**（诊断日志 / 扫码报告）、
**动态拦截**（市场选择 / 武装 / 启停 VPN / 打开商店 / 动态模块）。

已删除：一键开始、进度条与分段重试、多市场轮换、`flow_runs` 落库、历史页导出与筛选、事件导出/清空、
事件保留上限设置、报告里的 `exports/` 与 `flows`。数据库升到 v4（`DROP TABLE flow_runs`，老库顺序迁移）。

## 统一 UI（1.5.0）

> 市场候选列表默认只显示前 5 个，避免长列表把 ②③④ 挤出首屏；需要更多点「展开全部 N 个候选市场」。


三个页面（应用中心 / 动态拦截 / 取得报告）共用一套设计语言，实现在
[ui/CarUi.kt](app/src/main/java/com/zeeload/carappcenter/ui/CarUi.kt)：

| 元素 | 规范 |
|---|---|
| 背景 / 卡片 | `#0A0E13` / `#141B24`，卡片圆角 16dp + 1dp `#232C38` 描边 |
| 文字 | 标题 30sp 粗体 / 卡片标题 17sp 半粗 / 正文 16sp / 说明 13–15sp 次级灰 `#8FA0B3` |
| 主按钮 | 绿色 `#2EA043`，圆角 12dp，最小高 48dp，文字不换行 |
| 次按钮 | 描边幽灵按钮，文字 `#E8EEF6` |
| 状态色 | 成功 `#3FB950` / 信息 `#79C0FF` / 警告 `#D29922` / 错误 `#F85149` |
| 列表 | 选中行浅色底 `#161B22` + 左侧绿色竖条 |
| 侧栏 | 品牌区 + 导航（选中态竖条）+ 「工具」分组 + 底部状态卡 |
| 输入框 | 统一 `CarField`：深底、12dp 圆角、聚焦蓝边、可选等宽字体 |
| 徽标 | `Pill`：半透明底 + 同色描边，用于状态/来源 |

页面骨架统一：`PageHeader(title, subtitle, trailing)` → `Card(title, subtitle){}` → `StatusStrip()`。
改 `CarPalette` 一处，三页同时生效；新增页面直接复用这些组件即可保持风格一致。

对应截图（1280×720 车机横屏）：`runs/ui/11-main-v2.png`（我的应用）、`12-import-v2.png`、
`13-settings-v2.png`、`14/16/17-intercept-*-v2.png`、`15-report-v2.png`。

## 字号（车机看不清怎么办，1.6.0）

车机屏幕大、观看距离远，手机尺寸的字号在车上会显得很小。现在字号是**可调的一档设置**，
默认直接给「大」：

| 档位 | 倍率 | 适配场景 |
|---|---|---|
| 标准 | ×1.00 | 与手机一致，近距离看 |
| 大 | ×1.15 | **默认**，车机常规距离 |
| 特大 | ×1.30 | 远距离 / 大屏车机 |
| 超大 | ×1.45 | 后排、强光下 |

设置 →「显示」里点一档立即生效（无需重启），存在 `Settings.uiScale`。

实现方式：`ui/CarTheme.kt` 用 `CompositionLocalProvider(LocalDensity provides Density(density, fontScale = scale))`
统一放大所有 `sp` 文字——组件不用逐个改；同时 `ui/CarUi.kt` 的基础字号整体上调
（卡片标题 18sp、正文 16–17sp、说明 14sp、按钮 17sp，按钮最小高 52dp）。
新增页面只要套 `CarTheme` 就自动跟随档位。

配套修掉的布局问题（都是放大后被截图暴露的）：

- 报告页验证码挤掉按钮 → 验证码与地址改成同行紧凑排布，二维码 272dp；
- 拦截页开关被内容挤到屏幕外 → 底部改为**固定操作条**（启动/停止/打开商店常驻）；
- 侧栏底部状态卡被压扁 → 给最小高度 64dp；
- 市场列表在特大档半行截断 → 列表内滚动区按档位限高。

截图对照：`runs/ui/18-main-large.png`（大）、`20-main-larger.png`（特大）、
`19/21-settings-*.png`、`22-intercept-larger.png`、`23/24-report-*.png`。
