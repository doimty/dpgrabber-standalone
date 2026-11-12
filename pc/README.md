# DPGrabber PC — 独立版多陪抢单程序（电脑版）

独立桌面程序，直接调用多陪接口抢单，不注入、不依赖宿主 App。
登录用手机号 + 密码（PROTOCOL.md §3.2），监听、过滤、确认状态机与
抢单提交按 `../PROTOCOL.md` 实现。

## 运行

```bat
pip install -r requirements.txt
python dpgrabber_pc.py
```

首次运行会在程序旁边生成 `dpgrabber_config.json`（模板，账号密码为空）。
在界面里填写手机号和密码后点“登录”验证，再设置监听规则、点“开始监听”。

## 配置文件（`dpgrabber_config.json`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `phone` / `password` | `""` | 登录账号；密码只存本地配置文件，不上传到别处 |
| `shop_id` | `92` | 登录后优先切换的店铺 |
| `min_amount` / `max_amount` | `10` / `0` | 金额范围（元），`max_amount` 为 0 表示不限制 |
| `random_confirm_window_sec` | `20` | 确认窗口（秒）：订单有 `createTime` 时用它（0.1–86400） |
| `random_confirm_fallback_sec` | `20` | 确认窗口（秒）：订单无 `createTime` 时用它（0.1–86400） |
| `confirm_retry_delay_sec` | `0.5` | 确认重试间隔（秒），0–60 |
| `poll_interval_sec` | `0.5` | 空轮询间隔（秒） |
| `memo_mode` | `0` | `0` 不检测备注，`1` 有备注不抢 |
| `memo_keywords` | `[]` | 备注命中任一关键词就不抢（大小写不敏感的子串匹配） |
| `blacklist` | `[]` | 黑名单微信号 |
| `aes_key_hex` / `aes_iv_hex` | DPGrabber AES-256 套 | 主加解密密钥（十六进制），可在高级设置里改 |
| `aes_key_hex_alt` / `aes_iv_hex_alt` | PC 参考工具 AES-128 套 | 备用密钥；主密钥解密失败时自动尝试 |
| `request_timeout_sec` | `15` | 请求超时（秒） |
| `verify_tls` | `true` | 验证服务器 TLS 证书（建议保持开启） |
| `voice_enabled` | `true` | 语音播报（需要本机语音支持，否则自动静默） |
| `seen_ttl_sec` | `60` | 订单去重记忆（秒） |

配置文件在 POSIX 系统上以 0600 权限保存。请妥善保管：文件里有账号密码。

## 工作流程

1. 登录：`POST /s/duopei-account/login` 换 token（sid 固定 `"50"`），
   再 `list-shop-account` → `switch-account` → `getBalance` 验证，建成
   可用会话。401/403 时用保存的账号自动重新登录。
2. 监听：`GET /s/c/order/randomList` 轮询，只处理列表第一条；去重后按
   金额、黑名单、备注规则过滤。
3. 确认任务（DPGrabber 的确认状态机，PROTOCOL.md §8.2）：通过的订单
   立即开始确认，不读详情、不等精确时刻、不做用户延时。在窗口内循环
   `POST /s/c/order/confirm`（每 0.5 秒重试一次，直到截止）；成功文案
   或 code 0 即成功，终态文案即失败，网络错误用 `GET /s/c/order/get`
   回读核验；截止时最后核验一次，仍待确认则释放订单供下一轮重抢。
   抢单成功后自动停止监听。
4. 失败处理：IP 风控暂停 60 秒；其他错误等一个轮询间隔后继续。

## 与规格书的出入（有意为之，均已在代码注释中标明）

- 抢单逻辑采用 DPGrabber 的确认状态机（PROTOCOL.md §8.2：窗口内立即发、
  0.5 秒重试至截止、回读核验），**不采用** PC 参考工具的精确时刻等待
  （`reTime + confirmCountDown`）与 `user_delay`（已删除）；`order/get`
  只用 GET 形式作核验，不再用 POST 取详情。
- 响应解密：按 DPGrabber 的信封规则（`isEncrypted` 标志），但 `response`
  字符串解析为明文 JSON 失败时也会尝试 AES 解密（参考工具是无条件解密）。
- 轮询 `pageSize` 取 20（DPGrabber），参考工具取 10。
- 订单去重（seen 集合，60 秒 TTL）来自 DPGrabber；参考工具无去重。
- TLS 证书默认验证；参考工具监听会话关闭验证（`verify=False`），本程序不采用。
- 语音播报是可选功能；`pyttsx3` 不可用时自动静默，不影响抢单。

## 未证实（见 PROTOCOL.md §10.6）

- 两套 AES key/IV 哪一套是服务端当前使用的：程序先试主密钥（DPGrabber
  AES-256 套），失败再试备用密钥（PC AES-128 套），以能解密为准。
  上线前必须以真实接口验证。
- `reTime` / `confirmCountDown` 的服务端语义：本程序不读这两个字段
  （确认状态机不依赖它们），其语义不影响抢单；`randomList` 是否为
  正式接口、服务端限流阈值与 IP 规则：源码中未证实。

## 目录

| 文件 | 说明 |
|---|---|
| `dpgrabber_pc.py` | 入口（`--smoke-test` 只做界面冒烟测试，不发请求） |
| `dpapi.py` | API 客户端：会话、登录、加解密、请求封装 |
| `engine.py` | 监听引擎：轮询、过滤、确认状态机、提交 |
| `config.py` | 配置加载、校验、保存（0600） |
| `ui.py` | tkinter 界面 |
| `voice.py` | 可选语音播报（pyttsx3，不可用时静默） |
| `tests/test_smoke.py` | 离线单测（打桩 transport，不发真实请求） |
| `build-pc.md` | Windows 打包说明（PyInstaller） |
