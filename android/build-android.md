# 构建 DPGrabber Android

## 环境要求

- JDK 17（AGP 8.5 需要；`java -version` 应显示 17）
- Android SDK：platform `android-34`、build-tools `34.0.0`
  （`local.properties` 里写 `sdk.dir`，或设置 `ANDROID_HOME` /
  `ANDROID_SDK_ROOT` 环境变量）
- Gradle 8.7（仓库自带 wrapper，首次运行自动下载）

## 本地构建

```bash
cd android
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。
装到手机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。

跑单测：

```bash
./gradlew testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

出 release 包（R8 混淆开启，见 `app/proguard-rules.pro`）：

```bash
./gradlew assembleRelease
```

release 包默认未签名；正式分发前请用自己的 keystore 签名：

```bash
# 示例：生成 keystore（只做一次，妥善保管）
keytool -genkeypair -v -keystore dpgrabber-release.keystore \
  -alias dpgrabber -keyalg RSA -keysize 2048 -validity 10000
```

## 代理环境注意

如果本机上网走 HTTP(S) 代理（如沙箱、公司网络），Gradle 需要代理
配置才能下载依赖。把代理写进 `~/.gradle/gradle.properties`
（不要写进工程目录，以免提交到仓库）：

```properties
systemProp.http.proxyHost=代理主机
systemProp.http.proxyPort=代理端口
systemProp.https.proxyHost=代理主机
systemProp.https.proxyPort=代理端口
systemProp.http.nonProxyHosts=localhost|127.0.0.1
```

需要账号密码的代理另加 `systemProp.http.proxyUser` /
`systemProp.http.proxyPassword`（同理只放 `~/.gradle/`）。

## 常见问题

- `SDK location not found`：新建 `android/local.properties`，写一行
  `sdk.dir=/你的/Android/SDK/路径`（该文件已在 `.gitignore` 中）。
- Gradle daemon 在受限沙箱里可能因本机回环 TCP 被拦截而无法启动
  （表现为 `Could not receive a message from the daemon`）。本工程在
  这类环境下改走 GitHub Actions 编 APK（见 `.github/workflows/`），
  本地仍可用 `kotlinc` 直接编译核心并跑单测（见 `README.md` 的测试一节）。
- `EncryptedSharedPreferences` 需要设备 Keystore 正常；模拟器或个别
  定制 ROM 上创建失败时会自动回退到普通 SharedPreferences，界面会
  显示警告。
