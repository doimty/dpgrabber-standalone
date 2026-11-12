# 构建 iOS 版（build-ios.md）

## 1. 跑单测（Linux 或 macOS）

只需要 Swift 工具链（5.9+；本机验证用的是 Swift 6.1.2 Linux 版）。

```sh
cd ios
swift test
```

预期：56 个测试，0 失败。单测只覆盖 `Sources/DPGrabberCore/` 与
`Tests/DPGrabberCoreTests/`，全部打桩，不发任何真实请求。

## 2. 本地编 App（需要 macOS + Xcode 15+）

```sh
cd ios
# 先校验手写工程的结构（需要 pip install pbxproj）
python3 scripts/check-xcodeproj.py

# 编未签名 Release 包（真机安装前需重签）
xcodebuild -project DPGrabber.xcodeproj \
  -scheme DPGrabber \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

产物在 `build/Release-iphoneos/DPGrabber.app`。模拟器调试：

```sh
xcodebuild -project DPGrabber.xcodeproj \
  -scheme DPGrabber \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 15' \
  build
```

或直接在 Xcode 里打开 `DPGrabber.xcodeproj` 运行。

> 注意：`CODE_SIGNING_ALLOWED=NO` 的包**不能直接装真机**，必须重签。
> 本地有开发者证书时，去掉该参数、把 CODE_SIGN_STYLE 改回 Automatic
> 即可正常签名（工程默认 Manual 是为了让 CI 能打出未签名包）。

## 3. CI 打未签名 IPA（推荐）

推送到 `main`（改动 `ios/**` 时）即触发
`../.github/workflows/ios.yml`（macos-15）：

1. `pip install pbxproj` + `scripts/check-xcodeproj.py` 校验工程；
2. `swift test` 跑 56 个单测；
3. `xcodebuild` 编 Release 未签名 App；
4. 打包 `DPGrabber-unsigned.ipa`，上传为 artifact
   `DPGrabber-ios-unsigned`（保留 14 天）。

从 Actions 页面下载 artifact，解压即得未签名 IPA。

## 4. 重签与安装

用你自己的签名工具对未签名 IPA 重签（与 DPGrabber 插件注入宿主 App
时走签名工具是同一类流程，只是这里签的是我们自己的独立 App）：

1. 解压 IPA，得到 `Payload/DPGrabber.app`；
2. 签名工具重签（Bundle ID 保持 `com.doimty.dpgrabber`，或按工具要求改；
   若改 Bundle ID，钥匙串 service 不受影响——钥匙串按 service 字符串
   `com.doimty.dpgrabber` 区分，与 Bundle ID 无关）；
3. 安装到手机，首次打开按提示信任证书。

## 5. 首次使用检查清单

- [ ] 填手机号 + 密码，点“保存账号”（进钥匙串，可在“设置”里确认
      不会出现在配置文件里）。
- [ ] 高级设置确认店铺 ID（默认 92）与 AES 密钥（默认两套都已填好）。
- [ ] 点“开始监听”，看日志出现“登录成功，店铺 sid=50”。
- [ ] 观察一轮轮询：有单时出现“检测到新订单”，无单时无报错。
- [ ] 切后台 5 分钟 + 锁屏，回来确认仍在监听（保活验证）。
- [ ] 有可抢的单时，确认在精确时刻 confirm、成功后自动停止并收到
      本地通知。

## 6. 重新生成 Xcode 工程

`DPGrabber.xcodeproj/project.pbxproj` 是手写生成的（本机没有 Xcode）。
改了文件增减后重新生成：

```sh
cd ios
python3 scripts/generate-xcodeproj.py   # 确定性 ID，重复运行结果一致
python3 scripts/check-xcodeproj.py      # 19 项检查，全过才能提交
```

生成器在 `scripts/generate-xcodeproj.py`，文件列表（核心 8 个 +
App 层 8 个 + 资源 + 3 个系统 framework）写在脚本顶部，增减文件
时同步改那里。

## 7. 常见问题

- **CI 的 `xcode-select` 失败**：工作流里 `sudo xcode-select -s
  /Applications/Xcode_16.4.app || xcodebuild -version`，若 runner 的
  Xcode 路径变了，改这行即可。
- **check-xcodeproj.py 报错缺少 pbxproj**：CI 里已 `pip3 install pbxproj`；
  本地跑同样先装一次。
- **TLS 开关**：默认开启证书校验；只有排障抓包时才关，抓完请马上
  开回来。
- **密码输错**：登录被拒时日志会写“登录 rejected”，不会锁号；
  但请勿高频重试。
