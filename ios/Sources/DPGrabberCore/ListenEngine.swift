import Foundation

/// One serial confirmation worker. A stop cancels future work, not a request already accepted by the server.
public final class ListenEngine: @unchecked Sendable {
    public typealias LogCallback = (LogLevel, String) -> Void
    public typealias SpeakCallback = (String) -> Void
    private let client: ApiClient
    private var currentConfig: DPGConfig
    private let log: LogCallback
    private let speak: SpeakCallback
    private let now: () -> TimeInterval
    private let monotonic: () -> TimeInterval
    private let customSleeper: ((TimeInterval) async -> Bool)?
    private let jitter: () -> Double
    private let history: OrderHistoryStore
    private let stateLock = NSLock()
    private var running = false
    private var task: Task<Void, Never>?
    private var credentials: (phone: String, password: String)?
    private var seen: [String: TimeInterval] = [:]
    private var runID = ""
    private var accountID = ""
    private var itemAccountKey = ""
    private var busyCooldownPending = false
    public private(set) var clerk: DPGClerk?
    public var onGrabSuccess: (([String: Any]) -> Void)?
    public var onHistoryChange: (() -> Void)?
    public var onPersistenceFailure: (() -> Void)?
    public var onPollStatus: ((PollStatus) -> Void)?
    public var isRunning: Bool { stateLock.withLock { running } }
    private var stopped: Bool { !isRunning || Task.isCancelled }
    private var config: DPGConfig { stateLock.withLock { currentConfig } }

    public init(client: ApiClient, config: DPGConfig, log: @escaping LogCallback,
                speak: SpeakCallback? = nil,
                now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 },
                monotonic: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
                sleeper: ((TimeInterval) async -> Bool)? = nil,
                jitter: @escaping () -> Double = { Double.random(in: -0.2...0.2) },
                history: OrderHistoryStore? = nil) {
        self.client = client
        self.currentConfig = config
        self.log = log
        self.speak = speak ?? { _ in }
        self.now = now
        self.monotonic = monotonic
        self.customSleeper = sleeper
        self.jitter = jitter
        // The app supplies a persistent store; tests can explicitly use an in-memory store.
        self.history = history ?? .inMemory()
    }

    public func updateConfig(_ config: DPGConfig) {
        guard let validated = try? config.validated() else { return }
        stateLock.withLock { currentConfig = validated }
    }

    @discardableResult
    public func start(phone: String, password: String) -> Bool {
        guard (try? config.validated()) != nil else { return false }
        let cleanPhone = phone.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanPhone.isEmpty, !password.isEmpty else { return false }
        return stateLock.withLock {
            guard task == nil else { return false }
            credentials = (cleanPhone, password)
            accountID = loginPasswordDigest(cleanPhone)
            itemAccountKey = accountKeyForItemSwitch(cleanPhone)
            runID = UUID().uuidString
            seen.removeAll()
            busyCooldownPending = false
            running = true
            task = Task { [weak self] in
                guard let self else { return }
                await self.run()
            }
            return true
        }
    }

    public func stop() {
        stateLock.withLock { running = false; credentials = nil; task?.cancel() }
    }

    public func stopAndWait() async {
        let pending = stateLock.withLock { () -> Task<Void, Never>? in
            running = false
            credentials = nil
            task?.cancel()
            return task
        }
        await pending?.value
    }

    private func sleep(_ seconds: TimeInterval) async -> Bool {
        guard !stopped, seconds.isFinite else { return false }
        if let customSleeper {
            let completed = await customSleeper(max(0, seconds))
            return completed && !stopped
        }
        do { try await Task.sleep(nanoseconds: UInt64(max(0, min(86400, seconds)) * 1_000_000_000)) }
        catch { return false }
        return !stopped
    }

    private func verifiedClerk(session: DPGSession) async throws -> DPGClerk {
        let decoded = try await client.clerkGet(session: session)
        guard !stopped else { throw ApiError(.cancelled, "已停止") }
        guard let found = extractClerk(decoded), found.id > 0,
              found.shopId == nil || String(found.shopId ?? 0) == session.sid else {
            throw ApiError(.api, "无法确认当前店铺的店员身份")
        }
        return found
    }

    private func refreshSession() async throws -> DPGSession {
        guard !stopped, let saved = stateLock.withLock({ credentials }) else { throw ApiError(.cancelled, "已停止") }
        let result = try await client.sessionForAccount(phone: saved.phone, password: saved.password, preferredShopID: config.shopID)
        guard !stopped else { client.session = nil; throw ApiError(.cancelled, "已停止") }
        clerk = try await verifiedClerk(session: result)
        log(.success, "会话已重新建立，店铺 sid=\(result.sid)")
        return result
    }

    private func run() async {
        defer {
            stateLock.withLock { running = false; credentials = nil; task = nil }
            log(.info, "监听已停止")
        }
        do {
            let session: DPGSession
            if let existing = client.session {
                session = existing
                clerk = try await verifiedClerk(session: existing)
                log(.info, "复用已登录会话，店铺 sid=\(existing.sid)")
            } else {
                session = try await refreshSession()
            }
            guard !stopped else { return }
            if config.onlineAfterLogin {
                try await client.updateStatus(online: true, updateAutoOnline: false)
                guard !stopped else { return }
            }
            log(.success, "当前店铺 sid=\(session.sid)，店员权限已确认")
            await pollLoop()
        } catch {
            guard !stopped, (error as? ApiError)?.kind != .cancelled else { return }
            let issue = (error as? ApiError)?.safeDiagnostic ?? "启动校验未完成，请重新登录并检查店铺状态"
            onPollStatus?(PollStatus(issue: issue))
            log(.error, "无法开始监听：\(issue)")
        }
    }

    private func pollLoop() async {
        var activeSince = monotonic()
        var failures = 0
        log(.info, "开始监听新订单（仅限前台）")
        polling: while !stopped {
            let cfg = config
            if cfg.runRestEnabled, monotonic() - activeSince >= cfg.runDurationSec {
                log(.info, "进入定时休息")
                guard await sleep(cfg.restDurationSec) else { return }
                activeSince = monotonic()
            }
            let interval = max(0.1, cfg.pollIntervalSec + min(0.2, max(-0.2, jitter())))
            do {
                let orders = try await client.randomList(pageSize: 20, timeout: cfg.requestTimeoutSec)
                guard !stopped else { return }
                if failures > 0 { log(.info, "订单连接已恢复") }
                failures = 0
                onPollStatus?(PollStatus(lastSuccessAt: Date(timeIntervalSince1970: now())))
                seen = seen.filter { now() - $0.value < config.seenTTLSec }
                for order in orders {
                    guard !stopped else { return }
                    let id = orderID(of: order)
                    let sid = client.session?.sid ?? ""
                    guard !id.isEmpty, seen[id] == nil,
                          !history.blocksConfirmation(accountID: accountID, shopID: sid, orderID: id) else { continue }
                    seen[id] = now()
                    let latest = config
                    let filter = OrderFilter(minAmount: latest.minAmount, maxAmount: latest.maxAmount,
                        memoMode: latest.memoMode, memoKeywords: latest.memoKeywords, blacklist: latest.blacklist,
                        enabledItems: latest.itemSwitchScope(accountKey: itemAccountKey, shopKey: client.session?.sid ?? ""))
                    guard filter.check(order: order).passed else {
                        log(.info, "订单不符合当前过滤条件（客户信息已脱敏）")
                        continue
                    }
                    log(.info, "检测到新订单：金额 \(String(format: "%.2f", amountYuan(fromCents: order["totalAmount"]))) 元")
                    await handleOrder(order, orderID: id)
                    if busyCooldownPending {
                        busyCooldownPending = false
                        let cooldown = config.busyCooldownSec
                        log(.info, "名额已满，已记录跳过；冷却 \(cooldown) 秒后继续监听")
                        onPollStatus?(PollStatus(issue: "同时接单名额已满，正在冷却",
                            retryAt: Date(timeIntervalSince1970: now() + Double(cooldown))))
                        guard await sleep(Double(cooldown)) else { return }
                        continue polling
                    }
                    break // At most one serial confirmation per poll; a blocked first item cannot starve later items.
                }
            } catch let error as ApiError {
                guard !stopped else { return }
                let issue = error.safeDiagnostic
                switch error.kind {
                case .cancelled: return
                case .auth:
                    onPollStatus?(PollStatus(issue: issue))
                    log(.warning, "订单连接需重新认证：\(issue)")
                    do { _ = try await refreshSession() }
                    catch {
                        guard !stopped else { return }
                        client.session = nil
                        let reason = (error as? ApiError)?.safeDiagnostic ?? "重新登录未完成，请手动重试"
                        onPollStatus?(PollStatus(issue: reason))
                        log(.error, "监听已暂停：\(reason)")
                        return
                    }
                case .ip:
                    onPollStatus?(PollStatus(issue: issue, retryAt: Date(timeIntervalSince1970: now() + 60)))
                    log(.warning, "\(issue)，60 秒后重试")
                    guard await sleep(60) else { return }
                    continue
                case .api:
                    // Permission, business and protocol errors do not heal with an endless tight poll loop.
                    onPollStatus?(PollStatus(issue: issue))
                    log(.error, "监听已暂停：\(issue)。处理后可重新开始")
                    return
                case .network:
                    if error.detail == .tls || error.httpStatus.map({ (400..<500).contains($0) && $0 != 408 && $0 != 429 }) == true {
                        onPollStatus?(PollStatus(issue: issue))
                        log(.error, "监听已暂停：\(issue)")
                        return
                    }
                    failures += 1
                    let delay = error.httpStatus == 429 ? 60 : min(60, max(1, interval) * pow(2, Double(min(failures, 6))))
                    onPollStatus?(PollStatus(issue: issue, retryAt: Date(timeIntervalSince1970: now() + delay)))
                    log(.warning, "\(issue)，第 \(failures) 次失败，\(Int(delay)) 秒后重试")
                    guard await sleep(delay) else { return }
                    continue
                }
            } catch {
                guard !stopped else { return }
                let issue = "监听遇到未分类异常，已暂停；请重新开始 [randomList · unexpected]"
                onPollStatus?(PollStatus(issue: issue))
                log(.error, issue)
                return
            }
            guard await sleep(interval) else { return }
        }
    }

    private func persist(_ row: OrderHistoryRecord) -> Bool {
        do {
            try history.put(row)
            onHistoryChange?()
            return true
        } catch {
            log(.error, "历史保存失败，已停止；请人工核验未决订单")
            onPersistenceFailure?()
            stop()
            return false
        }
    }

    private func readback(orderID: String, clerkID: Int, session: DPGSession) async -> ReconcileResult {
        guard !stopped, client.session == session else { return ReconcileResult(verdict: .unknown, reason: "任务身份已失效") }
        do {
            let response = try await client.orderGet(orderID: orderID)
            guard !stopped, client.session == session else { return ReconcileResult(verdict: .unknown, reason: "已停止") }
            return reconcileOrder(response, ownClerkID: clerkID)
        } catch { return ReconcileResult(verdict: .unknown, reason: "状态查询失败") }
    }

    private func handleOrder(_ order: [String: Any], orderID: String) async {
        guard let session = client.session, let currentClerk = clerk, currentClerk.id > 0 else { stop(); return }
        let cfg = config
        let delay = orderWaitSeconds(order: order, sid: session.sid, config: cfg, now: now())
        guard await sleep(delay), client.session == session else { return }
        let latest = config
        let filter = OrderFilter(minAmount: latest.minAmount, maxAmount: latest.maxAmount,
            memoMode: latest.memoMode, memoKeywords: latest.memoKeywords, blacklist: latest.blacklist,
            enabledItems: latest.itemSwitchScope(accountKey: itemAccountKey, shopKey: client.session?.sid ?? ""))
        guard filter.check(order: order).passed else { return }
        let attempt = UUID().uuidString
        var row = OrderHistoryRecord(id: "\(runID):\(attempt)", accountID: accountID, shopID: session.sid,
            orderID: orderID, runID: runID, attemptID: attempt, timestamp: Date(timeIntervalSince1970: now()),
            amount: amountYuan(fromCents: order["totalAmount"]), wechatID: order["userWechatId"] as? String ?? "",
            memo: order["userMemo"] as? String ?? "", outcome: .unknown, reason: "请求准备提交；结果需核验")
        guard persist(row), !stopped else { return }
        var attempted = false
        var riskPause = false
        defer {
            if attempted {
                row.timestamp = Date(timeIntervalSince1970: now())
                let saved = persist(row)
                if saved, row.outcome == .success, !stopped {
                    log(.success, "抢单成功，请打开平台核对订单")
                    speak("抢单成功")
                    onGrabSuccess?(order)
                    if config.autoStopOnSuccess { stop() }
                }
            }
        }
        let deadline = monotonic() + confirmBudgetSeconds(order: order,
            windowSec: cfg.randomConfirmWindowSec, fallbackSec: cfg.randomConfirmFallbackSec)
        log(.info, "尝试接单 ***\(orderID.suffix(4))")
        while !stopped, client.session == session, monotonic() < deadline {
            let current = config
            let currentFilter = OrderFilter(minAmount: current.minAmount, maxAmount: current.maxAmount,
                memoMode: current.memoMode, memoKeywords: current.memoKeywords, blacklist: current.blacklist,
                enabledItems: current.itemSwitchScope(accountKey: itemAccountKey, shopKey: session.sid))
            guard currentFilter.check(order: order).passed else {
                row.reason = "提交规则已变更；未再次提交，请核验先前结果"
                return
            }
            attempted = true
            do {
                let response = try await client.orderConfirm(orderID: orderID,
                    timeout: min(cfg.requestTimeoutSec, max(0.1, deadline - monotonic())))
                guard !stopped, client.session == session else { row.reason = "停止期间的提交结果未知，请核验"; return }
                let result = classifyConfirm(response)
                switch result.outcome {
                case .ok: row.outcome = .success; row.reason = "平台确认成功"; return
                case .busy:
                    row.outcome = .skipped
                    row.reason = result.reason
                    busyCooldownPending = true
                    log(.info, result.reason)
                    return
                case .fail:
                    row.reason = result.reason
                    row.outcome = Self.failureOutcome(result.reason)
                    return
                case .verify:
                    let checked = await readback(orderID: orderID, clerkID: currentClerk.id, session: session)
                    guard !stopped else { return }
                    switch checked.verdict {
                    case .ok: row.outcome = .success; row.reason = "回查确认本人订单"; return
                    case .fail: row.outcome = Self.failureOutcome(checked.reason); row.reason = checked.reason; return
                    case .pending, .unknown: row.reason = "返回文案不确定，未再次提交；请核验"; return
                    }
                case .retry: break
                }
            } catch let error as ApiError {
                guard !stopped else { row.reason = "停止期间的提交结果未知，请核验"; return }
                switch error.kind {
                case .cancelled: return
                case .auth:
                    client.session = nil
                    row.reason = "提交认证失败，结果未核验"
                    stop()
                    return
                case .ip:
                    row.outcome = .risk
                    row.reason = "平台风控，监听已暂停"
                    riskPause = true
                default:
                    let checked = await readback(orderID: orderID, clerkID: currentClerk.id, session: session)
                    guard !stopped else { return }
                    switch checked.verdict {
                    case .ok: row.outcome = .success; row.reason = "回查确认成功"; return
                    case .fail: row.outcome = Self.failureOutcome(checked.reason); row.reason = checked.reason; return
                    case .unknown: row.reason = "结果未知，禁止自动重发；请只读核验"; return
                    case .pending: row.reason = "提交响应未知，回查仍待确认；禁止自动重发"; return
                    }
                }
            } catch { row.reason = "请求异常，需人工核验"; return }
            if riskPause {
                guard persist(row) else { return }
                _ = await sleep(60)
                return
            }
            guard await sleep(min(max(0.01, config.confirmRetryDelaySec), max(0, deadline - monotonic()))) else { return }
        }
        guard !stopped, client.session == session else { return }
        let checked = await readback(orderID: orderID, clerkID: currentClerk.id, session: session)
        guard !stopped else { return }
        switch checked.verdict {
        case .ok: row.outcome = .success; row.reason = "窗口结束回查成功"
        case .fail: row.outcome = Self.failureOutcome(checked.reason); row.reason = checked.reason
        case .pending: row.reason = "确认预算耗尽；仍待确认，需人工核验"
        case .unknown: row.reason = "确认结果未知，禁止自动重发"
        }
    }

    private static func failureOutcome(_ reason: String) -> OrderOutcome {
        reason.contains("他人") || reason.contains("其他人") ? .skipped : .failed
    }
}
