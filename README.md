# ZEEload

> # 🛑 Research Preview · 请勿公开分发
>
> ### **仅限口口相传，请勿发帖、请勿宣传。**
>
> **请不要**把本项目发到微博、小红书、知乎、B 站、抖音、X（推特）、V2EX、贴吧、微信群、Telegram 群、公众号、短视频或任何论坛；
> 也**不要**提交到导航站、聚合站、Awesome 列表或开源推荐号。
>
> **原因**：本项目是**学术研究与技术探讨**性质的课题，用于观察车机应用商店的更新机制与证书信任链设计，面向的是小范围的交流与复现。
> 公开传播容易被断章取义、被当成「破解工具」转发，既偏离研究初衷，也容易带来不必要的误解与风险。
>
> 想交流的话，**私下告诉有研究兴趣的朋友就好**，并提醒对方不要外传。

![ZEEload](assets/banner.png)

在没有 root、没有 adb、车机不允许安装未知来源应用的前提下，**把任意 APK 装进目标车机**。

思路是让车机自己的应用商店把它当成一次「应用更新」安装下去：透明解密商店流量 → 注入一条假更新 → 把下载内容换掉。全程不修改车机系统、不往车机装证书，退出即恢复。

> 当前版本：**v1.0.1 · Research Build**（2026-10-31）
> 技术分析见 [docs/技术路径-车机APK侧载.md](docs/技术路径-车机APK侧载.md)
> 本项目是 **AI 辅助编程项目**：需求、方案与实测由作者主导，代码与文档在 AI 协助下完成，可能存在未知缺陷，请自行审阅与评估。

## 研究边界（请先读）

> ### 本项目**仅适用于自行搭建的车机平台**
>
> 所有验证都在**自建环境**中完成：自己搭建的热点 + 模拟车机 + 模拟商店响应。**不针对、不连接、不操作任何真实厂商的车机或线上服务**。
>
> ### 本项目**仅是系统安全防护思路**的研究记录
>
> 它讨论的是「当证书信任链失效、且安装授权校验不一致时，会发生什么」——目的是说明这类校验点应当如何设计、如何加固，属于**防御视角**的课题记录，不是可用的攻击武器。
>
> ### 本项目**不得用于生产环境**
>
> 不得部署到任何生产线、售后流程、车队运维或商业交付中；不得用于对外提供服务。仅限研究者在隔离环境中复现与学习。
>
> 文中厂商一律写作「某厂商」，域名、包名、App 名称均使用 `example.com` / `com.example.*` 之类的占位符，不指代任何具体设备。


---

## 目录

- [研究边界（请先读）](#研究边界请先读)
- [下载（发布包）](#下载发布包)
- [适用条件](#适用条件)
- [环境要求（版本）](#环境要求版本)
- [原理](#原理)
- [两个漏洞的组合](#两个漏洞的组合)
- [快速开始（macOS）](#快速开始macos)
- [动态拦截（控制台：官方市场下载 → 权限跟着走）](#动态拦截控制台官方市场下载--权限跟着走)
- [Windows](#windows)
- [车机端应用中心（Android）](#车机端应用中心android)
- [车机本机动态拦截 + 局域网热加载（Android 1.3.0）](#车机本机动态拦截--局域网热加载android-130)
- [结果判读](#结果判读)
- [常见问题](#常见问题)
- [已知限制](#已知限制)
- [安全与合规](#安全与合规)
- [文件结构](#文件结构)

## 下载（发布包）

到 [Releases](https://github.com/zhujinle/ZEELoad/releases) 下载对应平台的压缩包：

| 平台 | 发布包 | 解压后 |
|---|---|---|
| Windows 10 / 11 x64 | `ZEELoad-Windows-x64.zip`（约 30 MB，**解压即用**） | 双击 `ZEELoad\ZEELoad.exe`（会弹 UAC），图形界面里选 APK → 点「开始」 |
| macOS 13+ | `ZEELoad-macOS.zip`（约 0.09 MB） | 先装 mitmproxy（见下），再双击 `ZEELoad\一键侧载安装.command`，把 APK 拖上去 |

> ⚠️ Windows 包必须**整个 `ZEELoad` 文件夹一起解压使用**。只把 `ZEELoad.exe` 拖出来会报
> `Failed to load Python DLL ... _internal\python312.dll`——程序依赖同目录的 `_internal\`。
>
> 🍎 macOS 包是脚本集合（已带 Unix 权限、LF 换行），**不含 mitmproxy**：
> `pip3 install mitmproxy`（或 `python3 -m venv venv && ./venv/bin/pip install mitmproxy`，脚本会自动识别包内 `venv/`）。
> 从浏览器下载后若提示「无法验证开发者」，右键 →「打开」，或执行 `xattr -dr com.apple.quarantine ZEEload`。
>
> 两个包都不含任何第三方 APK，也不联网上传数据；日志只写在本机。

## 适用条件

| 项目 | 要求 |
|---|---|
| 车机 | Android 车机，且系统**仍信任某公共 CA 的中间证书**（对应 2026-10 披露的中间 CA 私钥泄露事件；此后更新过信任库的设备可能失效） |
| 电脑 | macOS 13+（已验证）或 Windows 10/11 x64（已验证） |
| 网络 | 电脑给车机开热点 / 互联网共享，车机连上后能正常上网 |
| 权限 | 电脑需要一次管理员权限（配置 pf / 加载 WinDivert 驱动） |
| 车机侧 | 无需 root、无需 adb、无需安装证书、无需开启未知来源 |

**只用于你自己的设备与研究环境。** 详见[安全与合规](#安全与合规)。

## 环境要求（版本）

| 组件 | 最低要求 | 本项目实测 | 说明 |
|---|---|---|---|
| Python | **3.12** | 3.12.14 | mitmproxy 12.3+ 的硬性要求；3.10 / 3.11 装不到支持 `emudhra` 的版本 |
| mitmproxy | **12.3** | 12.3.1 | `emudhra`（使用泄露的中间 CA 链签发证书）从 12.3 起提供 |
| pydivert（仅 Windows） | 3.1+ | 3.1.3 | WinDivert 的 Python 绑定，自带驱动；版本由 mitmproxy 12.3 的约束解析 |
| pip | 任意较新版本 | 26.x | `python -m pip install -U pip` |
| macOS | 13+ | 15.6（arm64） | 依赖系统自带的 `pf`、互联网共享 |
| Windows | 10 / 11 x64 | 10 21H2（19044） | 需要管理员权限 + 移动热点（ICS） |

检查命令：

```bash
python3 --version
mitmdump --version            # 需要 Mitmproxy 12.3+
python3 -m pip show mitmproxy pydivert | grep -E "^(Name|Version)"
```

> Windows 上如果默认 `python` 是 3.10/3.11，请显式用 `py -3.12`；工具检测到低版本时会自动尝试 `py -3.12` 重新运行。

## 原理

```text
车机 ──> 电脑热点
          ├─ 透明转发：车机到商店域名的 443 全部转到本机（macOS: pf，Windows: WinDivert）
          ├─ 解密：mitmproxy 用泄露的中间 CA 链签发证书，车机仍信任该链，所以能解开 TLS
          ├─ 注入：商店「检查更新」时，把已安装应用的版本改成更高、哈希/大小指向目标 APK
          └─ 替换：商店真正下载时，把响应体换成目标 APK（按 Range 正确切分）
车机商店：哈希校验通过 → 请求签名（服务端对非官方哈希返回 null，商店不拦）→ 安装器按「新应用」装上
```

四个关键点（均在模拟环境复现中确认）：

1. 车机接受由泄露中间 CA 签发的证书，**不需要往车机装任何证书**；
2. 商店的哈希校验基准来自 appList 元数据，而元数据走同一条可解密的 TLS，可以一起改写；
3. 服务端只给官方哈希签名（非官方返回 `sign:null`），但**商店 / 安装器不因 null 中止**；
4. 包名不同会被安装器当成**新应用**安装 —— 所以只能装新包，不能覆盖已装应用。

## 两个漏洞的组合

本方法**不是单点突破**，而是把两个独立的安全问题串起来用——缺一不可：

| | 漏洞 | 在本项目里的作用 |
|---|---|---|
| **A. CA 证书链不可信** | 某公共 CA 的中间证书私钥泄露（2026-10 披露），部分车机系统仍信任该链 | 让网关能够**解密并改写**商店的 HTTPS（元数据、下载内容） |
| **B. 商店安装授权逻辑不一致** | 直接安装路径要求有效 `sign`；而「更新已安装应用」路径**不校验** `sign`——服务端返回 `sign:null` 仍继续下载并安装 | 让改写后的第三方 APK 以「更新」名义**绕过安装授权** |

```text
A：解密 + 改写 TLS
   └─ 把「安装任意 APK」伪装成「某个已安装应用有新版本」
        └─ B：更新路径不校验 sign
             └─ 目标 APK 以「更新」名义被安装
```

单独利用任何一个都不够：

- **只有 A**：能看能改，但任意 APK 拿不到有效签名，卡在授权；
- **只有 B**：需要能改写商店流量才能把「更新」指向自己的 APK，而 HTTPS 挡住了；
- **A + B**：才能把「任意 APK」送进安装流程。

模拟环境中的观测：服务端对非官方哈希返回 `sign:null`，商店仍完成下载并安装。完整论证、A/B 对照与修复建议见 [docs/技术路径-车机APK侧载.md](docs/技术路径-车机APK侧载.md)。

> 这不是「破解签名」：APK 签名和服务端签名服务本身都没被攻破，被绕过的是校验点。

## 快速开始（macOS）

### 1. 安装 mitmproxy

脚本会自动在以下位置找 `mitmdump`，任选一种即可：

```bash
# 方式 A：系统/Python 环境
pip3 install mitmproxy

# 方式 B：包内虚拟环境（推荐，不污染系统）
python3 -m venv venv && ./venv/bin/pip install mitmproxy
```

### 2. 开热点、连车机

系统设置 → 通用 → 共享 → **互联网共享**，共享来源选你的上网接口，共享到 Wi-Fi；车机连上这个热点。

### 3. 一键侧载

把要装的 APK **拖到 `一键侧载安装.command` 上**（或双击后输入路径），输入 Mac 密码。

脚本会自动完成：探测共享接口（`bridge*`）→ 从 DHCP 租约识别车机 → 配置 pf 独立 anchor → 启动 mitmdump → 注入 + 替换。

### 4. 车机侧操作

1. 打开应用商店 → **点开任意一个应用的详情页**（详情页会实时请求元数据，这是注入生效的关键入口）；
2. 看电脑终端出现：

   ```
   [inject-update] 已为 ALL 注入新版本（N 个下载地址已武装）
   ```

3. 在详情页点「更新 / 安装」，接着应出现：

   ```
   [replace-apk] 以 xxx.apk (NNNN 字节) 替换下载，Range=...
   ```

4. 进度走完即安装完成，按 **Ctrl-C** 结束（会自动卸载 pf 规则）。

### 手动 / 高级用法

```bash
# 指定 APK 与注入目标（默认 ALL：所有已安装应用都伪装成可更新）
sudo bash scripts/sideload.sh --apk /path/app.apk
sudo bash scripts/sideload.sh --apk app.apk --pkg com.example.app

# 只看会执行什么，不改系统（无需密码）
bash scripts/transparent-test.sh --check-only --all-443

# 环境自检
bash scripts/check-environment.sh
```

可用环境变量覆盖自动探测：`SIDELOAD_IFACE`、`SIDELOAD_CAR_IP`、`SIDELOAD_CAR_MAC`、`SIDELOAD_VENV`、`SIDELOAD_DOMAINS`、`SIDELOAD_HOSTS`。

## 动态拦截（控制台：官方市场下载 → 权限跟着走）

### 为什么必须走官方市场

车机系统给应用发权限，判据往往不是「你装了什么」，而是**「谁装的」**：
`PackageManager` 记录的 `installerPackageName` 等于应用市场包名时，系统才按官方应用对待
（后台保活、自启动、悬浮窗、系统级存储、通知免打扰白名单等额外授予一般都是这么发的）。

所以工具的目标不是「绕过权限」，而是**让安装动作由商店自己完成**：

```text
车机商店检查更新 → 我们只改元数据（版本/哈希/大小）→ 商店自己去下载
                → 我们只换响应体 → 商店自己调 PackageInstaller 安装
                ⇒ installerPackageName = 商店包名，权限按官方渠道发放
```

安装会话、安装器身份、安装完成广播全部由商店发起，工具只在网络上改两处内容。

### 动态拦截：运行中换包，不重启代理

静态 `--inject-update/--replace-apk` 只能启动时决定一次。**动态拦截**把「注入目标」和
「替换包」搬到运行期的控制台里，随时切换：

```bash
# 方式 A：双击（推荐）
动态拦截.command

# 方式 B：命令行
sudo bash scripts/sideload.sh --live
# 或
sudo bash scripts/transparent-test.sh --all-443 --intercept-all --live --control-port 18082
```

启动后打开控制台：**http://127.0.0.1:18082/**

| 控制台能做什么 | 接口 |
|---|---|
| 看当前武装状态（目标包 / APK / 哈希 / 剩余有效期 / 注入与替换次数） | `GET /status` |
| 看商店里出现过的应用（包名/名称/版本/下载地址，实时学习） | `GET /apps` |
| 武装：选注入目标 + 指定 APK，立即生效 | `POST /arm` `{"pkg":"…","apk":"/abs/path.apk","ttl":3600}` |
| 解除武装（后续下载原样透传） | `POST /disarm` |
| APK 换了文件后热重载（不用重启） | `POST /reload` |
| 拦截事件流水（注入 / 替换 / 跳过 / 取签名） | `GET /events` |

命令行等价用法：

```bash
curl -s -X POST http://127.0.0.1:18082/arm \
  -H 'content-type: application/json' \
  -d '{"pkg":"com.example.installed","apk":"/Users/you/ZkPlay.apk","ttl":3600}'
curl -s http://127.0.0.1:18082/apps | python3 -m json.tool     # 看商店已知应用，挑外壳包
curl -s -X POST http://127.0.0.1:18082/disarm
```

操作顺序：

1. 车机连热点 → 打开应用商店 → 我的 / 检查更新 → **点开任意应用详情页**（详情页才会实时拉元数据）；
2. 终端出现 `[inject-update]`、控制台 `/apps` 里出现该应用 → 选它 → 填 APK 绝对路径 → 武装；
3. 车机点那个应用的「更新」；
4. 终端出现 `[replace-apk] … 替换下载`，进度走完即装完；
5. 按 Ctrl-C（或控制台「解除武装」）结束，pf 规则自动卸载。

### 只能升级、不能重装：同包升级模式

车机上的应用一旦「装过」，后续往往只允许**升级**（同包名、签名一致、版本号更高），不再允许重装或改签。
这时工具要走的不是「换包名新装」，而是**同包升级**——注入目标就是目标 APK 自己的包名：

| 约束 | 为什么 | 工具怎么处理 |
|---|---|---|
| **versionCode 必须比已安装的大** | 安装器只认 APK 内部的 `android:versionCode`，不认服务端元数据；同版本会报「版本未提高」 | 武装前读 APK 的 `versionCode`，与已安装版本比较，**不满足直接拒绝并说明** |
| **签名必须与已安装的一致** | 同包升级校验签名，换签名会被安装器拒装 | 读 APK 签名证书指纹；可传 `installed_apk` 做比对并给出「一致 / 不一致」结论 |
| **元数据版本要和 APK 一致** | 商店按元数据判断「有新版本」，安装器按 APK 判断「能否装」 | 升级模式下 `apkVersion/apkVersionName` 直接写 APK 自己的值（不再用哈希派生假版本） |

武装时 `mode` 三个取值：

```bash
# auto：商店里已有该包 → 走升级；否则走换包名新装（推荐，默认）
curl -s -X POST http://127.0.0.1:18082/arm -H 'content-type: application/json' \
  -d '{"mode":"auto","apk":"/Users/you/ZkPlay.apk","installed_version_code":40}'

# update：强制同包升级（包名取自 APK 自己）
curl -s -X POST http://127.0.0.1:18082/arm -H 'content-type: application/json' \
  -d '{"mode":"update","apk":"/Users/you/ZkPlay.apk","installed_apk":"/path/installed.apk"}'

# new：换包名新装（外壳应用）
curl -s -X POST http://127.0.0.1:18082/arm -H 'content-type: application/json' \
  -d '{"mode":"new","pkg":"com.example.installed","apk":"/Users/you/app.apk"}'
```

`installed_version_code` 可显式给「车机上实际装的版本」（商店元数据可能滞后）；不给就用商店返回的
`apkVersion`。车机上已装 42、手里的包也是 42 时，武装会被直接拒掉：

```text
升级走不通：APK 内部 versionCode=42 ≤ 已安装 42。
安装器只认 APK 自己的 versionCode，先把版本号加大重新出包（或改包名走新装）
```

这条就是「只能升级」场景下最常见失败原因：**需要重新出包把 versionCode 加到 43 以上**，
签名沿用原私钥；签名换了照样装不上。

### 多应用市场：运行期选择拦谁

车机上常不止一个商店（厂商市场 / 应用中心 / 第三方市场），域名和接口还可能随版本变。工具不写死域名：

- **自动识别**：任一小 JSON 响应里出现 `apkPackageName`，就把该接口域名认成应用市场并纳入拦截范围
  （控制台 `/markets` 会列出来，带命中次数）；
- **逐个开关**：`POST /markets {"host":"…","enabled":false}` 立刻不再碰这个市场的流量；
- **武装时指定**：`POST /arm {… "hosts":["api.example.com","cdn.example.net"]}` 把某个市场一起带上；
- 全局开关：`POST /markets {"auto":false}` 关掉自动识别，只拦 `--set hosts=` 白名单里的域名。

```bash
curl -s http://127.0.0.1:18082/markets | python3 -m json.tool
curl -s -X POST http://127.0.0.1:18082/markets -H 'content-type: application/json' \
  -d '{"host":"api.example.com","enabled":false}'
```

协议一旦换（域名、路径、字段名一起变），能兜底的顺序是：自动识别新市场域名 → 元数据注入
按「含 `apkPackageName` 的 JSON」匹配（不依赖具体路径）→ 下载地址认不出来时，注入后 TTL 内
首个非 JSON 响应即当下载替换。再换不动了，就是服务端换了校验方式，得重新分析。

### 验证「这次是不是官方渠道装的」

```bash
bash scripts/check-installer.sh com.example.target
```

判读只有一条：`installerPackageName` 是不是商店包名。

| 结果 | 含义 |
|---|---|
| `installerPackageName = 商店包名` | 这次安装走的是官方渠道，系统按官方应用发放权限 |
| `installerPackageName` 为空 / 其它 | 系统按第三方来源处理，权限不跟着走 |
| `granted=true` 覆盖目标权限、`cmd appops get` 里悬浮窗/后台为 `allow` | 权限已到位 |

### 权限为什么可能「还是没给」

系统若是在**安装完成回调里按「商店以为自己在装的那个包」**发权限的，那么被发权限的是
元数据里那个包（外壳应用），而不是你的 APK。判定办法：装完后跑上面的脚本看两个包
——外壳包与目标包谁的权限变了。若发到了外壳包上，就改用「注入目标 = 目标 APK 自己的包名」
（目标应用已经装着时才行），这样元数据包名与真实包名一致，权限自然落在目标包。

## Windows

`windows/` 是自包含版本，用 **WinDivert** 代替 pf，用本地 **SNI 分流器**把流量喂给 mitmproxy（Windows 没有 pf，mitmproxy 透明模式不可用）。

### 用发布包（推荐）

解压 `ZEELoad-Windows-x64.zip` → 双击 `ZEELoad\ZEELoad.exe`（弹 UAC 点「是」）→ 选 APK → 点「开始」。界面里还有「放行防火墙」「抓包诊断」「诊断」几个按钮，排障时很有用。

### 从源码运行

```powershell
cd windows
python -m pip install -U mitmproxy pydivert

# 以管理员身份运行
python win_sideload.py --apk "C:\path\app.apk"
```

或直接双击 `windows\一键侧载-Windows.bat` 并拖入 APK（会自动申请管理员权限）。

常用参数：`--proxy-only`（只起 HTTP 代理，不需要管理员）、`--hotspot-ip`（手动指定热点网卡）、`--sniff 30`（抓包诊断）、`--emudhra-old`（老信任库设备）。

详细说明、排错与限制见 [windows/README.md](windows/README.md)。

## 车机端应用中心（Android）

`android/` 是独立的车机端应用中心，支持 Android 9+。首次把应用中心装入车机仍使用上面的 macOS/Windows 工具；安装完成后，应用中心可以直接管理后续 APK。

功能包括：

- 输入 HTTP/HTTPS APK 地址，下载、解析包名/版本/签名并提交系统安装；
- 导入车机已有的第三方应用，保存来源地址、版本、签名和安装结果；
- 同包名、同签名且版本递增时更新并保留原应用数据；签名不一致时保留原应用并报告失败；
- APK 缓存默认 512 MB，按最久未使用顺序清理，设置页可调整上限或一键清理；
- 本地结构化诊断日志最多 20 MB 或 7 天；可选批量 POST 到 HTTP/HTTPS 接收地址，支持断网重试和 30 分钟实时调试；
- 查看其他应用的权限状态，并跳转到系统应用详情页处理权限。普通应用的运行时权限仍由目标应用按系统流程申请。

### 构建与安装

```bash
cd android
cp local.properties.example local.properties
# 修改 local.properties 中的 sdk.dir
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

构建要求 Android SDK、JDK 17 和 Gradle wrapper；最低 Android 版本为 9（API 28）。发布版必须使用项目外部的稳定签名密钥，配置方式见 [android/README.md](android/README.md)。

车机端设置里的 `127.0.0.1` 指车机本机；Mac 或局域网日志接收端要填写车机能够访问的 IP 或域名。日志只上传结构化事件，不上传 APK 内容或请求头；完整 APK URL 会按设置记录，URL 中若含访问参数也会被记录。

## 车机本机动态拦截 + 局域网热加载（Android 1.3.0）

`android/` 里的应用中心现在自带「车机本机拦截」：不需要电脑、不需要 root，
用 per-app VPN 只接选中应用市场的流量，本地做 TLS 解密 + HTTP 改写，安装仍由商店发起。
详见 [android/README.md](android/README.md#动态拦截车机本机120)。

```bash
cd android
cp local.properties.example local.properties   # 改 sdk.dir
# 把中间 CA 材料放进去（不入库）
#   android/app/src/main/assets/ca/zeeload-int.pem
#   android/app/src/main/assets/ca/zeeload-int.key
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

**局域网热加载（不重装 App）**：车机端可从局域网拉「规则 + 插件」模块（ECDSA 验签 + sha256 校验），
协议变了只改 `rules.json`，逻辑变了换 `plugin.dex`，原子切换、可回滚。工具见
[tools/module-server](tools/module-server/README.md)：

```bash
cd tools/module-server
./gen-keys.sh                     # 一次：生成签名密钥，公钥粘进 app assets
VERSION=1.0.2 ./build_plugin.sh   # 编插件 → dex → 签名 → out/current
python3 serve.py --dir out/current --port 8770
```

要点与限制：只处理 IPv4、一个连接一个请求、只改写小 JSON（chunked 透传）、
DNS 固定 223.5.5.5；商店若做证书固定则只能退回电脑端侧载。

## 手机上取得报告（车机端页面）

车机上的「取得报告（扫码）」页：整屏二维码 + 6 位验证码，手机扫码进浏览器页面后点「下载报告」，
下载完成前页面会盯着进度并阻止误关；报告 = **异常 + 调试日志** 打包的 ZIP。
端口 8888 被占用会自动顺延（8889…，最多 10 个）并在车机上提示，也能一键「换一个端口」。
手机侧页面与预览：`python3 tools/report-portal/preview.py --port 8902`（预览服务同样会自动避让占用端口）；
细节见 [android/README.md](android/README.md#取得报告扫码下载140)。

## 结果判读

| 终端 / 车机现象 | 含义 | 处理 |
|---|---|---|
| `[inject-update] ... 已武装` | 元数据注入成功 | 可以去点更新了 |
| 没有 `[inject-update]` | 商店用了缓存，没重新拉元数据 | **点开应用详情页**；仍不行就重启车机 |
| `[replace-apk] 以 xxx.apk 替换下载` | 替换成功 | 等安装完成 |
| `[replace-apk] 该下载...按原样透传` | 注入没生效，下载的是官方内容 | 同上，先让注入成功 |
| 商店报「下载失败」 | 内容哈希与元数据对不上（替换没发生） | 检查上面的日志与机型 |
| 安装报「包名冲突」 | 目标 APK 与已安装应用同名 | 换包名不同的 APK |
| 装上了但桌面没图标 | 车机桌面过滤了非白名单应用 | 重启车机；从「设置 → 应用」里直接打开 |
| 控制台 `/apps` 一直是空 | 商店没重新拉元数据（缓存） | 在车机点开应用详情页；或强行停止商店后再试 |
| 控制台 `/arm` 返回 400 | APK 路径不对 / 不是 zip | 检查路径是否绝对路径、文件是不是真 APK |
| 武装报「升级走不通：versionCode ≤ 已安装」 | 手里的包版本号没提上去 | 重新出包把 versionCode 加到已装版本以上，签名沿用原私钥 |
| 武装报「签名不一致」 | 同包升级要求同一签名 | 用原签名私钥重签，或改包名走 new 模式 |
| 装完权限还是没给 | 权限发到了「外壳包」而不是目标包 | 跑 `scripts/check-installer.sh` 对比两个包，必要时改成注入目标包自己的包名 |

## 常见问题

**Q：会不会把车机弄坏？**
不会。全程只改电脑上的转发规则和发给车机的响应内容，不写车机系统、不装证书、不改分区。退出脚本即恢复；车机断网就关掉脚本或跑 `停止并恢复.command`。

**Q：能覆盖/升级已安装的应用吗？**
不能。安装器要求更新包与原应用包名、签名一致；本方法装的是**新包**。

**Q：为什么一定要点「应用详情页」？**
商店对列表/更新结果做了缓存，改缓存里的哈希不会生效；详情页是少数会实时请求元数据的入口。日志里出现 `[inject-update]` 就说明注入到位。

**Q：为什么服务端返回 `sign:null` 也能装？**
观测显示商店与安装器都不校验这个签名（或只作透传）。这是本方法成立的关键前提之一，商店/车机 OTA 更新后可能改变。

**Q：macOS 提示找不到 mitmdump？**
按[快速开始](#快速开始macos)装好；或在包内建 `venv/`，脚本会自动用它。

**Q：Windows 上 443 被循环劫持？**
已处理：WinDivert 过滤器只匹配热点网段（`x.x.x.2–254`），不会把本机 mitmproxy 的上游连接也劫持。若你改了热点网段，请同步改 `windows/win_sideload.py` 里的判断。

**Q：Windows 发布包需要装 Python 吗？**
不需要。`ZEELoad-Windows-x64.zip` 里已经内置 Python 3.12、mitmproxy、WinDivert 驱动，解压双击即可。只有从源码运行才需要 Python 3.12+。

**Q：Windows 版双击没反应 / 一闪而过？**
按顺序检查：① 是不是只把 `ZEELoad.exe` 拖出来了（必须整个 `ZEELoad` 文件夹一起解压）；② UAC 弹窗有没有点「是」（透明模式要管理员）；③ 看 `ZEELoad\logs\gui-run-*.log`，或点界面里的「诊断」。

## 已知限制

- **只能装新包**，不能替换已安装应用（同包名覆盖要求签名一致，本方法不解决签名）；
- 动态模式武装带 TTL（默认 3600 秒），过期自动失效；控制台只监听 `127.0.0.1`，不对外开放；
- 商店若在「安装完成回调」里按元数据包名发权限，权限会落在作为外壳的那款应用上，
  需要改用「注入目标 = 目标 APK 自己的包名」；
- **仅 IPv4 透明转发**（CDN 有 AAAA，macOS 版会用 `block return` 把目标域名的 IPv6 挡掉、逼回 IPv4；Windows 版依赖 ICS 不给客户端 IPv6）；
- 全量解密可能让个别做了证书固定的车机应用暂时异常，退出脚本即恢复；
- 商店「检查更新」有缓存，必须靠**详情页**触发新元数据；
- 商店 / 车机 OTA 更新后，信任库或校验逻辑可能变化，需要重新验证；
- **Windows 版**：必须用管理员运行（WinDivert 加载内核驱动），且依赖「移动热点」的 ICS 转发；车机如果自动切回蜂窝网络、或商店只走 QUIC，会拦截不到（工具会丢弃 QUIC 逼它回退 TCP，日志里有提示）。

## 安全与合规

> **本项目仅限自有设备的研究与自用，严禁用于任何商业用途**：不得收费代装、不得作为付费服务、不得随产品捆绑、不得用于付费培训或咨询，以及任何形式的变现。

- 仅用于**你名下的设备**与自建模拟环境；请勿用于他人车辆，或用于规避付费与授权；
- **仅限自行搭建的车机平台与隔离的研究环境，不得用于任何生产环境**（不得进产线、售后流程、车队运维或商业交付）；
- **质保风险自行辨别、自行承担**：向车机安装非官方应用可能被厂商 / 4S 店认定为影响质保，进而拒绝保修或拒赔；是否操作、能否接受，请自行判断，相关后果由使用者自行承担；
- 安装的 APK 由使用者自行负责来源与合法性；仓库与发布包均不附带任何第三方 APK；
- 工具不收集、不上传任何数据，所有日志只落在本机 `logs/`；
- 使用前请确认当地法律法规与车辆保修条款；因使用本项目产生的一切法律与财产风险由使用者自行承担；
- 本项目仅做技术探讨，代码未经第三方安全审计，不提供任何形式的担保，**不得用于盈利用途**。

## 文件结构

```text
ZEEload/
├── README.md                    本文档
├── 一键侧载安装.command          ★ 主入口（拖入 APK，静态模式）
├── 动态拦截.command               ★ 动态模式（起代理 + 打开控制台，运行中选包/换包）
├── 停止并恢复.command            兜底：卸载规则、停代理
├── 检查环境.command              只读诊断
├── scripts/
│   ├── sideload.sh               侧载入口（算哈希、调引擎；--live 走动态模式）
│   ├── check-installer.sh        校验安装来源（installerPackageName）与权限落地
│   ├── transparent-test.sh       引擎：pf 转发 + mitmdump 生命周期（自动探测接口/车机）
│   ├── stop-transparent.sh       卸载规则
│   ├── check-environment.sh      环境自检
│   └── inspect-pf.sh             只读导出 pf 规则（排障用）
├── addons/store_log.py           mitmproxy 插件（注入 / 替换 / 记录 / 动态控制面）
├── addons/apkmeta.py             APK 元信息（包名 / versionCode / 签名指纹，纯 Python）
├── windows/                      Windows 版（WinDivert + SNI 分流 + 图形界面）
├── android/                      车机端应用中心（Android 9+）
├── assets/                       Logo 与宣传图（svg / png / icns / ico）
├── docs/
│   └── 技术路径-车机APK侧载.md    原理、漏洞分析、模拟环境复现记录
├── release/                      本地存放待上传的发布包（zip，不入库）
├── lab/                          本机实验脚本（未纳入版本库）
├── artifacts/                    本机下载的 APK（未纳入版本库）
├── evidence/                     实验证据（未纳入版本库）
└── logs/                         每次运行的输出（未纳入版本库）
```

## 版本

### v1.24.1 · 全页面返回按钮（2026-11-06）

- 新增 `ui/BackBar.kt`：动态拦截页、取得报告页顶部统一「← 返回」（车机没有系统返回键时也能退出）
- 主界面加 `BackHandler`：非首页按返回先回「我的应用」，不再直接退出
- 报告页去重标题；产物 `release/应用助手-1.24.1-release.apk`（1.24.1 / vc 31）

### v1.24.0-hd · HD 大屏适配（2026-11-06）

- 新增 `ui/Adaptive.kt`：按可用宽度分三档（<980dp / 980–1500dp / ≥1500dp），驱动侧栏宽度、页边距、卡片间距
- 大屏字号 ×1.18（与用户字号档位相乘）、超宽屏内容限宽 1680dp 居中、拦截页左栏 560→780dp
- 720p 车机布局回归验证无变化；产物 `release/应用助手-1.24.0-hd-release.apk`（1.24.0-hd / vc 30）

### v1.23.3 · 应用改名「应用助手」（2026-11-06）

- 应用名抽成 `res/values/strings.xml` 的 `app_name`（桌面名称、界面标题、报告标题都读它），当前值 **应用助手**
- 包名不变 `com.zeeload.carappcenter`，可直接覆盖升级；产物 `release/应用助手-1.23.3-release.apk`（1.23.3 / vc 29）

### v1.23.2 · 应用图标 + 发布包（2026-11-06）

- 新增**应用图标**：深色圆角底 + 绿色 Z + 安装落点横线（自适应图标，含 monochrome；`mipmap-*` 位图兜底）
- 新增**正式签名与打包**：`android/keystore/zeeload-release.jks`（RSA-4096，10 年生）
  + `keystore.properties`（不入库）+ `assembleRelease` v1/v2/v3 签名
- 产物：`release/ZEELoad-1.23.2-release.apk`（正式签名）、`release/ZEELoad-1.23.2-debugkey.apk`（调试签名，用于覆盖旧开发包）、
  `release/SHA256SUMS`、`release/安装说明.txt`

### v1.23.2 · 修 A2/A3（2026-11-06）

- **A2 市场列表手势**：整行可点选（`clickable`），`Checkbox` 只做展示（`onCheckedChange = null`），不再和列表行抢手势；
  实测短促快滑（300ms）也能滚动整页且不会误勾选（`markets` 前后都是同样 2 个）
- **A3 武装告警重复**：已装版本号未知时不再同时输出"升级要求签名一致"那条，只保留"还拿不到已安装版本号"（实测 warnings 只剩一条）

### v1.23.1 · 修 A1：动态模块状态不恢复（2026-11-06）

- 根因：`ModuleManager.activate()` 只 `return` 拼好的 Status，**没写回 `_status`**，所以界面/报告里 `enabled` 一直是 false
  （事件里却显示"已生效"）—— 改成写回状态，`restore()` 同样写回，并在 `CarApp.onCreate` 启动时自动恢复缓存模块；
  界面 URL 跟随 `status.baseUrl`，不再回退默认 `192.168.2.1`
- 实测：重启后 `runtime.json` → `module.enabled=true / id=zeeload-demo / version=1.0.1 / plugin=true / url=http://127.0.0.1:8770`（Android 1.23.1）

### v1.23.0 · 无 adb 也能带出现场状态（2026-11-06）

- 报告新增 **`runtime.json`**：武装态（mode/target/vc/markets/warnings）、VPN 是否在拦、模块 enabled/id/version/plugin/url、
  设置项、库内条数 —— 车机上不能 adb 时，扫一次码就能把"当时什么状态"带出来（Android 1.23.0）

### v1.22.0 · 打开商店可自选（2026-11-06）

- 「打开商店」改为**用户挑市场**：弹候选列表（已勾选优先），选择持久化到 `settings.storePackage`，按钮显示当前目标；
  车机换市场包/改用别的商店 App 时不用改代码（Android 1.22.0）

### v1.21.0 · 功能收敛（2026-11-06）

**不再叠功能，做减法**：只保留「安装 / 调试日志 / 动态拦截」三条主线。

删除：一键开始、六段进度条与分段重试、多市场轮换、`flow_runs` 落库（DB v4 迁移 DROP）、
历史页导出/筛选/展开详情、事件导出/清空按钮、事件保留上限设置、报告里的 `exports/` 与 `flows`。
保留：按地址安装、待安装包、安装历史（纯列表）、导入应用、字号/存储/日志接收/安装身份/诊断、
动态拦截（市场选择 / 武装 / 启动·停止拦截 / 打开商店 / 动态模块）、扫码报告。

`InterceptActivity` 917 → 619 行，`HistoryTab` 350 → 73 行，单测 76 全绿。

### v1.0.25 · Research Build（2026-11-06）

- 六段进度**落库**（`flow_runs`，Room v2→v3 迁移）：历史详情可重放"这次卡在哪一步"，报告 `installs.json` 带 `flows`（Android 1.20.0）
- 历史展开自动滚动到该条；调试日志默认收起（按钮展开），详情不再被撑出屏幕

### v1.0.24 · Research Build（2026-11-06）

- 打开商店支持**多市场轮换**：按已勾选市场依次尝试，第一个没入口就换下一个，全失败才退「应用详情」并提示已试几个（Android 1.19.0）
- 支持 `--es markets "pkgA,pkgB"` 预置市场，便于脚本与演示

### v1.0.23 · Research Build（2026-11-06）

- 进度条的失败段可**点击重试**：武装 / 拦截 / 商店（失败退到系统「应用详情」页）/ 等安装，逐步重试不再从头再来（Android 1.18.0）

### v1.0.22 · Research Build（2026-11-06）

- 拦截页新增**流程进度条**：`武装 → 拦截 → 商店 → 等安装 → 对账 → 收尾`，六段状态（待执行/进行中/完成/失败）+ 当前消息（Android 1.17.0）
- 右栏改整体可滚动，事件列表改为最近 8 条（避免嵌套滚动抢手势）

### v1.0.21 · Research Build（2026-11-06）

- 一键开始的兜底：市场打不开时底栏黄字提示「<包名> 打不开，请手动进商店」+ warn 事件（Android 1.16.0）
- 拦截页结果消息挪到底部固定条（车机上一眼可见，不用滚到 ③ 看）

### v1.0.20 · Research Build（2026-11-06）

- **一键开始**：武装 + 启动拦截（首次弹 VPN 授权）+ 打开商店合成一个按钮，按钮随「有没有 APK」自动出现（Android 1.15.0）

### v1.0.19 · Research Build（2026-11-06）

- 报告带上 `exports/` 目录（最近 5 个导出文件，总量 ≤2 MB）；历史页导出只留最近 10 个，并有「清理导出」（Android 1.14.0）
- **对账命中即收尾**：装完自动解除武装 + 停止 VPN，写 `done` 事件（「对账完成：已解除武装 + 停止拦截」）

### v1.0.18 · Research Build（2026-11-06）

- 安装历史支持**按筛选导出**：`files/exports/install-history-<时间>.json`（含关联 `events`/`logs`）与 `.csv`（主字段 + 关联条数）（Android 1.13.0）

### v1.0.17 · Research Build（2026-11-06）

- 报告 `installs.json` 每条记录**内嵌关联的拦截事件与调试日志**（`events` / `logs`），看报告不用自己对时间（Android 1.12.0）
- 拦截事件页新增 **导出**（`files/exports/*.jsonl`）与 **清空**；保留条数上限做成设置（200–20000，默认 2000）

### v1.0.16 · Research Build（2026-11-06）

- 拦截事件**持久化**：新增 `intercept_events` 表（Room v1→v2 迁移 + `ts` 索引），保留最近 2000 条 / 7 天（Android 1.11.0）
- 安装历史展开详情改为「库内时间窗 + 内存」合并去重，**跨重启**仍能回答「这条是怎么来的」
- 报告 `intercept.json` 同时取内存与库；自动武装失败现在会落一条 warn 事件（此前静默）

### v1.0.15 · Research Build（2026-11-05）

- 安装历史支持**点击展开**「这条是怎么来的」：同包拦截事件 + 最近 8 条调试日志（Android 1.10.0）
- 设置页新增「动态拦截」卡：武装等待超时可改（30–1800 秒，默认 600），车机上无需 adb

### v1.0.14 · Research Build（2026-11-05）

- 新增**安装历史页**：按 `via=direct` / `via=market` 筛选，显示包名、vc、通道、installer 与完整结果串（Android 1.9.0）
- 拦截武装带**超时自愈**：默认 10 分钟等不到商店安装即自动解除武装并写 warn 事件（`--ei watch_timeout_sec` 可调）

### v1.0.13 · Research Build（2026-11-05）

- 安装**对账**：按地址安装记 `via=direct`，拦截安装由 `MarketInstallWatch` 检测版本变化后回写
  `via=market · installer=<市场包名>`，两条路的结果都进同一张安装历史与应用列表（Android 1.8.0）
- 拦截页新增**带入横幅**（应用名 / 包名 / 模式 / 未勾选市场 / 待武装 / 已武装），市场候选列表改「前 5 个 + 展开」
- 侧栏底部压缩显示最近一次安装的「哪条路 + 谁装的」

### v1.0.12 · Research Build（2026-11-05）

- 「待安装包」新增 **经市场安装** 一键串联：带包路径 + UPDATE 模式跳「动态拦截」，市场已勾选时自动武装（Android 1.7.0）
- 设置页新增 **安装身份** 卡片：三条路径（地址安装 / 拦截安装 / `pm install -i`）× installer × 权限口径，附实测结论与核对命令

### v1.0.11 · Research Build（2026-11-05）

- 车机端把「安装者身份」做进界面：待安装包卡片显示 `安装者 = com.zeeload.carappcenter（非市场）`、
  地址安装卡片常驻第三方口径说明、武装页提示「安装由商店发起 → installer = 商店包名」（Android 1.6.1）
- 支持 `--es apk_url` 预填 APK 地址，便于脚本与演示

### v1.0.10 · Research Build（2026-11-05）

- 补一节**实测**：「伪装成应用市场」在应用层不可行（Android 15 上 `createSession` 抛
  `SecurityException: Package <市场包名> does not belong to <uid>`），并给出复现探针、五条伪装路线为何不通、
  两条真正可行的路径、以及装完核对 `installerPackageName` 的判据
- `android/README.md` 增加「安装身份与权限口径」对照表：按地址安装 / 拦截链路 / 特权 `pm install -i`

### v1.0.9 · Research Build（2026-11-05）

- **车机字号可调（Android 1.6.0）**：`ui/CarTheme.kt` 用 `Density.fontScale` 统一放大，四档（标准 / 大·默认 / 特大 / 超大），设置里切换即时生效；基础字号与按钮高度整体上调
- 放大后暴露的布局问题一并修掉：报告页验证码与按钮重叠、拦截页开关滚出屏幕（改固定底部操作条）、侧栏状态卡被压扁、市场列表半行截断

### v1.0.8 · Research Build（2026-11-05）

- **车机端 UI 统一（Android 1.5.0）**：抽出 `ui/CarUi.kt` 设计系统（色板 / Card / PrimaryButton / GhostButton / CarField / Pill / PageHeader / StatusStrip / NavItem），应用中心三个页签与动态拦截、取得报告全部改用它；主界面从 Material 默认紫改为与拦截/报告一致的深色卡片风
- 主界面重排：侧栏（品牌 + 导航 + 工具分组 + 状态卡）、`我的应用`（列表 + 检查/安装 + 待安装包 + 权限）、`导入应用`、`设置`（存储 / 日志接收 / 诊断）全部卡片化
- 顺手修掉截图里暴露的问题：侧栏标题换行、按钮文字折行被裁、模块 dex 可写导致 ART 拒载、报告页超出 720p

### v1.0.7 · Research Build（2026-11-05）

- 报告服务端口不再写死：`8888` 被占用时自动顺延（最多 10 个），二维码/地址/界面提示同步更新，另有「换一个端口」按钮
- 预览服务 `tools/report-portal/preview.py` 同样会自动避让占用端口

### v1.0.6 · Research Build（2026-11-05）

- **车机端「取得报告」页（Android 1.4.0）**：二维码（纯 Kotlin QR 编码器，无 zxing）+ 6 位验证码 + 备用地址列表，手机扫码后在浏览器下载「异常 + 调试日志」ZIP；进度、断线、误关页面都有提示
- `ReportServer`（ServerSocket 极简 HTTP）只接 GET、验证码换报告、`Content-Length` 支撑手机端进度，并把状态推回车机界面
- `ReportBuilder` 打包 summary / exceptions / debug / installs / intercept / packages / manifest；`CrashLog` 全局未捕获异常落盘
- Mac 端 `tools/report-portal/preview.py` 复用同一份 landing.html 做页面预览

### v1.0.5 · Research Build（2026-11-05）

- **局域网动态模块**：车机端从 Mac 拉取 `rules.json`（声明式改写规则）+ `plugin.dex`（注入/绕过/劫持插件），ECDSA 验签 + sha256 校验，原子切换与回滚；不重装 App 就能适配协议变化
- 车机端控制面：`POST /markets` 之外新增动态规则/插件层（`RewriteRules` 可整体替换、字段名与下载路径可配）
- 工具链：`tools/module-server`（`gen-keys.sh` / `build_plugin.sh`（javac + d8）/ `sign_module.py` / `serve.py`），示例插件演示注入、下载劫持、`/v3→/v2` 绕过
- 跨工具链验证：Mac 侧 openssl 签出的模块，由打包进 APK 的公钥在真实单测里验签通过

### v1.0.4 · Research Build（2026-11-05）

- **车机本机动态拦截**（`android/` 1.2.0）：per-app VPN 选定应用市场 → 车机本地 TLS 解密（纯 Kotlin DER 签证书，无 BouncyCastle）→ 商店 JSON 注入 / 下载替换；安装由商店完成，权限走官方渠道
- 车机端同样实现「升级/新装」口径：`AUTO/UPDATE/NEW`，升级前校验 versionCode 与签名
- 车机端读 APK 用 `PackageManager` + 文件哈希，签名指纹与 `apksigner` 口径一致

### v1.0.3 · Research Build（2026-11-05）

- **同包升级模式**：`mode=auto|update|new`；`addons/apkmeta.py` 纯 Python 读 APK 的包名/versionCode/versionName/签名证书指纹（与 `apkanalyzer` / `apksigner` 结果一致），升级前校验版本与签名，不满足直接拒绝并给出原因
- 升级模式下元数据的 `apkVersion/apkVersionName` 用 APK 自己的值，避免「商店说有新版、安装器说版本没提高」
- **多应用市场动态选择**：自动识别返回应用数据的接口域名并入拦截范围；`GET/POST /markets` 逐个开关；`/arm` 可带 `hosts`
- 下载地址字段名不认识时（`assetPath` 之类），注入后 TTL 内首个非 JSON 响应按下载兜底替换

### v1.0.2 · Research Build（2026-11-05）

- **动态拦截控制面**（`addons/store_log.py` + `动态拦截.command`）：运行期热武装，切换注入目标包与替换 APK、解除武装、热重载，均不需要重启 mitmdump
- 控制台页面（`http://127.0.0.1:18082/`）实时显示商店已知应用、武装状态、注入/替换次数、事件流水；含 `GET /status` `/apps` `/events`、`POST /arm` `/disarm` `/reload`
- 下载识别放宽：`/v2/getApk?id=…` 这类不含 `.apk` 后缀的下载地址也能被替换；下载地址字段名不认识时按「注入后的下载」兜底
- 替换响应清理 `content-encoding` / `etag` / `digest` 等会与替换内容冲突的头
- 新增 `scripts/check-installer.sh`：校验 `installerPackageName` 与运行期权限是否跟着官方渠道走

### v1.0.1 · Research Build（2026-10-31）

通用化研究版本：去除具体厂商与设备信息，测试环境统一表述为自建模拟环境。

- **Windows 版可用**：图形界面（选 APK / 模式 / 热点 IP、实时日志、防火墙放行、抓包诊断）；透明重定向按 ICS 转发层抓包、注入时修正地址层与接口索引；证书链与握手策略均已调整
- **macOS 版**：与 v1.0.0 功能一致，重新打包（LF 换行 + 执行位）
- 全部标识改用通用占位：`example.com` / `com.example.*` / `某厂商`

### v1.0.0（2026-10-30）

- **macOS**：一键侧载（拖入 APK），pf 透明转发 + mitmproxy 解密
- **Windows**：WinDivert 重定向 + 本地 SNI 分流
- 自动探测：共享接口、车机、mitmdump 位置
- 更新注入支持 appList 与 app/detail 详情页两种响应
- 大响应流式转发、IPv6 回退、签名→下载时间闸门

## 参考

- [mitmproxy 透明代理](https://docs.mitmproxy.org/stable/howto/transparent/)
- [mitmproxy 证书机制](https://docs.mitmproxy.org/stable/concepts/certificates/)
- [eMudhra 事件与受影响客户端](https://emudhra.we-have-a-problem.com/)
