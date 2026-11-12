# DPGrabber iOS — 独立版多陪抢单 App

原生 Swift 独立 App，不越狱、不注入、不依赖宿主 App，直接调用多陪后端
`api.duopei.feiniaowangluo.com`。与 PC 版（`../pc/`）和安卓版（`../android/`）
同款协议、同款功能，协议见 [`../PROTOCOL.md`](../PROTOCOL.md)。

> **本机未编译出 IPA、待 CI 验证。** 本机是 Linux、没有 Xcode：纯逻辑模块已用
> Swift 官方 Linux 工具链跑通全部 67 个单元测试；完整 App（含 SwiftUI 界面）
> 只能在 macOS 上编译，GitHub Actions 工作流（`../.github/workflows/ios.yml`）
> 会跑单测并打出**未签名 IPA**，供签名工具重签后安装。IPA 产物未经本机验证，
> 首次使用请先在 CI 日志里确认构建成功。

## 功能

- **登录**：手机号 + 密码（PROTOCOL.md §3.2：`POST /s/duopei-account/login` →
  token，sid 固定 `"50"`，再 list-shop-account / switch-account / getBalance
  校验；401/403 自动重登）。token 只存内存；**账号密码只存 iOS 钥匙串**，
  绝不写进配置文件。
- **监听**：轮询 `randomList`（pageSize 20），过滤（黑名单微信号、最低/最高金额，
  0 = 不限制、备注模式与关键词大小写不敏感子串），订单去重 60 秒 TTL。
- **确认抢单**：通过的订单立即开始确认任务（DPGrabber 的确认状态机：
  窗口内循环 confirm、每 0.5 秒重试至截止、网络错误用 `order/get` 回读
  核验），成功自动停止。不读 `reTime` / `confirmCountDown`，不做精确
  等待、不做用户延时。
- **后台**：前台轮询为主；Info.plist 声明 `UIBackgroundModes=audio` 并播放
  静音音频保活（我们自己的 App 自己声明，合法；做法与 DPGrabber Keeper 的
  静音音频同理）。抢单成功发本地通知。
- **界面**：SwiftUI，分区照 PC 版：登录、监听设置、开始/停止、运行日志、
  保存设置、高级设置（店铺 ID、超时、AES 密钥、TLS 开关）。
- **语音播报**：抢单成功 / 失败用系统语音播报（可关）。

## 后台能力的诚实说明

iOS 的后台能力**不能**与安卓前台 Service 完全等同：

- 静音音频能让 App 在切后台后继续轮询一段时间，但 iOS 仍可能在内存紧张、
  系统更新、用户上滑关闭、音频会话被接管时挂起或终止 App。
- 保活依赖音频会话：来电期间会中断，通话结束后自动恢复；插拔耳机、连蓝牙
  只会短暂影响，不会永久杀死保活（与旧版 DPGrabber Keeper 的 25 秒租约问题
  不同——这里没有租约）。
- 抢单成功会发本地通知，但如果 App 已被系统终止，通知也不会发出。
- 不要把 iOS 版当作 7×24 小时无人值守的抢单服务来用；它是“手机在手边、
  亮屏或切后台短时间监听”的工具。

## 加解密

- AES/CBC/PKCS7，**纯 Swift 实现**（`Sources/DPGrabberCore/AES.swift`，
  FIPS-197）。不用 CommonCrypto、不用 CryptoKit 的 AES-GCM——GCM 不是 CBC，
  且 CryptoKit 没有 CBC 模式。纯 Swift 还有一个好处：Linux 上的测试跑的
  就是最终上机的同一份代码。
- 十六进制 key/IV 与 Base64 响应在客户端内部自动转换（§4.5 的“自动转换进制”）。
- 两套密钥自动切换，以能解密为准：主密钥 DPGrabber 插件的 AES-256
  （`faf6bcb4…`），备用密钥 PC 工具的 AES-128（`81b120ef…`）。key/IV 可在
  高级设置里改，恢复默认一键完成。

## 目录结构

```
ios/
  Package.swift                  SwiftPM 包：只含 DPGrabberCore + 测试（Linux/macOS 跑单测用）
  Sources/DPGrabberCore/         纯逻辑模块：不依赖 UIKit/SwiftUI
    AES.swift                    纯 Swift AES-128/256，CBC + PKCS#7
    Crypto.swift                 hex/Base64 转换、信封加解密、金额/时间换算
    Models.swift                 ApiError、DPGSession、DPGClerk、LogLevel
    ApiClient.swift              URLSession 客户端、登录/店铺/订单接口
    OrderFilter.swift            §7.1 过滤链
    ConfirmStateMachine.swift    确认状态机：分类/预算/回读核验（§8.2）
    Config.swift                 配置模型、校验、JSON 存取
    ListenEngine.swift           监听引擎：轮询、去重、确认状态机、重登
  Tests/DPGrabberCoreTests/      XCTest 单测（打桩 transport，不发真实请求）
  App/                           App 层（只在 Xcode 工程里编译）
    DPGrabberApp.swift           @main 入口
    ContentView.swift            主界面：登录 / 监听设置 / 运行 / 日志
    SettingsView.swift           高级设置：店铺 ID、超时、AES 密钥、TLS
    AppViewModel.swift           界面与引擎之间的桥接
    CredentialStore.swift        钥匙串存取（手机号/密码）
    AudioKeepAlive.swift         静音音频保活（AVAudioEngine 循环零 PCM）
    VoiceAnnouncer.swift         AVSpeechSynthesizer 语音播报
    LogStore.swift               内存运行日志（最多 500 条，不落盘）
    Info.plist                   Bundle ID、最低 iOS 15.0、audio 后台模式
    LaunchScreen.storyboard      启动屏
    Assets.xcassets/             AppIcon（深色底 + 绿色圆环 + DP）
  DPGrabber.xcodeproj/           Xcode 工程（手写生成，见 scripts/）
  scripts/
    generate-xcodeproj.py        重新生成 project.pbxproj（确定性 ID）
    check-xcodeproj.py           校验工程结构（CI 里也跑）
  README.md / build-ios.md
```

**只有一份核心代码**：Xcode 工程直接引用 `Sources/DPGrabberCore/` 下的
8 个文件，SwiftPM 包引用的是同一批文件，不存在两份实现。改核心逻辑只需
改一处，两边都生效。

## 构建与安装

详见 [`build-ios.md`](build-ios.md)。简述：

1. **跑单测**（Linux 或 macOS 均可）：
   `cd ios && swift test`（需要 Swift 5.9+ 工具链）。
2. **本地编 App**（需要 macOS + Xcode 15+）：
   打开 `DPGrabber.xcodeproj`，选真机或模拟器运行；或命令行
   `xcodebuild -project DPGrabber.xcodeproj -scheme DPGrabber -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`。
3. **CI 打未签名 IPA**：推送到 `main` 即触发 `.github/workflows/ios.yml`，
   产物 `DPGrabber-ios-unsigned`。
4. **安装**：用你自己的签名工具对未签名 IPA 重签后安装（与安卓 APK、
   PC exe 同样的自签名分发思路，不走 App Store）。

## 测试结果（本机，2026-11-12）

Swift 6.1.2 Linux 工具链，`swift test`：**67 个测试，0 失败**。

| 套件 | 数量 | 覆盖 |
| --- | --- | --- |
| ApiClientTests | 20 | 信封解码（明文/加密/主备密钥回退）、401/403/IP 分类、请求头与 session、登录流程（sid 50、店铺优先/回退、拒绝）、randomList 形态 |
| CryptoTests | 14 | NIST AES 向量（ECB/CBC，128/256）、主备密钥 CBC、Python 加密的交叉 fixture 解密、hex/key 校验、金额/时间换算 |
| EngineTests | 13 | 端到端抢单成功并自动停止、过滤订单不 confirm、401 后重登、重复订单去重、重复启动拒绝、重试后成功、confirm 内 401 重登、网络错误回读成功、回读待定重试、回读未知停重试、回读店员不符、截止待定释放重抢、截止终核成功 |
| FilterTimingConfigTests | 20 | 过滤全分支、确认分类/预算/回读核验、配置默认/校验/JSON 往返（含新确认键与旧 user_delay 键忽略） |

AES 与 Python Crypto 的交叉验证：CryptoTests 里用的 Python 加密 fixture
（主密钥 AES-256 与备用密钥 AES-128 各一）由 Python PyCryptodome 生成，
Swift 解密后与原文一致；NIST/ECB/CBC 向量与 SP 800-38A 一致。

App 层（SwiftUI 界面、钥匙串、静音音频、语音、通知）**本机只做了语法检查**
（`swiftc -parse` 全部通过），未在本机编译——没有 Xcode，也没有 iOS 模拟器。
界面与保活行为以 CI 构建 + 真机为准。

## 与规格书的出入

1. **信封解码多了一条回退**（ApiClient.swift `decodeResponse` 注释里已注明）：
   当 `response` 字符串既不是明文 JSON，`isEncrypted` 又未置位时，也会尝试
   AES 解密。PC 工具是无条件解密，DPGrabber 插件只在 `isEncrypted` 时解密；
   本实现取中间：明文优先，失败再解密。
2. **业务 code 的处理**：`decodeResponse` 只对缺 code、401/403、code 1 +
   msg 含 "IP" 抛错；其他非零 code（如 confirm 返回 code 2“已被抢”）原样
   返回，由确认状态机分类（终态失败 / 重试），而不是“请求异常”。
3. **配置加载的数值兼容**：`validated(from:)` 不用 `JSONSerialization` 做
   标量转换——Linux 的 swift-corelibs-foundation 会对标量调
   `data(withJSONObject:)` 直接 fatalError；改用直接类型转换 + NSNumber
   显式取数，行为等价。
4. **登录态刷新**：引擎在 randomList 返回 401/403 时重登一次；confirm
   返回 401/403 时释放订单、重登一次，任务结束（下一轮轮询可重新接该单）；
   IP 风控时暂停 60 秒、不重登，任务结束。

## 未证实项（需真号验证，同 PC/安卓版）

- **哪套 AES 是服务端当前用的**：两套密钥都已内置并自动切换，真号登录后
  看哪套解密成功即见分晓。
- **reTime / confirmCountDown 的服务端语义**：本 App 不读这两个字段
  （确认状态机不依赖它们），其语义不影响抢单。
- **randomList 是否正式接口**：PC 工具与插件都在用，但未确认是否为官方
  接口；若服务端下线或改版，监听会整体失败。
- **限流规则**：服务端对轮询频率、IP 的限制未知；0.5 秒默认间隔来自 PC 版，
  真号使用时若遇 429/IP 风控请调大间隔。
- **后台保活时长**：静音音频在真机后台能维持多久、是否会被系统挂起，
  只能真机实测（建议先做 5 分钟切后台 + 锁屏测试，再逐步加长）。
