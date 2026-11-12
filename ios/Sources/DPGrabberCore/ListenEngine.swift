// DPGrabber iOS - Listening engine.
//
// One worker task: poll -> filter -> precise grab time -> submit
// (PROTOCOL.md §6-§8, following the PC reference tool's flow, with
// the DPGrabber additions noted below).
//
// Flow per round:
// 1. GET /s/c/order/randomList (page 1, 20 orders).
// 2. No orders -> wait `pollIntervalSec` and poll again.
// 3. Take only the first order (reference tool behaviour), de-dup by id
//    (seen set, TTL from config; DPGrabber addition, the reference tool
//    has no de-dup).
// 4. Filters, in order (§7.1): missing amount/id, blacklist WeChat id,
//    min amount, max amount (0 = unlimited), memo mode, memo keywords
//    (case-insensitive substring).
// 5. Passed -> precise grab time (§8.1): POST /s/c/order/get,
//    `grab_time = reTime + confirmCountDown`; wait in 0.1 s steps
//    (stoppable); then the user delay in 1 s steps (stoppable); then
//    POST /s/c/order/confirm once. Success stops the whole listen loop.
// 6. On 401/403 the session is re-established with the saved account
//    (manual-mode refresh, §3.2 step 5); on IP control the loop pauses
//    60 s; other errors wait `pollIntervalSec` and continue.
//
// All progress is reported through a `log(level, message)` callback and an
// optional `speak(text)` callback; the engine never touches the UI
// directly. `now` and `sleeper` are injected so the precise-wait math is
// unit-testable.

import Foundation

public final class ListenEngine: @unchecked Sendable {
    public typealias LogCallback = (LogLevel, String) -> Void
    public typealias SpeakCallback = (String) -> Void

    private let client: ApiClient
    private let config: DPGConfig
    private let log: LogCallback
    private let speak: SpeakCallback
    private let now: () -> TimeInterval
    /// Injected interruptible sleep (tests). Returns false when asked to stop.
    private let customSleeper: ((TimeInterval) async -> Bool)?

    private let stateLock = NSLock()
    private var _running = false
    private var _task: Task<Void, Never>?
    private var _seen: [String: TimeInterval] = [:]
    private var _consecutiveFailures = 0
    public private(set) var clerk: DPGClerk?

    /// Called when an order is grabbed successfully (the app posts a
    /// local notification from this callback).
    public var onGrabSuccess: (([String: Any]) -> Void)?

    public var isRunning: Bool {
        stateLock.withLock { _running }
    }

    public init(
        client: ApiClient,
        config: DPGConfig,
        log: @escaping LogCallback,
        speak: SpeakCallback? = nil,
        now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 },
        sleeper: ((TimeInterval) async -> Bool)? = nil
    ) {
        self.client = client
        self.config = config
        self.log = log
        self.speak = speak ?? { _ in }
        self.now = now
        self.customSleeper = sleeper
    }

    // MARK: - Lifecycle

    @discardableResult
    public func start(phone: String, password: String) -> Bool {
        let started = stateLock.withLock { () -> Bool in
            guard !_running else { return false }
            _running = true
            return true
        }
        guard started else { return false }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.run(phone: phone, password: password)
        }
        stateLock.withLock { _task = task }
        return true
    }

    public func stop() {
        stateLock.withLock {
            _running = false
            _task?.cancel()
            _task = nil
        }
    }

    private var stopped: Bool {
        stateLock.withLock { !_running }
    }

    /// Interruptible sleep. Returns false when asked to stop.
    private func sleep(_ seconds: TimeInterval) async -> Bool {
        if let customSleeper { return await customSleeper(seconds) }
        return await defaultSleep(seconds)
    }

    private func defaultSleep(_ seconds: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(max(0, seconds))
        while !stopped {
            if Date() >= deadline { return true }
            let step = min(0.1, deadline.timeIntervalSinceNow)
            do {
                try await Task.sleep(nanoseconds: UInt64(max(0, step) * 1_000_000_000))
            } catch {
                return false
            }
        }
        return false
    }

    // MARK: - De-dup

    private func markSeen(_ orderID: String) {
        let nowValue = now()
        stateLock.withLock {
            _seen = _seen.filter { nowValue - $0.value < config.seenTTLSec }
            if !orderID.isEmpty { _seen[orderID] = nowValue }
        }
    }

    private func isSeen(_ orderID: String) -> Bool {
        guard !orderID.isEmpty else { return false }
        return stateLock.withLock {
            guard let seenAt = _seen[orderID] else { return false }
            return now() - seenAt < config.seenTTLSec
        }
    }

    // MARK: - Main loop

    private func run(phone: String, password: String) async {
        defer {
            stateLock.withLock { _running = false }
            log(.info, "监听已停止")
        }
        do {
            log(.info, "正在登录…")
            let session = try await client.sessionForAccount(
                phone: phone, password: password, preferredShopID: config.shopID
            )
            log(.success, "登录成功，店铺 sid=\(session.sid)")
            do {
                let decoded = try await client.clerkGet(session: session)
                if let found = extractClerk(decoded) {
                    clerk = found
                    log(.info, "当前账号：\(found.name ?? "")（ID：\(found.id)）")
                }
            } catch let error as ApiError {
                log(.warning, "店员信息读取失败，不影响监听：\(error.message)")
            } catch {
                log(.warning, "店员信息读取失败，不影响监听：\(error.localizedDescription)")
            }
            await pollLoop(phone: phone, password: password)
        } catch let error as ApiError {
            log(.error, "启动失败：\(error.message)")
            speak("启动失败")
        } catch {
            log(.error, "启动失败：\(error.localizedDescription)")
            speak("启动失败")
        }
    }

    private func refreshSession(phone: String, password: String) async -> Bool {
        do {
            let session = try await client.sessionForAccount(
                phone: phone, password: password, preferredShopID: config.shopID
            )
            log(.success, "会话已重新建立，店铺 sid=\(session.sid)")
            return true
        } catch let error as ApiError {
            log(.error, "重新登录失败：\(error.message)")
            return false
        } catch {
            log(.error, "重新登录失败：\(error.localizedDescription)")
            return false
        }
    }

    private func pollLoop(phone: String, password: String) async {
        let interval = config.pollIntervalSec
        let filter = OrderFilter(
            minAmount: config.minAmount,
            maxAmount: config.maxAmount,
            memoMode: config.memoMode,
            memoKeywords: config.memoKeywords,
            blacklist: config.blacklist
        )
        log(.info, "开始监听新订单")
        while !stopped {
            let orders: [[String: Any]]
            do {
                orders = try await client.randomList(pageSize: 20, timeout: config.requestTimeoutSec)
            } catch let error as ApiError {
                switch error.kind {
                case .auth:
                    log(.warning, "登录态失效，尝试重新登录…")
                    guard await refreshSession(phone: phone, password: password) else { return }
                    continue
                case .ip:
                    log(.warning, "IP 风控，暂停 60 秒")
                    guard await sleep(60) else { return }
                    continue
                default:
                    stateLock.withLock { _consecutiveFailures += 1 }
                    log(.warning, "监听请求失败：\(error.message)")
                    guard await sleep(interval) else { return }
                    continue
                }
            } catch {
                log(.warning, "监听请求失败：\(error.localizedDescription)")
                guard await sleep(interval) else { return }
                continue
            }
            stateLock.withLock { _consecutiveFailures = 0 }
            guard let order = orders.first else {
                guard await sleep(interval) else { return }
                continue
            }
            let orderID = orderID(of: order)
            if isSeen(orderID) {
                guard await sleep(interval) else { return }
                continue
            }
            markSeen(orderID)
            let amount = amountYuan(fromCents: order["totalAmount"])
            log(.info, "检测到新订单：金额 \(String(format: "%.2f", amount)) 元，客户微信 \(order["userWechatId"] as? String ?? "未知")")
            let (passed, reason) = filter.check(order: order)
            guard passed else {
                log(.info, "订单不符合要求：\(reason)")
                speak(reason)
                continue // re-poll immediately, as the reference tool does
            }
            await handleOrder(order, orderID: orderID)
        }
    }

    private func handleOrder(_ order: [String: Any], orderID: String) async {
        // 1. Precise grab time (§8.1).
        var wait: TimeInterval = 0
        var grabMs: Int?
        do {
            let detail = try await client.orderGet(orderID: orderID, timeout: 10)
            guard parseCode(detail) == 0 else {
                log(.warning, "订单详情读取失败：\((detail["msg"] as? String) ?? "")")
                return
            }
            let inner = (detail["order"] as? [String: Any]) ?? detail
            let computed = computeGrabWait(order: inner, nowEpochSec: now())
            wait = computed.waitSeconds
            grabMs = computed.grabEpochMs
            let createMs = normalizeMs(inner["createTime"])
            if createMs > 0 {
                let date = Date(timeIntervalSince1970: TimeInterval(createMs) / 1000.0)
                log(.debug, "订单创建时间：\(Self.dateString(date))")
            }
            if computed.reason == .ok, let grabMs {
                let date = Date(timeIntervalSince1970: TimeInterval(grabMs) / 1000.0)
                log(.info, "准确抢单时间：\(Self.dateString(date))，等待 \(String(format: "%.1f", wait)) 秒")
            }
        } catch let error as ApiError {
            if error.kind == .auth {
                log(.warning, "登录态失效，订单详情未读取")
                return
            }
            log(.warning, "订单详情读取失败：\(error.message)")
            return
        } catch {
            log(.warning, "订单详情读取失败：\(error.localizedDescription)")
            return
        }
        if wait > 0 {
            guard await sleep(wait) else { return }
        }
        // 2. User delay (reference tool: 1 s steps, stoppable).
        if config.userDelay > 0 {
            log(.info, "等待用户延时 \(config.userDelay) 秒")
            guard await sleep(TimeInterval(config.userDelay)) else { return }
        }
        // 3. Submit once (no retry in the reference flow).
        do {
            let result = try await client.orderConfirm(orderID: orderID, timeout: 10)
            if parseCode(result) == 0 {
                log(.success, "抢单成功，请去查看订单，金额 \(String(format: "%.2f", amountYuan(fromCents: order["totalAmount"]))) 元")
                speak("抢单成功")
                onGrabSuccess?(order)
                stop()
            } else {
                let msg = (result["msg"] as? String) ?? (result["message"] as? String) ?? ""
                log(.warning, "抢单未成功：\(msg)")
                speak("抢单未成功")
            }
        } catch let error as ApiError {
            log(.error, "抢单请求失败：\(error.message)")
        } catch {
            log(.error, "抢单请求失败：\(error.localizedDescription)")
        }
    }

    private static func dateString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.locale = Locale(identifier: "zh_CN")
        return formatter.string(from: date)
    }
}
