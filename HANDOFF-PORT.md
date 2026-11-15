# 交接表：Android / iOS 端开发

> 用途：把 PC 端这一轮做出来的东西交给另一个工具继续写移动端。
> 我在这个工作区里审查、构建、推送。**只认仓库里的代码，不认聊天记录**。
>
> 最后更新：2026-11-15（PC 端构建 `92fe1d62`）

---

## 0. 一句话现状

| 端 | 状态 | 代码位置 | 能跑吗 |
|---|---|---|---|
| PC（Windows） | **功能完整**，已交付使用 | `pc/` | 是，`DPPP.exe` 51MB |
| Android | 骨架完整，**缺本轮全部新接口** | `android/` | 能编译，功能不全 |
| iOS | 骨架完整，**缺本轮全部新接口** | `ios/` | 能编译，功能不全 |

三端共用接口规格：**`PROTOCOL.md`**（已存在，102 个端点里只覆盖了抢单主链路）。
本轮新增的接口我在下面第 3 节补全，**`PROTOCOL.md` 里没有，必须按本节实现**。

---

## 1. 仓库与分支

```
仓库   github.com/doimty/dpgrabber-standalone   （private）
分支   main
CI     .github/workflows/build-pc.yml   → DPPP.exe
       .github/workflows/android.yml    → apk
       .github/workflows/ios.yml        → 未签名 ipa
```

**本机 git 到 github.com:443 时通时不通，但 api.github.com 稳定通。**
提交方式用 `gh api repos/.../contents/<path>` PUT（base64），不要依赖 `git push`。

目录职责：

```
pc/        Python + PySide6（已完成，作为功能基准）
android/   Kotlin + Gradle
ios/       Swift（SPM 核心 + xcodeproj 壳）
PROTOCOL.md  三端共用接口规格
```

---

## 2. 移动端现状（逐文件）

### Android（`android/app/src/main/java/com/doimty/dpgrabber/`）

| 文件 | 职责 | 状态 |
|---|---|---|
| `core/ApiClient.kt` | 登录、订单列表、订单详情、抢单 | 有，**缺新接口** |
| `core/Config.kt` | 配置结构与默认值 | 有，**缺新开关** |
| `core/Confirm.kt` | 抢单状态机（窗口内重试） | 有 |
| `core/Crypto.kt` / `data/OkHttpTransport.kt` | AES + 请求层 | 有 |
| `core/OrderFilter.kt` | 金额/备注/黑名单过滤 | 有 |
| `engine/ListenEngine.kt` | 轮询循环 | 有，**缺按店铺等待** |
| `service/ListenService.kt` | 前台服务保活 | 有 |
| `ui/MainActivity.kt` / `SettingsActivity.kt` | 界面 | 有 |
| `test/…ApiClientTest.kt` `EngineTest.kt` | 单测 | 有，需要同步扩展 |

### iOS（`ios/`）

| 文件 | 职责 | 状态 |
|---|---|---|
| `Sources/DPGrabberCore/ApiClient.swift` | 同上 | 有，**缺新接口** |
| `Sources/DPGrabberCore/Config.swift` | 配置 | 有，**缺新开关** |
| `Sources/DPGrabberCore/ListenEngine.swift` | 轮询 | 有，**缺按店铺等待** |
| `Sources/DPGrabberCore/ConfirmStateMachine.swift` | 抢单状态机 | 有 |
| `Sources/DPGrabberCore/OrderFilter.swift` | 过滤 | 有 |
| `App/ContentView.swift` `AppViewModel.swift` | SwiftUI 界面 | 有 |
| `Tests/DPGrabberCoreTests/*` | 单测 | 有，需要同步扩展 |
| `scripts/generate-xcodeproj.py` | 生成工程 | 有（**改工程文件请改脚本，不要手改 pbxproj**） |

---

## 3. 本轮新增接口（必须补到两端）

全部是**表单参数**（`application/x-www-form-urlencoded`），发 JSON 会报
`Required int parameter 'status' is not present`。
Base URL、AES、UA 见 `PROTOCOL.md` 第 1、3、4 节。

### 3.1 上线 / 下线

```
POST /s/c/clerk/updateStatus
status=1            1 上线，2 下线
updateAutoOnline=false
→ {"code":0,"msg":"成功"}
```

### 3.2 随机单开关

```
POST /s/c/clerk/updateRandomOrderSwitch
randomOrderSwitch=true|false
→ code 0 成功；部分店铺店员返回「没有操作权限」
```

### 3.3 接单类型

```
POST /s/c/clerk/getItemSwitchList            （空表单）
→ itemSwitchList: [{id, name, ...}], clerkItemSwitchIds: [465, ...]

POST /s/c/clerk/updateItemSwitch
switchId=<id>
switchIds=[465,2666]                          ← 注意：JSON 数组塞进字符串字段
```
`switchIds` 传逗号列表会报 `expect '[', but int`。

### 3.4 周统计（三个率的原始数据）

```
GET /s/c/statistics/statisticsClerkOrder?startTime=YYYY-MM-DD&endTime=YYYY-MM-DD
```
* **只接受日期**，带时分秒报 `Unable to parse the date`
* 返回字段：`orderCount` `continuedCount` `continuedUserCount`
  `continuedAmount` `orderAmount` 等

### 3.5 订单总数（统计失败时的兜底）

```
GET /s/c/order/list?pageNum=1&pageSize=1&startTime=YYYY-MM-DD HH:MM:SS&endTime=…
```
* 这个接口**要带时分秒**
* 读返回里的 `total`

### 3.6 会话复用（重要，不是接口而是行为）

PC 端实测：**界面登录一次后，引擎启动时复用同一个 session**，不要再登录一次。

```
日志证据：
[15:09:21] 已登录：加冰（ID：230313），店铺 sid=92
[15:09:24] 复用已登录会话，店铺 sid=92     ← 引擎直接用这个
```

好处：少一次登录 + 少一轮店铺探测（每次探测会对无权限的店铺各吃一个 401）。

---

## 4. 业务规则（三端必须一致）

### 4.1 三个率的定义

| 名称 | 公式 | 卡什么 |
|---|---|---|
| **续费率** | `continuedAmount / orderAmount`（**金额**比） | **卡接单**：低于 30% 平台拒绝派随机单 |
| **续单率** | `continuedCount / orderCount`（**单数**比） | 平台按此发工资 |
| **续客率** | `continuedUserCount / orderCount`（**人数**比） | 参考指标 |

三者**不是一个东西**，界面必须分开显示、标题写清。PC 端踩过这个坑：
把金额比标成「续单率」，被用户当场指出。

平台拒绝文案原文（用于解析）：

```
本周订单8单，当前续费率24%，需要达标续费率30%，可继续抢随机单，请提升续费率后，再来接随机单
```

### 4.2 抢单等待按店铺不同（关键）

平台倒计时**不是全局一致的**。PC 端实测数据：

| 店铺 | 目标等待 |
|---|---|
| 92 | 5.0 s |
| 50 / 105 / 1391 | 3.0 s |
| 其他 | 用全局默认 |

实现要求：等待时长 = **目标值 − 已流逝时间**，已流逝时间从订单的
`createTime` 算起（不是从"收到订单"算起）。PC 端实现见
`pc/engine.py: confirm_wait_for_order()`：

```python
table = cfg["confirm_wait_by_shop"]              # {"92": 5.0, "50": 3.0, ...}
target = table.get(str(shop_id)) or confirm_wait_seconds(cfg, shop_id, rng)
elapsed = (now_ms - order["createTime"]) / 1000
wait = max(0, target - elapsed)
```

失败原因与调法：
* 日志出现「跑走了」→ 等待偏长，减 0.5
* 日志出现「未满足抢单倒计时」→ 等待偏短，加 0.5

### 4.3 轮询节奏

`0.6s ± 0.2s`（即 0.4–0.8 秒）。PC 端从 0.5±0.25 调过来：
确认时机已改为按 `createTime` 计算，轮询快慢不影响命中点，慢一点更不像机器。

### 4.4 黑名单 / 备注关键词

* 黑名单按 `userWechatId` 精确匹配，命中不抢
* 关键词按 `userMemo` 包含匹配
* **两者都必须能在运行中生效**，不能是启动时快照。
  PC 端每轮轮询重建过滤器（`build_filter()`），所以界面上拉黑立刻有效。

### 4.5 刷新与休息（风控节奏）

可选项，开启后：轮询一个随机"运行时长"后休息一个随机"休息时长"，循环。
PC 默认：刷新 50–60 分钟，休息 10–20 分钟。

### 4.6 同时接单上限（实测出现在店铺 92，数量随账号等级变化，**必做**）

平台原话：

```
同时接单数超过限制的2单
```

语义：**当前账号在该店铺最多能同时持有几单随机单**（实测店铺 92 为 2 单），
数量随账号等级变化。名额占满时，任何新单在确认时都会返回这句话。

> 这条规则的归属（店铺 / 账号 / 等级）平台没有明说，实测只出现在店铺 92。
> **所以唯一正确的做法是把数字从文案里解析出来，任何写死 2 的实现都是错的。**

必须这样处理：

1. 视为**不可重试**——立刻结束本轮确认（PC 端实测：不处理会在一个窗口里
   白白重试 28 次，下一轮又重复一遍）
2. 记一条历史，结果归 `skipped`（跳过），原因写平台原话
3. 暂停轮询若干秒（PC 默认 `busy_cooldown_sec = 60`），等名额释放
4. 上限数字从文案里**解析**（`超过限制的(\d+)单`），不要写死 2
5. 日志要能说明白：「已达本等级同时接单上限（2 单），本次跳过；完成一单后可继续」

### 4.6 终态判定

订单离开确认流程时归类，写历史：

| 结果 | 触发 |
|---|---|
| success | 确认返回成功 |
| failed | 平台明确拒绝（续费率不足等） |
| risk | IP/风控类错误 |
| unknown | 超时、网络中断、结果不明 |
| skipped | 被别人抢走（「跑走了」「跑掉」「已被接单」等） |

---

## 5. 移动端待办清单

### 5.1 Android

- [ ] `ApiClient.kt` 增加 3.1–3.5 五个接口（含表单编码与错误码处理）
- [ ] `Config.kt` 增加 `confirm_wait_by_shop`、`rotate_*`、`online_after_login`
- [ ] `ListenEngine.kt` 等待改按店铺表 + `createTime` 计时
- [ ] `ListenEngine.kt` 支持复用已有会话（登录后启动不再重复登录）
- [ ] `MainActivity` 增加：上线/下线开关、接单类型勾选、今日概况（成功/失败/跳过/未知）
- [ ] `SettingsActivity` 增加：按店铺等待表、轮换开关
- [ ] 历史列表长按 → 加入黑名单 / 备注转关键词 / 复制订单号
- [ ] `ApiClientTest.kt` 补新接口的请求体断言（form 编码、日期格式、JSON 数组字符串）

### 5.2 iOS

- [ ] `ApiClient.swift` 同上五个接口
- [ ] `Config.swift` 同上三个字段
- [ ] `ListenEngine.swift` 按店铺等待 + 会话复用
- [ ] `ContentView` / `SettingsView` 同上界面项
- [ ] 历史长按菜单（SwiftUI `contextMenu`）
- [ ] `ApiClientTests.swift` 同步补测试
- [ ] 改工程文件只能改 `scripts/generate-xcodeproj.py`，然后跑
      `python scripts/generate-xcodeproj.py` + `python scripts/check-xcodeproj.py`

### 5.3 两端都要注意的平台差异

| 项 | Android | iOS |
|---|---|---|
| 后台保活 | 前台服务 + 通知常驻（已实现） | 静音音频循环（`AudioKeepAlive.swift` 已实现） |
| 通知 | 通知渠道 + 抢单成功推送 | 本地通知 |
| 凭据存储 | `CredentialStore.kt`（Keystore） | `CredentialStore.swift`（Keychain） |
| 主题 | 跟随系统深/浅 | 同 |

---

## 6. 这一轮踩过的坑（照抄，别再犯）

### 6.1 接口层

1. **必须 form 编码**。JSON 会报 `Required int parameter 'status' is not present`。
2. **`switchIds` 是"JSON 数组塞进字符串"**，不是逗号列表。
3. **统计接口只吃日期，订单列表必须带时分秒**，两者相反。
4. 无权限的店铺会在探测阶段返回 401 `login state invalid`，**这是正常的**，
   不要当成登录失败中止流程。
5. 会话落在 `data.shopId`，不要硬编码（曾把 sid 写死成 50，另一个账号直接 401）。

### 6.2 业务层

6. **续费率是金额比**。混淆过一次，界面标题必须写对。
7. **等待时长按店铺不同**，全局单值一定有一半场景是错的。
8. **自动停止监听 / 在线接单这类开关必须即时落盘**，
   只在「保存设置」里写会导致每次启动回到默认值（PC 端被用户投诉过）。
9. **成功计数要来自持久化历史**，不能只用内存计数器，否则重启后归零。
9b. **「同时接单数超过限制的N单」是个人等级上限，不是可重试错误**。
    按可重试处理会把窗口耗光、把请求打满；详见 4.6。

### 6.3 UI 层（PC 的教训，移动端同理）

10. 系统原生对话框（`QInputDialog`/`QMessageBox`/`AlertDialog`）会被应用主题染色，
    出现"白字浅底"看不见按钮。**要么完全用自定义样式，要么别改全局按钮样式**。
11. 右键/长按菜单挂在不接收事件的子控件上会失效。PC 端历史列表是
    `setItemWidget` 整块控件，右键被吃掉，菜单从来不弹。
12. 列表高度按内容算，别写死；PC 端侧栏曾溢出被裁 46px。

### 6.4 构建 / 发布

13. iOS 图标在设备上是 CgBI 编码，Pillow 读不了；要图标就去 App Store 的
    `artworkUrl512`（bundle id `com.featnet.peipei.shop`）。
14. Windows 任务栏图标要显式 `setWindowIcon`，光靠 exe 内嵌资源在某些情况下会回退成默认图标。
15. Windows 图标有缓存，替换后资源管理器可能还显示旧的（`ie4uinit.exe -show`）。

---

## 7. 提交与审查流程

### 7.1 你的提交流程

1. 只在 `android/` 和 `ios/` 目录里改（**不要动 `pc/`**，那是已交付的基准实现）
2. 提交到 `main`，或开 `feat/android-port` / `feat/ios-port` 分支（推荐后者）
3. 提交信息格式：`android: <做了什么>` / `ios: <做了什么>`，一行说清
4. 提交后告诉我，我审代码 → 跑 CI → 有 CI 产物我再决定是否替换本机安装

**不要自己打包发给用户，也不要改 `pc/` 或 CI 配置**。

### 7.2 我审查什么

| 检查项 | 判定标准 |
|---|---|
| 接口正确性 | 请求方法、编码、参数名、日期格式逐字核对第 3 节 |
| 会话复用 | 登录后启动监听，日志里**不应**出现第二次完整登录 |
| 等待逻辑 | 92 店 ≈5s、50/105/1391 ≈3s，且从 `createTime` 计时 |
| 三个率 | 公式与标题一致，金额比/单数比不混 |
| 配置持久化 | 改开关 → 杀进程 → 重开，值还在 |
| 测试 | 新增接口必须有单测；`./gradlew test` / `swift test` 全绿 |
| 平台规范 | Android 不要 ANR 级阻塞主线程；iOS 不要在主线程发网络请求 |

### 7.3 CI

```
build-pc.yml  →  PyInstaller            →  产物 DPPP.exe
android.yml   →  ./gradlew testDebugUnitTest
                 ./gradlew assembleDebug →  产物 DPGrabber-android-debug（apk）
ios.yml       →  macos-15 + Xcode 16.4
                 python scripts/check-xcodeproj.py
                 swift test（SwiftPM 核心库）
                 xcodebuild 未签名 → 产物 DPGrabber-ios-unsigned（ipa）
```
CI 只保证**编译与单测通过**，功能对不对要我人工核。
注意：移动端产物名还是 `DPGrabber-*`，PC 端已改名 `DPPP`；要不要统一等你定。

---

## 8. 验收清单（每端逐条打勾）

功能面：

- [ ] 登录成功，界面显示店员名/ID/店铺 sid
- [ ] 显示 本周接单 / 续费率 / 续单率（三个数，标题正确）
- [ ] 显示 今日 成功/失败/跳过/未知（重启后不归零）
- [ ] 上线/下线切换，文字即时变化，失败回滚
- [ ] 接单类型勾选，改动立刻生效
- [ ] 开始监听后轮询间隔 0.4–0.8s
- [ ] 92 店等待 ≈5s，50/105/1391 ≈3s
- [ ] 抢单成功后写历史，今日成功 +1
- [ ] 历史长按可拉黑/加关键词/复制订单号
- [ ] 拉黑后下一轮轮询即不再命中该客户
- [ ] 监听期间锁屏/切后台不掉线

工程面：

- [ ] `./gradlew test` 或 `swift test` 全绿
- [ ] CI 两个 job 全绿
- [ ] 没有硬编码的账号、密码、sid、token
- [ ] 日志不打印完整手机号/微信号（脱敏）

---

## 9. 需要我提供的

- 真实测试账号：**16637393776**（店员 加冰 / ID 230313 / 店铺 92），
  密码走 MD5 后提交，配置里存的是 32 位哈希，不要明文落盘
- 测试店铺：92（有权限）、50 / 105 / 1391（探测会 401，属正常）
- 需要真机验证时告我，我这边有 iPhone 13 Pro Max（iOS 15.1.1，越狱）可配合

---

## 10. 附：PC 端代码索引（要抄逻辑就照这些文件）

| 逻辑 | 文件 | 函数 |
|---|---|---|
| 登录 + 店铺探测 | `pc/dpapi.py` | `session_for_account` / `_probe_shop_permission` |
| 加密 | `pc/dpapi.py` | `_decrypt` |
| 等待计算 | `pc/engine.py` | `confirm_wait_for_order` |
| 轮询 + 过滤 | `pc/engine.py` | `_poll_loop` / `OrderFilter` |
| 确认状态机 | `pc/engine.py` | `_confirm_one` / `TERMINAL_WORDS` |
| 统计三个率 | `pc/ui.py` | `_on_week_orders` |
| 配置与默认值 | `pc/config.py` | `DEFAULT_CONFIG` / `validate` |
| 每账号配置文件 | `pc/accounts.py` | `load_account` / `save_account` |
| 历史条目结构 | `pc/ui.py` | `_history_entry_for_order` |
