# DPGrabber Android — 独立版多陪抢单程序（安卓版）

独立 Android 应用，直接调用多陪接口抢单，不注入、不依赖宿主 App。
登录用手机号 + 密码（PROTOCOL.md §3.2），监听、过滤、精确抢单时机与
抢单提交按 `../PROTOCOL.md` 实现，与 PC 版同款协议、同款功能。

## 功能

- **账号登录**：手机号 + 密码 → token（sid 固定 `"50"`）→
  `list-shop-account` → `switch-account` → `getBalance` 验证。
  401/403 时用保存的账号自动重新登录。token 只存内存。
- **账号密码存储**：EncryptedSharedPreferences（AES-256-GCM，密钥在
  Android Keystore）。加密存储不可用时回退到普通 SharedPreferences，
  界面会显示警告。
- **监听引擎**：`randomList` 轮询（pageSize 20），只处理列表第一条；
  去重（60 秒 TTL）、黑名单微信号、金额上下限（0 不限制）、备注模式
  与关键词（大小写不敏感子串）过滤；通过的订单取 `reTime` +
  `confirmCountDown` 算出准确抢单时间，等到该时刻再 `confirm` 提交
  一次；成功自动停止监听。
- **保活**：监听跑在前台 Service 里（带常驻通知），切后台/锁屏后仍
  继续轮询。想更稳：系统设置里给本应用关掉电池优化。
- **设置**：监听规则在主界面保存；店铺 ID、超时、AES 密钥在高级设置
  里改。两套 AES 密钥（DPGrabber AES-256 主、PC 工具 AES-128 备）
  自动切换，以能解密为准。

## 构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可以用 GitHub Actions（见 `../.github/workflows/android.yml`，推到
GitHub 后自动编出 debug APK 并上传为 artifact）。

本地构建说明见 `build-android.md`（含代理环境注意事项）。

## 目录

| 位置 | 说明 |
|---|---|
| `app/src/main/java/com/doimty/dpgrabber/core/` | 纯 Kotlin 核心：API 客户端、加解密、过滤、精确时机、配置（不依赖 Android 框架，可 JVM 单测） |
| `app/src/main/java/com/doimty/dpgrabber/engine/` | 监听引擎（轮询、去重、精确等待、提交） |
| `app/src/main/java/com/doimty/dpgrabber/data/` | OkHttp 传输、凭证存储（加密 SharedPreferences） |
| `app/src/main/java/com/doimty/dpgrabber/service/` | 前台 Service（保活）与运行日志总线 |
| `app/src/main/java/com/doimty/dpgrabber/ui/` | 界面：主界面（登录 / 设置 / 监听 / 日志）、高级设置 |
| `app/src/test/` | 离线单测（打桩 transport，不发真实请求） |

## 测试

```bash
./gradlew testDebugUnitTest
```

单测覆盖：AES 加解密（两套密钥、错误输入）、响应信封解密与回退、
登录流程（优先店铺、换店重试）、请求头与表单编码、过滤规则全分支、
精确抢单时机、配置校验、引擎整轮（抢单成功停止、过滤跳过、401 重登、
确认失败不停止、去重）。

### 本机验证状态（2026-11-12）

- **单测 59 个全部通过**：kotlinc 2.0.20 直接编译核心 + 引擎 + 单测，
  JUnit 4.13.2 运行，`OK (59 tests)`，无真实请求。
- **全部主源码编译通过**：kotlinc 直接编译 `core / engine / data /
  service / ui` 全部 15 个 Kotlin 文件，对 android.jar（API 34）+
  appcompat / material / security-crypto 等 AndroidX 依赖做类型检查，
  0 错误（ViewBinding 与 R 类用与布局同字段的存根代替，AGP 构建时
  由真实生成类替换）。
- **资源校验**：res/ 中 85 处 `@string/@array/@color/@drawable/@style`
  引用全部存在；启动图标含 API 24–25 回退 PNG（五档密度，方形+圆形）。
- **本机未编译出 APK**：本沙箱拦截跨进程回环 TCP，Gradle daemon
  无法启动（`Could not receive a message from the daemon`），`./gradlew`
  本地无法运行。APK 改走 `.github/workflows/android.yml`（见下），
  待 CI 验证。
- **待 CI 验证**：`./gradlew testDebugUnitTest` 与
  `./gradlew assembleDebug` 在 GitHub Actions（ubuntu-latest，JDK 17）
  上的结果；`gradlew` 与 `gradle-wrapper.jar`（Gradle 8.7）已放入工程。

## CI（GitHub Actions）

`../.github/workflows/android.yml`：推到 GitHub 后自动跑
`./gradlew testDebugUnitTest` 与 `./gradlew assembleDebug`，产物
`DPGrabber-android-debug`（debug APK）与单测报告随 run 上传为
artifact。触发条件：`android/**` 有改动的 push / PR，或手动触发。

## 与规格书的出入（有意为之）

- 响应解密：按 DPGrabber 的信封规则（`isEncrypted` 标志），但 `response`
  字符串解析为明文 JSON 失败时也会尝试 AES 解密（与 PC 版一致）。
- 轮询 `pageSize` 取 20（DPGrabber），参考工具取 10。
- 订单去重（seen 集合，60 秒 TTL）来自 DPGrabber；参考工具无去重。
- TLS 证书默认验证；参考工具监听会话关闭验证，本程序不采用。
- 本程序无语音播报（PC 版有）；后续可接 Android TextToSpeech。

## 未证实（见 PROTOCOL.md §10.6）

- 两套 AES key/IV 哪一套是服务端当前使用的：程序先试主密钥（DPGrabber
  AES-256 套），失败再试备用密钥（PC AES-128 套），以能解密为准。
  上线前必须以真实接口验证。
- `reTime` / `confirmCountDown` 的服务端语义、`randomList` 是否为正式接口、
  服务端限流阈值与 IP 规则：源码中未证实。

## 隐私与安全

- 账号密码只存在本机加密存储里，不上传到任何地方；token 只在内存中。
- 配置里的 AES 密钥是协议层面的默认值（DPGrabber 与参考工具公开的
  两套），不是你的账号凭证；不要把自己的账号密码写进代码或发给别人。
