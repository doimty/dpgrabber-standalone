// DPGrabber iOS - Listening engine.
//
// One worker task: poll -> filter -> confirm task (PROTOCOL.md §6-§8,
// DPGrabber's own DPGConfirmationTask state machine, DPGEngine.m).
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
// 5. Passed -> confirm task (§8.2): POST /s/c/order/confirm immediately
//    (no order/get detail fetch, no reTime / confirmCountDown, no
//    precise wait), retried every `confirmRetryDelaySec` until the
//    window deadline (budget = `randomConfirmWindowSec` when the order
//    has a createTime, else `randomConfirmFallbackSec`); a network
//    failure is verified with one GET /s/c/order/get (reconcile); at
//    the deadline one final verification runs. Success stops the whole
//    listen loop.
// 6. On 401/403 the session is re-established with the saved account
//    (manual-mode refresh, §3.2 step 5); on IP control the loop pauses
//    60 s; other errors wait `pollIntervalSec` and continue.
//
// All progress is reported through a `log(level, message)` callback and an
// optional `speak(text)` callback; the engine never touches the UI
// directly. `now` (wall clock), `monotonic` (deadline clock) and
// `sleeper` are injected so the confirm math is unit-testable.

import Foundation

public final class ListenEngine: @unchecked Sendable {
    public typealias LogCallback = (LogLevel, String) -> Void
    public typealias SpeakCallback = (String) -> Void

    private let client: ApiClient
    private let config: DPGConfig
    private let log: LogCallback
    private let speak: SpeakCallback
    private let now: () -> TimeInterval
    /// Monotonic clock for the confirm deadline (systemUptime by default).
    private let monotonic: () -> TimeInterval
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
        monotonic: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
        sleeper: ((TimeInterval) async -> Bool)? = nil
    ) {
        self.client = client
        self.config = config
        self.log = log
        self.speak = speak ?? { _ in }
        self.now = now
        self.monotonic = monotonic
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
            await handleOrder(order, orderID: orderID, phone: phone, password: password)
        }
    }

    // MARK: - Confirm task

    /// The order number for logs: `no` when present, else the order id.
    private func orderNumber(_ order: [String: Any], orderID: String) -> String {
        if let s = order["no"] as? String, !s.isEmpty { return s }
        if let n = order["no"] as? NSNumber { return n.stringValue }
        return orderID
    }

    /// Drop an order from the seen set so a later poll round can pick it
    /// up again (DPGConfirmationTask releaseForRetry).
    private func releaseSeen(_ orderID: String) {
        guard !orderID.isEmpty else { return }
        stateLock.withLock { _seen.removeValue(forKey: orderID) }
    }

    private func ownClerkID() -> Int { clerk?.id ?? 0 }

    /// One order/get verification (reconcileWithSession). Any API failure
    /// means the result is unknown.
    private func readback(orderID: String) async -> ReconcileResult {
        do {
            let response = try await client.orderGet(orderID: orderID, timeout: 15)
            return reconcileOrder(response, ownClerkID: ownClerkID())
        } catch let error as ApiError {
            log(.warning, "订单状态查询失败 id=\(orderID)：\(error.message)")
            return ReconcileResult(verdict: .unknown, reason: "")
        } catch {
            log(.warning, "订单状态查询失败 id=\(orderID)：\(error.localizedDescription)")
            return ReconcileResult(verdict: .unknown, reason: "")
        }
    }

    /// Success end of a confirm task: log, announce, auto-stop.
    private func grabSuccess(order: [String: Any], orderID: String, confirmCalls: Int) {
        let number = orderNumber(order, orderID: orderID)
        log(.success, "✓ 确认成功: \(number) (id=\(orderID)) confirm_calls=\(confirmCalls)")
        log(.success, "抢单成功，请去查看订单，金额 \(String(format: "%.2f", amountYuan(fromCents: order["totalAmount"]))) 元")
        speak("抢单成功")
        onGrabSuccess?(order)
        stop()
    }

    /// Wait one confirm retry delay (capped at the deadline).
    /// Returns false when asked to stop.
    private func sleepRetry(deadlineMono: TimeInterval) async -> Bool {
        let delay = config.confirmRetryDelaySec
        let remaining = deadlineMono - monotonic()
        return await sleep(min(delay, max(0, remaining)))
    }

    /// One confirm task (DPGConfirmationTask Init -> Loop -> Final /
    /// Readback -> Done). Starts immediately; loops confirm until the
    /// window deadline.
    private func handleOrder(_ order: [String: Any], orderID: String, phone: String, password: String) async {
        let budget = confirmBudgetSeconds(
            order: order,
            windowSec: config.randomConfirmWindowSec,
            fallbackSec: config.randomConfirmFallbackSec
        )
        let deadlineMono = monotonic() + budget
        let amount = amountYuan(fromCents: order["totalAmount"])
        let number = orderNumber(order, orderID: orderID)
        log(.info, "尝试接单 \(number) ￥\(String(format: "%.2f", amount))")
        var confirmCalls = 0
        while !stopped {
            let remaining = deadlineMono - monotonic()
            if remaining <= 0 { break }
            let timeout = min(config.requestTimeoutSec, max(0.1, remaining))
            do {
                confirmCalls += 1
                let result = try await client.orderConfirm(orderID: orderID, timeout: timeout)
                let classification = classifyConfirm(result)
                switch classification.outcome {
                case .ok:
                    grabSuccess(order: order, orderID: orderID, confirmCalls: confirmCalls)
                    return
                case .fail:
                    log(.warning, "✗ 确认终态失败 \(number): \(classification.reason)")
                    return
                case .retry:
                    log(.info, "[confirm] retry \(number) confirm_calls=\(confirmCalls) reason=\(classification.reason)")
                    guard await sleepRetry(deadlineMono: deadlineMono) else { return }
                }
            } catch let error as ApiError {
                switch error.kind {
                case .auth:
                    releaseSeen(orderID)
                    log(.warning, "确认认证失败，等待账号单飞刷新: \(number)")
                    _ = await refreshSession(phone: phone, password: password)
                    return
                case .ip:
                    releaseSeen(orderID)
                    log(.warning, "确认触发IP风控，账号共享暂停60s，不重登录")
                    _ = await sleep(60)
                    return
                case .cancelled:
                    return
                default:
                    // No response (network / undecodable): verify
                    // instead of blind retry.
                    let checked = await readback(orderID: orderID)
                    switch checked.verdict {
                    case .ok:
                        grabSuccess(order: order, orderID: orderID, confirmCalls: confirmCalls)
                        return
                    case .fail:
                        log(.warning, "✗ 状态查询确认终态失败: \(checked.reason)")
                        return
                    case .pending:
                        guard await sleepRetry(deadlineMono: deadlineMono) else { return }
                    case .unknown:
                        log(.warning, "⚠ 确认结果未知，停止盲重试: \(number)")
                        return
                    }
                }
            } catch {
                log(.error, "抢单请求失败：\(error.localizedDescription)")
                return
            }
        }
        // Deadline reached: one final verification (stepFinal).
        let final = await readback(orderID: orderID)
        switch final.verdict {
        case .ok:
            grabSuccess(order: order, orderID: orderID, confirmCalls: confirmCalls)
        case .fail:
            log(.warning, "✗ 窗口结束，订单已不可确认: \(number) confirm_calls=\(confirmCalls)")
        case .pending:
            releaseSeen(orderID)
            log(.info, "↻ 本轮确认预算耗尽，订单仍待确认: \(number) confirm_calls=\(confirmCalls)")
        case .unknown:
            log(.warning, "⚠ 窗口结束，确认结果未知: \(number) confirm_calls=\(confirmCalls)")
        }
    }
}
