# 多陪抢单接口协议规格（PC / Android / iOS 三端共用）

> 来源：静态分析，未运行任何 exe，未登录真实接口，未做任何真实请求。
> 资料 A：DPGrabber iOS tweak 源码 `~/workspace/dpgrabber-repo/Sources/`（v1.4.1）。
> 资料 B：PC 版参考工具主脚本 `DP.py`（PyInstaller 打包的 `DP.pyc`，Python 3.14，
> 由 `marshal` + Python 3.14 `dis` 反汇编读取；行号为 `DP.py` 源码行号）。
> 本文档只记录协议事实（URL、方法、参数、请求头、加解密、字段、流程、返回码）。
> 不复制 PC 工具的界面文案与品牌信息；凡未在源码中找到的，写明“未在源码中找到”；
> 凡未证实的，写明“未证实”。
> 注意：两份资料的 AES key/IV **不一致**（见 §3），新客户端上线前必须以真实接口验证。

## 0. 总览与差异速查

| 维度 | DPGrabber（iOS 进程内插件） | PC 参考工具（独立桌面程序） |
|---|---|---|
| 运行形态 | 注入宿主 App 进程内，观测宿主自身的会话 | 独立进程，用户手动输入 Token 登录 |
| Base URL | `https://api.duopei.feiniaowangluo.com` | 相同 |
| 会话来源 | 观测宿主 HTTPS 请求头，或手动账号密码登录 | Token 手动输入 + `clerk/get` 校验 |
| 轮询接口 | `GET /s/c/order/randomList?pageNo=1&pageSize=20` | `GET /s/c/order/randomList?pageNo=1&pageSize=10` |
| 轮询间隔 | 均匀随机 2–3 s（默认） | 固定 `ListenDelay`，默认 0.5 s；命中订单后立即重 poll |
| 抢单时机 | 窗口内立即发、0.5 s 重试至截止 | 等到 `reTime + confirmCountDown` 精确时刻，单次提交 |

> **独立版三端（PC / Android / iOS）采用 DPGrabber 的抢单模型**（上表左列，
> 详见 §8.2 的确认状态机），**不采用** PC 参考工具的精确时刻等待（§8.1，仅作
> 资料记录）。独立版也不用 `user_delay`（已删除）。
| AES | AES-256-CBC，key 32 字节 | AES-128-CBC，key 16 字节（key/IV 均可配） |
| 过滤 | 仅最低金额 | 金额上下限、备注模式、备注关键词、黑名单微信号 |
| 去重 | 有（seen 集合 + TTL） | 无 |
| 429 处理 | 无专门处理 | 无专门处理 |

> **独立版登录方式（已定）：手机号 + 密码**，采用 DPGrabber 手动模式的现成流程
> （`loginAccount:` → `sessionForAccount:`，见 §3.2）。PC 参考工具的“贴 Token
> 登录”只是它自己的做法，**不采用**；独立版三端（PC / Android / iOS）统一用
> 手机号 + 密码登录。`app_session` 观测模式依赖注入宿主进程，独立版不可用。

---

## 1. Base URL 与通用规则

- Base URL：`https://api.duopei.feiniaowangluo.com`
  （DPGrabber：`DPGAPIClient.m:8`；PC：`DP.py` 各请求硬编码同一 host）。
- 所有业务路径都以 `/s/` 开头。DPGrabber 的请求封装会拒绝非 `/s/` 开头的路径，
  并校验 host 必须等于 `api.duopei.feiniaowangluo.com`
  （`DPGAPIClient.m:225`、`DPGAPIClient.m:230-233`）。
- 响应信封（两端一致，见 §4.2）：外层 JSON 含 `response` 字段；`response` 为字符串时
  是加密或明文 JSON 文本，为对象时直接使用。
- 业务状态码字段为 `code`，成功为 `0`（两端均以 `code == 0` 判成功）。
  DPGrabber 要求 `code` 必须存在且可解析为整数（数字或数字字符串均可），
  缺失/非法一律视为错误（`DPGAPIClient.m:29`、`DPGAPIClient.m:144-149`）。

## 2. 接口清单

### 2.1 登录与会话类

| # | 方法 | 路径 | 用途 | 谁用 |
|---|---|---|---|---|
| 1 | POST | `/s/duopei-account/login` | 手动账号密码登录，换 token | DPGrabber 手动模式；独立版采用 |
| 2 | GET | `/s/duopei-account/list-shop-account` | 列出可切换店铺账号 | DPGrabber 手动模式；独立版采用 |
| 3 | POST | `/s/duopei-account/switch-account` | 切换店铺，换 token + sid | DPGrabber 手动模式；独立版采用 |
| 4 | GET | `/s/balance/getBalance` | 校验会话有效（code==0 即有效） | DPGrabber；独立版采用（会话校验） |
| 5 | GET | `/s/duopei-account/get-phone-number` | 取当前账号手机号（仅展示用） | 仅 DPGrabber |
| 6 | GET | `/s/c/clerk/get` | 取店员信息（id / shopId / name），兼作登录校验 | 两端都用 |

#### 2.1.1 `POST /s/duopei-account/login`（DPGrabber 手动模式；独立版采用）

- 请求体：JSON（`DPGAPIClient.m:278-279`）；密码字段优先取 `password_hash`，
  为空时取 `password`（`DPGAPIClient.m:272-273`）

```json
{"method": "password",
 "phoneNumber": {"regionCode": "CN", "mobileDial": "86", "mobile": "<手机号>"},
 "password": "<密码或 password_hash>"}
```

- 额外请求头：`sid: -1`、`authorization-token: `（空）（`DPGAPIClient.m:281-283`）。
- 超时 15 s（`DPGAPIClient.m:281-283`）。
- 成功条件：`code == 0` 且 `data.token` 非空；返回会话 token，sid 固定为 `"50"`
  （`DPGAPIClient.m:285-295`）。
- 失败：`code != 0` 或无 token → 认证错误，不继续。

#### 2.1.2 `GET /s/duopei-account/list-shop-account`（DPGrabber 手动模式；独立版采用）

- 无请求体；请求头 `sid: 50`，用登录所得 token（`DPGAPIClient.m:313-314`）。
- 超时 15 s。成功条件 `code == 0`；`data` 为店铺数组，每项含 `shopId`、`accountId`
  （`DPGAPIClient.m:313-320`）。
- 客户端优先尝试配置的 `shop_id`（默认 `92`），其余店铺随后依次尝试
  （`DPGAPIClient.m:312`、`DPGAPIClient.m:321-327`）。

#### 2.1.3 `POST /s/duopei-account/switch-account`（DPGrabber 手动模式；独立版采用）

- 请求体：`application/x-www-form-urlencoded`，表单字段 `accountId`
  （`DPGAPIClient.m:333-335`；表单编码规则见 §2.5）。
- 请求头 `sid: 50`，用登录所得 token。超时 15 s。
- 成功条件 `code == 0` 且 `data.token` 非空 → 新会话（token，sid = 该店 `shopId`）。
- 失败处理：认证失败 / IP 风控 / 已取消 → 直接中止；其余失败 → 换下一家店铺继续
  （`DPGAPIClient.m:336-343`）。

#### 2.1.4 `GET /s/balance/getBalance`（DPGrabber 会话校验；独立版采用）

- 无请求体；用待验证的会话（token + sid）。超时 15 s
  （`DPGAPIClient.m:350-351`、`DPGEngineRuntime.h` 账号验证循环）。
- `code == 0` 即会话有效；否则该会话不可用。
- 用途：手动模式切换店铺后验证；自动模式（跟随 App）启动时验证观测到的会话。
- IP 风控或取消 → 中止；其余失败 → 该店铺跳过。

#### 2.1.5 `GET /s/duopei-account/get-phone-number`（DPGrabber，仅展示）

- 无请求体；用当前会话。超时 10 s（`DPGEngineRuntime.h` `learnPhoneForContext`）。
- 成功条件 `code == 0`；手机号取自 `data.phoneNumber` / `data.phone` / `data.mobile`
  （字典时取其 `mobile` 字段）；失败不影响业务，仅展示缺失。

#### 2.1.6 `GET /s/c/clerk/get`（两端都用）

- 无请求体；用当前会话。DPGrabber 超时 15 s（`DPGEngineRuntime.h` `clerkIdForContext`）。
- 响应中店员对象可能出现在 `response.clerk`、`response.data` 或响应顶层；
  DPGrabber 按此顺序取第一个含正整数 `id` 的字典（`DPGEngineRuntime.h` 同上）。
- PC 工具：解密后的 `text['clerk']`，取 `id`、`shopId`、`name`
  （`DP.py:595-600`）；`code != 0` 即登录失败。
- DPGrabber 用该接口的 `id` 作为本账号 `clerkId`，用于抢单成功后核对归属
  （见 §8.3）；取不到时 `clerkId = 0`，核对退化为仅文案分类。

### 2.2 订单列表

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 7 | GET | `/s/c/order/randomList?pageNo=1&pageSize=20` | 大厅随机订单列表（DPGrabber） |
| 7' | GET | `/s/c/order/randomList?pageNo=1&pageSize=10` | 同上（PC 工具） |
| 8 | GET | `/s/c/order/list?type=assigned&pageNo=1&pageSize=20` | 指定单列表（仅 DPGrabber） |

- DPGrabber：`pageNo=1&pageSize=20`，超时取配置 `request_timeout_sec`（默认 15 s）
  （`DPGEngineRuntime.h:602-614`）。
- PC 工具：`pageNo=1&pageSize=10`，超时 30 s（`DP.py:748`、`DP.py:754-757`）。
- 成功条件（DPGrabber）：`code == 0`；订单数组取 `response["list"]`，
  若无则取 `response["data"]["list"]`；两者皆无 → 报错
  （`DPGEngineRuntime.h:615-625`）。
- PC 工具：不校验 `code`，直接取解密后 `text['list']`（默认 `[]`）
  （`DP.py:769-770`）。响应无 `response` 字段时直接跳过本轮、无日志、无等待
  （`DP.py:763-765`）。
- 注意：DPGrabber 源码注释称 `randomList` 是“保留的脚本端点”，即该路径来自
  外部脚本而非宿主 JS 观测（`DPGEngineRuntime.h:611`）。PC 工具独立地使用了
  同一路径，可作旁证，但未做真实请求验证——见 §10“未证实”。

### 2.3 订单详情

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 9 | GET | `/s/c/order/get?id=<订单id>` | 查询订单状态（DPGrabber 核验用） |
| 9' | POST | `/s/c/order/get?id=<订单id>` | 取订单时间信息（PC 工具精确等待用） |

- DPGrabber：GET，无请求体，超时 15 s（`DPGEngine.m:1140-1142`）。
- PC 工具：POST，`id` 在 URL 查询串中，无请求体，超时 10 s
  （`DP.py:885-887`）。响应解密后要求 `code == 0`，否则返回失败
  （`DP.py:895-898`）。
- 独立版三端：只用 GET 形式，**仅作确认核验**（§8.2 的 Readback / Final
  步骤，超时 15 s）；命中订单后不再调详情接口取时间、不做精确等待。
- 详情对象取自解密后 `text['order']`（PC，`DP.py:901`）；DPGrabber 核验时按
  `response["order"]`、`data["order"]`、`data`、响应顶层的顺序取第一个含
  `status` 或 `msg` 的字典（`DPGEngine.m:1153-1157`）。

### 2.4 抢单提交

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 10 | POST | `/s/c/order/confirm?id=<订单id>` | 大厅单确认（DPGrabber 随机单；PC 工具） |
| 10' | POST | `/s/c/order/confirm`（表单 `id=<订单id>`） | 指定单确认（仅 DPGrabber） |

- DPGrabber 随机单：POST，`id` 在 URL 查询串，无请求体
  （`DPGEngine.m:1127-1129`）。
- DPGrabber 指定单：POST，表单体 `id=<订单id>`（`DPGEngine.m:1131-1133`）。
- PC 工具：POST，`params={'id': order_id}`（即查询串 `?id=`），无请求体，
  超时 10 s（`DP.py:943-946`）。
- 超时（DPGrabber）：取 `min(request_timeout_sec, 剩余窗口时间)`，下限 0.1 s
  （`DPGEngine.m:940-948`）。

### 2.5 通用请求规则（DPGrabber 封装）

- 仅允许后台线程调用；主线程调用直接报错（`DPGAPIClient.m:221-224`）。
- 请求体两种编码（`DPGAPIClient.m:250-262`）：
  - `json`：`Content-Type: application/json`，标准 JSON。
  - `form`：`Content-Type: application/x-www-form-urlencoded`，键按字典序排序，
    键与值均按 RFC 3986 非保留字符集（`A-Z a-z 0-9 -._~`）做百分号编码，
    以 `&` 连接。
- 会话超时（`DPGAPIClient.m:63`，`init` 内）：单请求 30 s、总资源 60 s、每 host 最大 8 连接、
  不读缓存；`transportRequest` 另在超时值上加 0.5 s 宽限（`DPGAPIClient.m:185-186`）。
- DPGrabber 自有请求会打标记，避免被自身的会话观测器重复捕获
  （`DPGAPIClient.m:237`；观测器见 §3.3）。

## 3. 认证

### 3.1 Token 与 sid 的含义

- `authorization-token` 请求头：登录态 token（不透明字符串）。
- `sid` 请求头：店铺标识，十进制数字字符串。DPGrabber 的 `DPGSession` 规定
  sid 为空时默认 `"92"`（`DPGModels.m` `DPGSession initWithToken:sid`）。
- 两端一致：每个请求同时带 `authorization-token` 与 `sid`。

### 3.2 会话来源

**独立版登录方式（已定）：手机号 + 密码**

独立版三端（PC / Android / iOS）统一用手机号 + 密码登录，采用 DPGrabber
手动模式的现成流程（`DPGAPIClient.m:269` `loginAccount:` 与 `:298`
`sessionForAccount:`）：

1. `POST /s/duopei-account/login`（`DPGAPIClient.m:281-283`），请求体 JSON
   `{"method": "password", "phoneNumber": {"regionCode": "CN", "mobileDial": "86",
   "mobile": "<手机号>"}, "password": "<密码>"}`；密码字段优先取 `password_hash`，
   为空时取 `password`（`:272-273`）；请求头 `sid: "-1"`、`authorization-token: ""`
   （空）；超时 15 s。`code == 0` 且 `data.token` 非空 → 建成会话
   （token，sid `"50"`）（`:285-295`）。
2. `GET /s/duopei-account/list-shop-account`（`:313-314`），请求头 `sid: 50`，
   超时 15 s；`code == 0`，`data` 为店铺数组（`shopId` / `accountId`）；
   优先尝试配置 `shop_id`（默认 `92`）（`:312`），其余店铺随后依次尝试。
3. 对每家店铺：`POST /s/duopei-account/switch-account`（`:333-335`），表单体
   `{accountId}`，请求头 `sid: 50`，超时 15 s；`code == 0` 且 `data.token`
   非空 → 新会话（token，sid = 该店 `shopId`）。认证失败 / IP 风控 / 已取消
   → 直接中止；其余失败 → 换下一家店铺继续。
4. `GET /s/balance/getBalance`（`:350-351`）验证新会话，`code == 0` 即有效。
5. 失效时（业务码 / HTTP 401/403）：用保存的账号重新走本流程
   （`DPGEngineRuntime.h` `refreshContext`）。

账号密码只存于配置文件（DPGrabber 为 0600 权限的 `config.json`），token
只存内存，不持久化（见 §3.3）。

> `app_session` 观测模式（DPGrabber 默认）依赖注入宿主 App 进程内、观测
> 宿主自身的 HTTPS 请求头，独立版没有宿主进程，**不可用**；独立版只用
> 上述手动模式。

**参考工具做法（仅参考，不采用）：贴 Token 登录**

- 用户手动输入 Token；登录校验为 `GET /s/c/clerk/get`，请求头
  `authorization-token: <Token>`、`sid: "null"`（字符串 `null`）
  （`DP.py:574-575`、`DP.py:579-582`）。
- 校验成功后取 `clerk.shopId` 作为后续监听会话的 `sid`（`DP.py:597`），
  `clerk.id` 记为 `user_id`，`clerk.name` 作展示名。
- 监听会话的请求头在 `start_listening` 时一次性写入 `requests.Session`
  （`DP.py:699-714`）：`authorization-token`、 `platform: app`、
  `Content-Type: application/x-www-form-urlencoded`、`sid: <shopId>`、
  `user-agent`（Android UA，见 §3.4）、`Host`、`Connection: Keep-Alive`、
  `Accept-Encoding: gzip`。
- Token 只存在内存与用户输入框中；配置文件 `local_config.json` 不含 Token
  （配置键见 §9.1）。
- 失效处理：**未在源码中找到**。登录只校验一次；轮询与抢单路径不校验 `code`，
  不处理 401/403，不重新登录。

**DPGrabber 自动模式（`app_session`，仅插件可用，独立版不采用）：**

- `app_session`（默认）：不保存任何凭证。观测宿主 App 自身发往
  `api.duopei.feiniaowangluo.com` 的 HTTPS 请求，读取其
  `authorization-token` 与 `sid` 请求头（`DPGAutoSession.m:362-414`）。
  细节：
  - 仅观测 HTTPS、host 完全等于 `api.duopei.feiniaowangluo.com` 的请求
    （`DPGAutoSession.m:33-38`）；跳过插件自有请求（`DPGAutoSession.m:40-44`）。
  - 路径须以 `/s/` 开头；按路径最后一段识别登录/登出/切换账号
    （`login|sign-in|signin`、 `logout|sign-out|signout`、
    `switch-account|switch_account|switchaccount`，`DPGAutoSession.m:46-67`）。
  - 只有路径以 `/s/c/` 或 `/s/balance/` 开头的请求才能确立可用会话
    （`DPGAutoSession.m:405-414`）；sid 必须全是数字且去除前导零后非空。
  - 登录请求的 JSON 体仅读取 `phoneNumber.mobile` 或 `phone` 字段作手机号提示，
    不读取任何密码字段（`DPGAutoSession.m:119-140`）。
  - 观测到的会话**不做服务端校验即视为就绪快照**，引擎启动前必须用
    `GET /s/balance/getBalance` 验证（`DPGAutoSession.m:2-4`、
    `DPGEngineRuntime.h` 账号验证循环）。
- `manual`：即独立版采用的手机号 + 密码流程，见本节开头（`DPGAPIClient.m:298-361`）。
- 失效处理（DPGrabber）：
  - 业务 `code` 401/403 或 HTTP 401/403 → 认证失效（`DPGAPIClient.m:150-153`、
    `DPGAPIClient.m:201-205`）。
  - 自动模式：使观测会话失效并**停止整轮运行**，需用户在 App 内重新登录后
    再开启（`DPGEngineRuntime.h` `refreshContext`）。
  - 手动模式：用保存的账号重新走 `sessionForAccount` 登录流程
    （`DPGEngineRuntime.h` 同上）。
  - 轮询/确认线程在会话刷新期间暂停，不发请求（`DPGEngineRuntime.h` `pollLoopKind`、
    `DPGEngine.m` `acquireSendSlotCancelled:`）。
- Token 有效期（TTL）：**两端源码均未记录**。DPGrabber 仅以“请求头里有无 token”
  判断就绪，不记录过期时间（见其既往评审结论）。新客户端应以 401/403
  作为失效信号，不得假设固定 TTL——未证实。

### 3.3 会话存放

| 客户端 | 存放位置 | 持久化 |
|---|---|---|
| PC 工具 | 内存（`self.token`、`self.session`）；Token 由用户输入，不写入配置文件 | 否 |
| DPGrabber 自动模式 | 内存（`DPGAutoSessionStore`）；观测会话永不序列化 | 否 |
| DPGrabber 手动模式 | 内存会话 + 配置文件中的手机号与密码（`config.json`，0600） | 账号持久化，token 不持久化 |

### 3.4 User-Agent（两端三套，逐字）

- DPGrabber（所有请求，`DPGAPIClient.m:9-11`）：
  `Mozilla/5.0 (iPhone; CPU iPhone OS 15_1_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Html5Plus/1.0 (Immersed/20) uni-app`
- PC 工具登录校验（`DP.py:567-568`，WeChat on Windows）：
  `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36 NetType/WIFI MicroMessenger/7.0.20.1781(0x6700143B) WindowsWechat(0x63090819) XWEB/8461 Flue`
- PC 工具监听会话（`DP.py:705-706`，Android uni-app）：
  `Mozilla/5.0 (Linux; Android 13; 22041216UC Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/104.0.5112.97 Mobile Safari/537.36 uni-app Html5Plus/1.0 (Immersed/36.363636)`

### 3.5 其他固定请求头

- DPGrabber 每请求（`DPGAPIClient.m:238-244`）：
  `api-version: 202501071`、`platform: app`、`Accept: */*`、
  `Accept-Language: zh-CN,zh-Hans;q=0.9`。另有配置键 `api_version`
  （默认 `"202501071"`，`DPGConfigStore.m` 默认配置）。
- PC 工具登录校验（`DP.py:559-575`）：
  `Accept: application/json, text/plain, */*`、`Accept-Language: zh-CN,zh;q=0.9`、
  `Connection: keep-alive`、`Origin: <official_url>`、`Referer: <official_url>/`、
  `Sec-Fetch-Dest: empty`、`Sec-Fetch-Mode: cors`、`Sec-Fetch-Site: cross-site`。
  其中 `official_url` 为 PC 工具配置默认值（见 §9.1），登录时不发
  `platform` 与 `api-version`。
- PC 工具监听会话（`DP.py:700-714`）：见 §3.2，不发 `api-version`；
  `Accept-Encoding: gzip` 由 `requests` 处理。
- DPGrabber 不显式设置 `Host`（由系统网络栈填写）；PC 监听会话显式设置
  `Host: api.duopei.feiniaowangluo.com`。

## 4. AES 加解密

### 4.1 结论先行：请求不加密，响应可能加密；两端密钥不同

- 两端都**只解密响应，不加密请求**。DPGrabber 源码中只有解密函数，没有
  请求加密函数（`DPGAPIClient.m` `decryptBase64Response`；
  `DPGContracts.h` 注释：“仅用于解密被包裹的响应体”）。
  PC 工具同样只有 `decrypt_aes_cbc`，无加密函数（`DP.py:498-504`）。
- 两端密钥与算法参数**不一致**，见下表。新客户端必须以真实接口验证
  应采用哪一套；本文档不作选择——**未证实**。

| 项目 | DPGrabber | PC 工具 |
|---|---|---|
| 算法 | AES-256-CBC | AES-128-CBC |
| 填充 | PKCS#7 | PKCS#7（`unpad`） |
| Key 长度 | 32 字节（64 位十六进制字符） | 16 字节（32 位十六进制字符） |
| IV 长度 | 16 字节（32 位十六进制字符） | 16 字节（32 位十六进制字符） |
| Key（十六进制） | `faf6bcb4eeaa8aa62154ed3b8c14cf5bce08cdf83086b9188abdca6f2c8bcbb1` | `81b120ef00216c33b266763abb02e6d1` |
| IV（十六进制） | `d45df726d59d21fac023cdad3dfcea86` | `e6a4cc0507dfe344b042289eeb945dce` |
| Key/IV 来源 | 硬编码（`DPGAPIClient.m:12-13`） | 配置文件 `local_config.json`，可改；上值为默认值（`DP.py:305-306`） |
| 密文编码 | Base64 | Base64 |
| 明文编码 | UTF-8 JSON | UTF-8 JSON |

### 4.2 DPGrabber 解密流程（`DPGAPIClient.m:90-159`）

1. 外层响应先按 JSON 解析，须为 JSON 对象，否则报错。
2. 取 `response` 字段与 `isEncrypted` 标志：
   - `isEncrypted` 为真 → `response`（Base64 字符串）经 AES 解密得内层 JSON。
   - `isEncrypted` 为假但 `response` 是非空字符串 → 按 UTF-8 直接解析为 JSON，
     不解密。
   - `response` 本身是对象 → 直接使用。
   - 其余情况 → 报错。
3. 解密细节（`decryptBase64Response`，`DPGAPIClient.m:90-116`）：
   - Base64 解码得密文（`:97`）；key/IV 由十六进制字符串解码（`:98-99`）。
   - 校验：密文非空且长度为 16 的倍数；key 恰为 32 字节（AES-256）；
     IV 恰为 16 字节；否则报错（`:100-101`）。
   - `CCCrypt(kCCDecrypt, kCCAlgorithmAES, kCCOptionPKCS7Padding, key, iv)`，
     即 AES-CBC + PKCS#7。
4. 内层须为 JSON 对象且含有效整数 `code`，否则报错。

### 4.3 PC 工具解密流程（`DP.py:498-504`）

```python
key = binascii.unhexlify(self.key)          # 配置 key，16 字节
iv  = binascii.unhexlify(self.iv)           # 配置 iv，16 字节
encrypted_bytes = binascii.a2b_base64(encrypted_text)
cipher = AES.new(key, AES.MODE_CBC, iv)     # Crypto.Cipher.AES
decrypted = unpad(cipher.decrypt(encrypted_bytes), AES.block_size)  # PKCS#7
return decrypted.decode('utf-8')
```

- 调用点：登录（`DP.py:589`）、轮询（`DP.py:767`）、订单详情（`DP.py:895`）、
  抢单提交（`DP.py:954`）——均为 `json.loads(decrypt_aes_cbc(data['response']))`。
- 注意：PC 工具解密前**不校验密文长度**；填充错误时 `unpad` 抛异常，
  由各调用点的通用异常处理接管（轮询：重试；详情/提交：返回失败）。
- 配置加载时校验 `len(iv) == 32` 且 `len(key) == 32`（十六进制字符数），
  否则配置无效（`DP.py:365-368`）。注意配置字典里键名与值的关系：
  配置键 `'iv'` 对应值 `e6a4cc05…`，配置键 `'key'` 对应值 `81b120ef…`；
  解密时 `self.key`（`81b120ef…`）作 AES 密钥，`self.iv`（`e6a4cc05…`）作 IV。

### 4.4 响应解密与否的判定差异

- DPGrabber：按 `isEncrypted` 标志或 `response` 的类型决定（§4.2）。
- PC 工具：登录时若外层 JSON 已含 `code` 则直接使用，不解密；
  否则一律解密 `data['response']`（`DP.py:586-589`）。
  轮询/详情/提交则**无条件**解密 `response` 字段（无该字段即失败，
  见 `DP.py:763-765`、`DP.py:890-893`、`DP.py:949-952`）。

### 4.5 进制转换（客户端内部自动完成）

加解密层内部自动做两处编码转换，调用方不需要自行处理进制：

- **十六进制 → 字节**：DPGrabber 的 `DPGHexDecode`（`DPGAPIClient.m:15`）把
  十六进制字符串形式的 key/IV 解码为字节后才交给 AES；PC 工具对应为
  `binascii.unhexlify(self.key)` / `unhexlify(self.iv)`（`DP.py:499-500`）。
- **Base64 → 字节**：DPGrabber 的 `decryptBase64Response` 先把 `response`
  载荷 Base64 解码为密文字节，再 AES 解密（`DPGAPIClient.m:90-101`：
  `:97` 解码，`:98-99` 取 key/IV，`:100-101` 校验长度）；
  PC 工具对应为 `binascii.a2b_base64(encrypted_text)`（`DP.py:501`）。

调用约定：调用方只传十六进制字符串形式的 key/IV 与 Base64 字符串形式的
响应载荷即可；key/IV 的字节长度校验（DPGrabber：key 32 字节、IV 16 字节，
`DPGAPIClient.m:100-101`；PC：两者均为 32 位十六进制字符，`DP.py:365-368`）
由加解密层内部完成。

## 5. 订单对象字段

### 5.1 两端共同读取的字段

| 字段 | 类型/单位 | 含义 | 谁读 |
|---|---|---|---|
| `id` | 字符串（DPGrabber 也接受数字转字符串） | 订单唯一标识，抢单与查询的主键 | 两端 |
| `no` | 字符串 | 订单号（展示/日志用） | 两端 |
| `totalAmount` | 整数，分 | 订单金额（分）；两端均 `/100` 得元 | 两端 |
| `createTime` | 整数，毫秒时间戳 | 订单创建时间 | 两端（用途不同，见 §7） |
| `code` | 整数（或数字字符串） | 业务状态码，`0` 成功 | 两端 |
| `msg` / `message` | 字符串 | 业务提示文案；DPGrabber 用其做成功/失败分类（§8.2） | 两端 |

### 5.2 仅 DPGrabber 读取的字段

| 字段 | 说明 |
|---|---|
| `servicePrice` | 整数，分；`totalAmount` 为 0/缺失时的金额后备（`DPGEngineRuntime.h` 轮询过滤；`DPGEngine.m` 通知与日志） |
| `status` | 整数；指定单列表过滤（非 0 跳过，`DPGEngineRuntime.h`）；核验分类（§8.3） |
| `clerkId` | 整数；核验时比对是否本账号接走（§8.3） |
| `list` / `data.list` | 订单数组所在位置（§2.2） |

### 5.3 仅 PC 工具读取的字段

| 字段 | 说明 |
|---|---|
| `userWechatId` | 字符串；客户微信号，用于黑名单匹配（§6） |
| `userMemo` | 字符串；订单备注，用于备注模式与关键词过滤（§6） |
| `reTime` | 整数，毫秒时间戳；PC 工具解释为订单开放时间（§7.1） |
| `confirmCountDown` | 数值，秒；PC 工具解释为平台倒计时（§7.1） |

### 5.4 时间戳与金额的归一化

- DPGrabber：`DPGNormalizeMilliseconds`（`DPGCompat.m`）——数值 ≤ 0 返回 0；
  数值 `< 1e12` 视为秒，×1000 转毫秒；否则视为毫秒。
  `DPGAmountFromCents`——分 `/100` 得元。
- PC 工具：`totalAmount / 100` 得元（`DP.py:810`）；`createTime / 1000` 与
  `reTime / 1000` 直接当毫秒转秒（`DP.py:903-904`），不做秒/毫秒自适应。
- DPGrabber 会在轮询得到的订单上附加内部字段 `_observed_mono`（单调时钟）、
  `_observed_wall`（墙钟秒）、`_observed_sid`（观测时 sid），仅供本端去重与
  会话范围校验使用，不属于服务端字段（`DPGEngineRuntime.h:648-652`）。

## 6. 轮询循环

### 6.1 PC 工具（`DP.py:742-795`，`listening_order_list`）

- 单线程循环；停止条件为 `stop_event` 置位。
- 每轮：`GET /s/c/order/randomList`，`params={'pageNo':'1','pageSize':'10'}`，
  超时 30 s；`raise_for_status()`（HTTP 错误即抛异常）。
- 计时：记录本轮请求耗时 `network_delay`（秒），分档记录网络状态：
  `< 0.6` / `< 1` / `<= 3` / 否则（`DP.py:776-779`）。
- 响应处理：取 `response_json['response']` 解密 → `text['list']`（默认 `[]`）。
  有订单时**只处理 `order_list[0]`**（第一条），处理完立即重 poll，
  **不等待**（`DP.py:772-773`）。
- 无订单时：记录网络状态与耗时，`sleep_with_stop(listen_delay)` 后继续
  （`DP.py:781-782`）。`listen_delay` 默认 0.5 s（配置 `ListenDelay`）。
- 异常处理（均记日志后等待 `listen_delay` 再继续，无退避放大）：
  `JSONDecodeError` → 解密后字符串无法解析；`KeyError` → 缺少必要参数；
  `requests.RequestException` → 请求失败；其他 `Exception` → 监听异常
  （`DP.py:784-795`）。
- 无去重：同一订单可重复进入 `handle_order`；无 `code` 校验。

### 6.2 DPGrabber（`DPGEngineRuntime.h:562-664`，`pollLoopKind` / `pollOrdersKind`）

- 每账号一个轮询线程；另有指定单线程（配置 `auto_assigned` 为真时）。
- 大厅：`GET /s/c/order/randomList?pageNo=1&pageSize=20`；
  指定单：`GET /s/c/order/list?type=assigned&pageNo=1&pageSize=20`。
- 成功条件：`code == 0` 且含订单数组；否则按 §6.3 处理。
- 去重：按 `kind:id` 的 seen 集合，TTL 内重复出现跳过；
  大厅默认 60 s、指定单默认 660 s（配置 `random_seen_ttl` /
  `assigned_seen_ttl`）；终态（完成/未知）单独记忆（`DPGModels.m`）。
- 大厅金额过滤在轮询层：`totalAmount`（后备 `servicePrice`）换算后
  小于 `min_amount`（默认 10 元）直接跳过（`DPGEngineRuntime.h:640-644`）。
- 指定单过滤在轮询层：`status` 非 0 跳过；`createTime` 距今超过
  `assigned_expire_ms`（默认 660000 ms）跳过（`DPGEngineRuntime.h:629-638`）。
- 轮询间隔：每轮成功后均匀随机取 `[interval_min, interval_max]` 秒
  （默认 2–3 s）（`DPGEngineRuntime.h:594-597`）。
- 每次轮询前检查：IP 冷却剩余（有则等待）、会话是否待刷新（有则刷新）、
  观测会话是否仍为当前会话（变化则停止整轮）。

### 6.3 DPGrabber 轮询失败处理（`DPGEngineRuntime.h:562-600`）

| 失败类型 | 处理 |
|---|---|
| 认证失败（401/403） | 标记会话待刷新，唤醒调度器，下一轮刷新会话 |
| IP 风控 | 全账号暂停 60 s（`pauseIP:60`），等待 60 s |
| 其他网络/API 错误 | 指数退避：`min(10 × 2^min(5, 失败次数-1), 300)` 秒；失败计数成功后清零 |
| 会话验证失败（启动时） | IP 风控或累计 ≥10 次 → 60 s 后重试，否则 30 s 后重试（`DPGEngineRuntime.h` `accountWorker`） |

## 7. 过滤规则

### 7.1 PC 工具（`DP.py:799-853`，`handle_order`；`DP.py:870-877`，`match_keyword`）

按以下顺序检查，任一不通过即丢弃本单（只处理 `order_list[0]`）：

1. `totalAmount` 缺失 → 丢弃。
2. `id` 缺失 → 丢弃。
3. 黑名单：`userWechatId` 非空且在 `blacklist` 列表中 → 丢弃。
   `blacklist` 为配置文件中的微信号列表（一行一个，见 §9.1）。
4. 最低金额：`totalAmount/100 < min_price` → 丢弃。
5. 最高金额：`max_price > 0` 且 `totalAmount/100 > max_price` → 丢弃；
   `max_price == 0` 表示不限制。
6. 备注模式（`desc_state`，配置项，见 §9.1）：
   - `0`：不检测备注。
   - `1`：订单有任何备注（`userMemo` 非空）即丢弃。
7. 备注关键词：`match_keyword(userMemo)`——备注小写化后，
   关键词列表中任一非空关键词小写化后为其子串即命中 → 丢弃。
   （大小写不敏感的子串匹配；返回命中的关键词原文。）
- 金额与延时校验（保存设置时，`DP.py:637-661`）：
  `min_price`、`max_price` 须为数字，`user_delay` 须为整数；
  金额不得小于 0；`max_price > 0` 时须 `max_price >= min_price`；
  备注模式取值须在 `DESC_OPTIONS` 的键集合内（`0` / `1`）。

### 7.2 DPGrabber

- 轮询层仅有最低金额过滤（大厅单，见 §6.2）。
- **备注、关键词、黑名单过滤在 DPGrabber 源码中不存在**：
  全源码检索 `userWechatId`、`userMemo`、`keyword`、`blacklist` 均无命中
  （设置页 UI 除外）。新客户端如需这些规则，应按 §7.1 的语义实现。
- 其余“过滤”由确认调度器承担：容量上限、每类并发上限、去重、终态记忆、
  会话范围校验（订单须属于当前 sid，见 §8.2）。

## 8. 抢单时机与抢单提交

### 8.1 PC 工具：精确时刻等待（`DP.py:879-938`，`get_new_order_title`）

> 本节只记录 PC 参考工具的做法。**独立版三端不采用本节逻辑**：不读
> `reTime` / `confirmCountDown`，不算精确时刻，不做用户延时（`user_delay`
> 已删除），命中后立即开始 §8.2 的确认任务。

流程（在 `handle_order` 通过全部过滤后执行）：

1. `POST /s/c/order/get?id=<订单id>`，超时 10 s；无 `response` 字段 → 返回 -1（中止）。
2. 解密，要求 `code == 0`，否则返回 -1。
3. 取 `order` 对象，读三个字段：
   - `system_delay = order['confirmCountDown']`（秒）
   - `create_dt = datetime.fromtimestamp(order['createTime'] / 1000)`
   - `open_dt = datetime.fromtimestamp(order['reTime'] / 1000)`
4. 计算：
   - `grab_time = open_dt + timedelta(seconds=system_delay)`
   - `time_to_wait = (grab_time - datetime.now()).total_seconds()`
   - 即：**准确抢单时间 = reTime + confirmCountDown；等待时长 = 该时刻 − 本地当前时间**。
   - `createTime` 仅用于记录，不参与计算。
5. 若 `time_to_wait > 0`：0.1 s 步进忙等（每 0.1 s 检查一次 `stop_event`），
   剩余 > 1 s 时每秒记录一次剩余秒数，剩余 ≤ 1 s 时按 0.1 s 精度记录；
   等完返回 0（继续抢单）。等待中 `stop_event` 置位 → 返回 -1（中止）。
6. 若 `time_to_wait <= 0`：不等待，立即抢单，返回 0。
7. 缺少字段（`KeyError` / `TypeError`）：不等待，直接抢单，返回 0。
8. 其他异常：返回 -1（中止）。
9. 随后还有一层用户延时（`DP.py:859-865`）：`user_delay` 为正整数时，
   按 1 s 步进等待 `user_delay` 秒（期间可随时停止），再提交。

### 8.2 DPGrabber：窗口内重试（`DPGEngine.m` `DPGConfirmationTask`）

DPGrabber **不读取 `reTime` 与 `confirmCountDown`**（全源码无命中），
也不做“等到某一时刻再发”的逻辑。其模型是“窗口内尽早发、失败重试”：

- 任务阶段：`Init → Loop → Final → Readback → Done`（`DPGEngine.m` `step`）。
- 截止时间（`deadlineForOrderAtWall`，`DPGEngine.m`）：
  - 大厅单：`wallStart + random_confirm_window_sec`（默认 20 s）；
    无 `createTime` 时用 `random_confirm_fallback_sec`（默认 20 s）。
  - 指定单：`createTime/1000 + assigned_confirm_window_sec`（默认 660 s）；
    无 `createTime` 时用 `wallStart + 660`。
- 开始时间（仅指定单）：`createTime/1000 + 2` 秒，未到则等待
  （`DPGEngine.m` `stepInit`）；大厅单无此等待，立即进入循环。
- 循环内（`stepLoop`）：每次发送前取全局发送位（两次发送间隔至少
  `confirm_retry_delay_sec`，默认 0.5 s，可配 0–60 s）；发送后按 §8.3 分类；
  非终态则 `readyAt = min(截止, 当前 + confirm_retry_delay_sec)` 后重试，
  直至截止。
- 截止后（`stepFinal`）：用 `order/get` 核验一次（§8.3）；仍待确认时，
  大厅单释放回轮询（`Pending`，可由后续轮询再次入队），指定单记终态失败。
- 会话范围校验：订单须属于创建时观测的 sid；中途 sid 变化时，
  未发送过的任务释放重试，已发送过的记未知（`DPGEngine.m` `scopeChanged`）。
- 并发：每类订单并发上限默认大厅 2、指定 1（配置 `limits`，每类 1–4，
  总线程 ≤ 8）；待确认任务容量默认 8（配置 `confirm_capacity`，1–128）
  （`DPGEngine.m` 调度器初始化）。

> **独立版实现（与 DPGrabber 原版的出入）**：只做大厅单（random），不做
> 指定单；**单任务串行**（一次只处理一个订单，不移植 8 并发容量与每类
> 并发上限）；无调度器发送位 pacing（串行 + 0.5 s 重试间隔已覆盖）；
> 无店铺会话范围校验（单账号引擎；会话刷新只在任务之间或认证失败时
> 发生，认证失败即结束任务）；无共享 IP 冷却/会话刷新状态机
> （认证/IP 在调用点处理：401/403 → 释放订单 + 重登，任务结束；
> IP → 暂停 60 s、不重登，任务结束）；`order/get` 用 GET 形式（与
> DPGrabber 核验一致，PC 工具的 POST 形式不用）。

### 8.3 抢单提交与返回码

**提交请求**（见 §2.4）：

- PC：`POST /s/c/order/confirm`，`params={'id': order_id}`，超时 10 s；
  响应须含 `response` 字段，解密后 `code == 0` 即成功，返回 0；
  否则记录 `msg` 并返回 -1；异常返回 -1（`DP.py:940-969`）。**无重试**。
  成功后调用方停止整轮监听（`DP.py:867-868`）。
- DPGrabber：见 §8.2 的循环与核验。

**DPGrabber 返回分类**（`DPGEngine.m` `stepLoop` / `reconcileWithSession`）：

- 提交响应：`code == 0` → 成功；`code` 401/403 → 认证失败；
  `msg` 含成功词（`已接单`、`已确认`、`接单成功`、`确认成功`）→ 成功；
  `msg` 含终态词（`取消`、`已被`、`不存在`、`无权`、`权限`、`失效`、`超时`）
  → 失败；其余 → 重试。
- 提交无响应（网络错误/超时）→ 进入 Readback 核验，不盲重试。
- 核验（`GET /s/c/order/get?id=`）：取含 `status` 或 `msg` 的对象；
  `status` 为 2 或 3、或 `msg` 含成功词 → 成功（但若 `clerkId` 已知且与
  本账号 `clerkId` 不符 → 失败，即被他人接走）；`status` 为 -1 或 `msg`
  含终态词 → 失败；`status` 为 0 → 待确认；其余 → 未知。
- 认证失败 → 刷新会话（手动重登 / 自动停轮）；IP 风控 → 全账号暂停 60 s；
  已取消 → 按需记未知或取消。

**返回码含义总表（两端源码可验证的部分）：**

| code | 含义 |
|---|---|
| 0 | 成功 |
| 401 / 403 | 登录态失效（业务码与 HTTP 状态码均如此处理） |
| 1 且 `msg` 含 `IP` | IP 风控（仅 DPGrabber 的判定，见 §9.3） |
| 其他非 0 | 失败；具体原因以 `msg` 文案分类（成功词/终态词，见上） |

## 9. 配置与默认值

### 9.1 PC 工具配置（`local_config.json`，`DP.py:22`、`DP.py:303-312`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `iv` | `e6a4cc0507dfe344b042289eeb945dce` | AES IV（32 位十六进制字符） |
| `key` | `81b120ef00216c33b266763abb02e6d1` | AES 密钥（32 位十六进制字符） |
| `official_url` | （PC 工具配置默认值，用作登录 Origin/Referer） | 店铺网址 |
| `official_title` | （PC 工具配置默认值） | 店铺名称（登录成功展示用） |
| `ListenDelay` | `0.5` | 轮询间隔（秒） |
| `voice_enabled` | `true` | 语音播报开关 |
| `blacklist` | `[]` | 黑名单微信号列表 |
| `keyword_filter` | `[]` | 备注关键词列表 |

- 缺失文件时自动创建模板；缺失的键用默认值补齐并回写（`DP.py:319-344`）。
- 加载校验：`iv`、`key`、`official_url` 均非空；`iv` 与 `key` 长度均为 32，
  否则配置无效（`DP.py:360-368`）。
- `ListenDelay` 转 `float` 失败时回退 0.5（`DP.py:352-354`）。
- 另有界面输入（不存配置文件）：Token、最小金额、最大金额、延时（整数秒）、
  备注模式（`0` 不检测 / `1` 不抢有备注订单）。

### 9.2 DPGrabber 配置（`config.json`，默认见 `DPGConfigStore.m`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `auth_mode` | `"app_session"` | `app_session` 跟随 App / `manual` 手动账号 |
| `accounts` | `[]` | 手动账号数组（`phone` + `password`/`password_hash`） |
| `min_amount` | `10` | 最低金额（元），0–1000000 |
| `interval_min` / `interval_max` | `2` / `3` | 轮询间隔上下限（秒），各 0.05–3600 |
| `notification_enabled` | `true` | 本地通知开关 |
| `background_mode` | `"off"` | `off` / `audio` / `jailed` / `external`（与保活相关，非协议） |
| `auto_assigned` | `true` | 是否另起线程轮询指定单 |
| `shop_id` | `92` | 手动模式优先切换的店铺 |
| `random_seen_ttl` / `assigned_seen_ttl` | `60` / `660` | 去重记忆（秒），各 0.1–86400 |
| `assigned_expire_ms` | `660000` | 指定单过期阈值（毫秒） |
| `random_confirm_window_sec` | `20` | 大厅单确认窗口（秒） |
| `random_confirm_fallback_sec` | `20` | 无 createTime 时的大厅单确认窗口（秒） |
| `assigned_confirm_window_sec` | `660` | 指定单确认窗口（秒） |
| `confirm_retry_delay_sec` | `0.5` | 确认重试间隔（秒），0–60 |
| `request_timeout_sec` | `15` | 轮询请求超时（秒），0.1–60 |
| `api_version` | `"202501071"` | 随请求发送的 `api-version` 头 |
| `confirm_capacity` | `8` | 待确认任务容量，1–128 |
| `limits` | （未配置时默认） | 每类确认并发：大厅 2、指定 1（各 1–4） |

- 配置文件位置：App 容器 `Application Support/DPGrabberNative/config.json`，
  目录 0700、文件 0600（`DPGConfigStore.m`）。
- 独立版三端只用上表的大厅单三键（`random_confirm_window_sec` /
  `random_confirm_fallback_sec` / `confirm_retry_delay_sec`）与通用键
  （轮询间隔、超时等）；`assigned_*`、`confirm_capacity`、`limits` 不实现，
  单任务串行；`user_delay` 已删除（旧配置文件里的该键会被忽略）。
- 校验规则见 `DPGConfigStore.m` `validatedConfiguration`（含各键取值范围、
  `interval_max >= interval_min`、`limits` 总和 ≤ 8 且须含 `random` 等）。
- 另可导入旧版配置（`importLegacyConfigurationAtPath`），含单账号的
  `phone` / `password_hash` / `password` 字段。

## 10. 其他注意点

### 10.1 限流与 429

- **两端源码均无对 HTTP 429 的专门处理**：DPGrabber 源码全文无 `429` 字样；
  其传输层把 429 归为通用 API 错误（非 401/403、非 5xx），轮询按通用错误
  指数退避（§6.3）；PC 工具的 `raise_for_status()` 会把 429 抛为
  `RequestException`，按固定 `ListenDelay` 重试（`DP.py:759`、`DP.py:790-792`）。
- 服务端真实的限流阈值与 429 语义：**未在源码中找到，未证实**。

### 10.2 IP 限制判断

- DPGrabber：仅当业务 `code == 1` **且** `msg` 含子串 `IP` 时判定为 IP 风控
  （`DPGAPIClient.m:154-157`）。该判定为子串匹配，可靠性存疑（既往评审已指出）。
  触发后全账号暂停 60 s，期间不发请求；手动模式下不重新登录
  （`DPGEngine.m` `stepLoop`、`DPGEngineRuntime.h` `pollLoopKind`）。
- PC 工具：**无任何 IP 相关判定**（不检查 `code`，不读 `msg` 作分类）。
- 服务端真实的 IP 限制规则：**未在源码中找到，未证实**。

### 10.3 TLS 与证书

- PC 工具监听会话：`session.verify = False`，即**不验证 TLS 证书**
  （`DP.py:509`）。登录校验用模块级 `requests.get`，未改 `verify`，
  即默认验证。
- DPGrabber：`NSURLSession` 默认配置（`ephemeralSessionConfiguration`），
  证书默认验证（`DPGAPIClient.m:63`，`init` 内）。
- 新客户端建议默认验证证书；如需复现 PC 工具行为再显式关闭——但关闭
  意味着中间人可读 Token，应明确告知用户。

### 10.4 时钟

- PC 工具的精确等待用**本地墙钟**（`datetime.now()`）与服务端给出的
  `reTime` / `confirmCountDown` 相减（`DP.py:905-906`）。本地时钟偏差
  会直接转化为抢单时刻偏差；源码中无任何时钟同步或偏差补偿。
- DPGrabber 的窗口计算混用墙钟（截止时间）与单调时钟（内部调度），
  但不依赖服务端倒计时，故不受该偏差影响。
- 独立版三端同样不依赖服务端倒计时：确认截止用单调时钟
  （任务开始 + 预算），只用 `createTime` 是否存在选择窗口还是兜底窗口，
  不读 `reTime` / `confirmCountDown`。

### 10.5 订单 `status` 语义

- DPGrabber 核验逻辑中：`status == 0` 待确认；`2` 或 `3` 已确认；
  `-1` 不可确认（`DPGEngine.m` `reconcileWithSession`）。
  该映射仅由客户端代码推断，**服务端字段语义未证实**。
- 指定单列表中 `status` 非 0 的订单直接跳过（`DPGEngineRuntime.h`）。

### 10.6 未在源码中找到 / 未证实清单

- Token 与 sid 的服务端有效期（TTL）、刷新接口：未在源码中找到。
- `randomList` 是否为服务端正式接口：两端独立使用同一路径，但均无服务端
  文档佐证；DPGrabber 注释称其来自脚本——未证实。
- `reTime` / `confirmCountDown` 的服务端语义：仅知 PC 工具将其解释为
  “开放时间 + 平台倒计时”——未证实。
- 两套 AES key/IV 哪一套是服务端当前使用的：两端不一致——未证实，
  新客户端必须以真实接口验证（建议先以 DPGrabber 的 AES-256 套验证，
  因其注释称与原版一致；再以 PC 的 AES-128 套验证；以能解密为准）。
- 服务端限流阈值、429 语义、IP 限制规则：未在源码中找到。
- `clerk/get` 返回的 `shopId` 与 DPGrabber 手动模式 `shop_id` 是否同一
  命名空间：未在源码中找到。
- PC 工具 `submit_order` 成功后是否还需额外确认步骤：源码中无后续步骤，
  返回 0 即停止监听——未证实服务端是否要求其他调用。
- DPGrabber `confirm` 的指定单表单体除 `id` 外是否还需其他字段：
  源码中只有 `id`——未证实服务端是否接受。

## 11. 资料出处索引

| 内容 | 出处 |
|---|---|
| Base URL、UA、AES key/IV、请求封装、响应解密、登录/切店 | `DPGAPIClient.m`（全文 365 行） |
| 错误码枚举、会话/状态机/配置接口声明 | `DPGContracts.h` |
| 引擎启动、会话验证、轮询循环、退避、clerk 查询 | `DPGEngineRuntime.h` |
| 确认调度器、确认任务状态机、核验、提交 | `DPGEngine.m` |
| 会话观测（请求头捕获、过渡识别） | `DPGAutoSession.m` |
| 配置默认值与校验范围 | `DPGConfigStore.m` |
| 会话模型、去重/终态/IP 冷却状态机 | `DPGModels.m` |
| 金额/时间戳归一化 | `DPGCompat.m` |
| PC 登录、监听、过滤、精确等待、提交、AES 解密、配置 | `DP.py`（反汇编：`login_worker`、`listening_order_list`、`handle_order`、`match_keyword`、`get_new_order_title`、`submit_order`、`decrypt_aes_cbc`、`create_robust_session`、`default_config`、`load_local_config`、`save_settings`、`start_listening`、`sleep_with_stop`） |
