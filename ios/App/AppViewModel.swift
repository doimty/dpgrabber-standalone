import Foundation
import Combine
import UserNotifications
import UIKit

@MainActor
final class AppViewModel: ObservableObject {
    @Published var config: DPGConfig {
        didSet { configurationChanged(from: oldValue) }
    }
    @Published var phone = "" { didSet { credentialsChanged() } }
    @Published var password = "" { didSet { credentialsChanged() } }
    @Published private(set) var isRunning = false
    @Published private(set) var isStopping = false
    @Published private(set) var isLoggingIn = false
    @Published private(set) var isActing = false
    @Published private(set) var isLoggedIn = false
    @Published private(set) var isOnline = false
    @Published private(set) var randomOrderEnabled = false
    @Published private(set) var accountSummary = "尚未登录"
    @Published private(set) var weeklyStats = WeeklyStatistics(raw: [:])
    @Published private(set) var itemOptions: [ItemSwitchOption] = []
    @Published private(set) var selectedItemIDs: Set<Int> = []
    @Published private(set) var todayCounts: [OrderOutcome: Int] = [:]
    @Published private(set) var statusText = "未开始"
    @Published private(set) var listenIssue: String?
    @Published private(set) var listenRetryAt: Date?
    @Published private(set) var lastPollAt: Date?
    @Published var keepAliveText = "仅限前台运行；进入后台停止新增请求，未决订单保存为未知"
    @Published private(set) var foundCount = 0
    @Published private(set) var grabbedCount = 0
    @Published private(set) var historyRows: [OrderHistoryRecord] = []
    @Published private(set) var storageReady = true
    @Published var lastError: String?
    let logStore = LogStore()
    private let voice = VoiceAnnouncer()
    private var engine: ListenEngine?
    private var client: ApiClient?
    private var history: OrderHistoryStore?
    private var pollTimer: Timer?
    private var generation = UUID()
    private var loginTask: Task<Void, Never>?
    private var actionTask: Task<Void, Never>?
    private var shutdownTask: Task<Void, Never>?
    private var clientIdentity: [String]?
    private var persistedConfig = DPGConfig()
    private var restoringConfig = false
    private var configurationReady = true
    private var foregroundActive = true
    private var cleanupTaskID: UIBackgroundTaskIdentifier = .invalid

    var isBusy: Bool { isStopping || isLoggingIn || isActing }
    var accountEditingDisabled: Bool { isRunning || isBusy }
    var connectionEditingDisabled: Bool { isRunning || isBusy }
    var serverControlsDisabled: Bool { !isLoggedIn || isRunning || isBusy || !storageReady }
    private var currentAccountID: String { loginPasswordDigest(phone.trimmingCharacters(in: .whitespacesAndNewlines)) }

    init() {
        var initial = DPGConfig()
        var problem: String?
        do { initial = try loadConfig() }
        catch { problem = "配置读取失败；请保留原文件并检查，当前禁止业务操作" }
        config = initial
        persistedConfig = initial
        configurationReady = problem == nil
        if let saved = try? CredentialStore.load() { phone = saved.phone; password = saved.password }
        voice.enabled = initial.voiceEnabled
        do {
            guard let url = defaultConfigURL()?.deletingLastPathComponent().appendingPathComponent("order-history.json") else {
                throw ConfigError("无法定位历史存储")
            }
            history = try OrderHistoryStore(url: url)
            refreshHistory()
        } catch { storageReady = false; problem = "历史文件读取失败；为保护未决订单，当前禁止业务操作" }
        lastError = problem
    }

    private func connectionValues(_ cfg: DPGConfig) -> [String] {
        [String(cfg.shopID), cfg.aesKeyHex, cfg.aesIVHex, cfg.aesKeyHexAlt, cfg.aesIVHexAlt]
    }

    private func identity() -> [String] {
        [phone.trimmingCharacters(in: .whitespacesAndNewlines), loginPasswordDigest(password.trimmingCharacters(in: .whitespacesAndNewlines))] + connectionValues(config)
    }

    private func invalidateSession() {
        client?.session = nil
        client = nil
        clientIdentity = nil
        isLoggedIn = false
        isOnline = false
        randomOrderEnabled = false
        accountSummary = "尚未登录"
        weeklyStats = WeeklyStatistics(raw: [:])
        itemOptions = []
        selectedItemIDs = []
        listenIssue = nil
        listenRetryAt = nil
        lastPollAt = nil
    }

    private func credentialsChanged() {
        // Views disable editing while a task is active. The identity check also protects programmatic changes.
        if clientIdentity != nil, clientIdentity != identity() {
            if isRunning || isLoggingIn || isActing { stopListening() }
            invalidateSession()
        }
        refreshHistory()
    }

    private func configurationChanged(from previous: DPGConfig) {
        guard !restoringConfig else { return }
        if connectionValues(config) != connectionValues(previous) {
            if connectionEditingDisabled {
                restoreConfiguration(previous)
                lastError = "请先停止任务，再修改店铺或协议参数"
                return
            }
            invalidateSession()
        }
        _ = saveSettings()
    }

    private func restoreConfiguration(_ value: DPGConfig) {
        restoringConfig = true
        config = value
        restoringConfig = false
    }

    @discardableResult
    func saveSettings() -> Bool {
        guard configurationReady else { lastError = "原配置未成功读取，暂不覆盖文件"; return false }
        do {
            let validated = try config.validated()
            try saveConfig(validated)
            persistedConfig = validated
            restoreConfiguration(validated)
            engine?.updateConfig(validated)
            voice.enabled = validated.voiceEnabled
            return true
        } catch {
            restoreConfiguration(persistedConfig)
            lastError = "设置未保存，已恢复上次有效值"
            return false
        }
    }

    func saveCredentials() {
        guard !accountEditingDisabled else { lastError = "请先停止正在执行的任务"; return }
        do {
            try CredentialStore.save(phone: phone.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
            logStore.append(.success, "账号已保存到本机钥匙串")
        } catch { lastError = "账号保存失败，未确认持久化" }
    }

    func clearCredentials() { stopWork(clearCredentials: true, background: false) }

    func resetAESKeys() {
        guard !connectionEditingDisabled else { return }
        var updated = config
        updated.aesKeyHex = defaultAESKeyHex
        updated.aesIVHex = defaultAESIVHex
        updated.aesKeyHexAlt = altAESKeyHex
        updated.aesIVHexAlt = altAESIVHex
        config = updated
    }

    func setShopWait(_ sid: String, seconds: Double) {
        var updated = config
        updated.confirmWaitByShop[sid] = seconds
        config = updated
    }

    private func makeClient() -> ApiClient {
        ApiClient(aesKeyHex: config.aesKeyHex, aesIVHex: config.aesIVHex,
            aesKeyHexAlt: config.aesKeyHexAlt, aesIVHexAlt: config.aesIVHexAlt)
    }

    func login() {
        guard foregroundActive, !isRunning, !isBusy, configurationReady, storageReady, history != nil, saveSettings() else {
            if history == nil { lastError = "历史存储不可用，平台操作已禁用；请先恢复历史文件" }
            return
        }
        let cleanPhone = phone.trimmingCharacters(in: .whitespacesAndNewlines)
        let secret = password
        guard !cleanPhone.isEmpty, !secret.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            lastError = "请填写手机号和密码"; return
        }
        invalidateSession()
        let id = UUID()
        generation = id
        let fingerprint = identity()
        let cfg = config
        let api = makeClient()
        isLoggingIn = true
        statusText = "正在校验登录与店铺权限"
        loginTask = Task { @MainActor in
            defer { loginTask = nil; isLoggingIn = false }
            do {
                _ = try await api.sessionForAccount(phone: cleanPhone, password: secret, preferredShopID: cfg.shopID)
                guard generation == id, !Task.isCancelled, foregroundActive, identity() == fingerprint else { api.session = nil; return }
                client = api
                clientIdentity = fingerprint
                isLoggedIn = true
                statusText = "已登录，店铺 \(api.session?.sid ?? "")"
                if cfg.onlineAfterLogin { try await api.updateStatus(online: true, updateAutoOnline: false) }
                guard generation == id, !Task.isCancelled else { return }
                await refreshAccountData(api, id: id)
            } catch {
                guard generation == id, !Task.isCancelled else { return }
                invalidateSession()
                statusText = "登录未完成"
                let reason = (error as? ApiError)?.safeDiagnostic ?? "登录或权限校验未完成"
                lastError = reason
                logStore.append(.error, "登录失败：\(reason)")
            }
        }
    }

    private func serverAction(_ operation: @escaping @MainActor (ApiClient) async throws -> Void) {
        guard foregroundActive, configurationReady, storageReady, history != nil, !serverControlsDisabled, clientIdentity == identity(), let api = client, api.session != nil else {
            lastError = "请先登录并停止监听，再进行平台操作"; return
        }
        let id = generation
        isActing = true
        actionTask = Task { @MainActor in
            defer { actionTask = nil; isActing = false }
            do {
                try await operation(api)
                guard generation == id, !Task.isCancelled, foregroundActive else { return }
                await refreshAccountData(api, id: id)
            } catch {
                guard generation == id, !Task.isCancelled else { return }
                if (error as? ApiError)?.kind == .auth { invalidateSession() }
                let reason = (error as? ApiError)?.safeDiagnostic ?? "平台操作未完成"
                lastError = reason + "。界面未应用本次更改，请刷新核验"
                logStore.append(.warning, reason)
            }
        }
    }

    func setOnline(_ value: Bool) {
        serverAction { api in try await api.updateStatus(online: value, updateAutoOnline: false) }
    }

    func setRandomOrder(_ value: Bool) {
        serverAction { api in try await api.updateRandomOrderSwitch(enabled: value) }
    }

    func setItem(_ itemID: Int, enabled: Bool) {
        var ids = selectedItemIDs
        if enabled { ids.insert(itemID) } else { ids.remove(itemID) }
        let chosen = ids.sorted()
        serverAction { api in try await api.updateItemSwitch(switchID: itemID, enabledIDs: chosen) }
    }

    func refreshAccount() { serverAction { _ in } }

    private func refreshAccountData(_ api: ApiClient, id: UUID) async {
        guard let session = api.session else { return }
        do {
            let decoded = try await api.clerkGet(session: session)
            guard generation == id, !Task.isCancelled, api.session == session else { return }
            guard let clerk = extractClerk(decoded) else { throw ApiError(.api, "无有效店员身份") }
            let raw = decoded["clerk"] as? [String: Any] ?? decoded["data"] as? [String: Any] ?? decoded
            isOnline = parseCode(["code": raw["status"] ?? NSNull()]) == 1
            if let value = raw["randomOrderSwitch"] as? Bool { randomOrderEnabled = value }
            else if let value = raw["randomOrderSwitch"] as? String { randomOrderEnabled = value == "true" || value == "1" }
            accountSummary = "\(clerk.name ?? "店员") · ID \(clerk.id) · 店铺 \(session.sid)"
        } catch {
            guard generation == id, !Task.isCancelled else { return }
            if (error as? ApiError)?.kind == .auth { invalidateSession(); return }
            accountSummary = "店员状态暂不可用；请重新刷新"
        }
        let week = PlatformWeek()
        var rawStats: [String: Any] = [:]
        var fallback: Int?
        do {
            let response = try await api.statisticsClerkOrder(startTime: week.startDate, endTime: week.endDate)
            rawStats = response["list"] as? [String: Any] ?? [:]
        } catch {
            if Task.isCancelled { return }
            if (error as? ApiError)?.kind == .auth {
                guard generation == id else { return }; invalidateSession(); return
            }
        }
        if rawStats.isEmpty {
            do {
                let response = try await api.orderList(startTime: week.startDateTime, endTime: week.endDateTime)
                fallback = parseCode(["code": response["total"] ?? NSNull()]).flatMap { $0 >= 0 ? $0 : nil }
            } catch { if Task.isCancelled { return } }
        }
        guard generation == id, !Task.isCancelled, api.session == session else { return }
        weeklyStats = WeeklyStatistics(raw: rawStats, fallbackCount: fallback)
        do {
            let response = try await api.getItemSwitchList()
            guard generation == id, !Task.isCancelled, api.session == session else { return }
            let data = response["data"] as? [String: Any] ?? response
            guard let items = data["itemSwitchList"] as? [[String: Any]], let enabled = data["clerkItemSwitchIds"] as? [Any] else {
                throw ApiError(.api, "接单类型数据缺失")
            }
            var unique: [Int: ItemSwitchOption] = [:]
            for item in items {
                if let value = parseCode(["code": item["id"] ?? NSNull()]), value > 0 {
                    unique[value] = ItemSwitchOption(id: value, name: item["name"] as? String ?? "类型 \(value)")
                }
            }
            itemOptions = unique.values.sorted { $0.id < $1.id }
            selectedItemIDs = Set(enabled.compactMap { parseCode(["code": $0]) })
        } catch {
            guard generation == id, !Task.isCancelled else { return }
            itemOptions = []
            selectedItemIDs = []
        }
        refreshHistory()
    }

    func startListening() {
        guard foregroundActive, storageReady, !isRunning, !isBusy, engine == nil, saveSettings() else { return }
        guard let history, let api = client, api.session != nil, isLoggedIn, clientIdentity == identity() else {
            lastError = "请先登录并确认店铺权限及历史存储"; return
        }
        let id = UUID()
        generation = id
        let worker = ListenEngine(client: api, config: config, log: { [weak self] level, message in
            Task { @MainActor in
                guard let self, self.generation == id else { return }
                if message.hasPrefix("检测到新订单") { self.foundCount += 1 }
                self.logStore.append(level, message)
                self.isLoggedIn = api.session != nil
            }
        }, speak: { [weak self] text in
            Task { @MainActor in
                guard let self, self.generation == id else { return }
                self.voice.speak(text)
            }
        }, history: history)
        worker.onPollStatus = { [weak self] status in
            Task { @MainActor in
                guard let self, self.generation == id else { return }
                self.listenIssue = status.issue
                self.listenRetryAt = status.retryAt
                if let time = status.lastSuccessAt { self.lastPollAt = time }
                if status.issue != nil {
                    self.statusText = status.retryAt == nil ? "监听已暂停" : "连接待恢复"
                } else { self.statusText = "正在监听" }
            }
        }
        worker.onHistoryChange = { [weak self] in Task { @MainActor in self?.refreshHistory() } }
        worker.onPersistenceFailure = { [weak self] in
            Task { @MainActor in
                guard let self, self.generation == id || self.isStopping else { return }
                self.storageReady = false
                self.lastError = "历史写入失败，平台操作已锁定；请保留文件并恢复存储后重启核验"
            }
        }
        worker.onGrabSuccess = { [weak self] _ in
            Task { @MainActor in
                guard let self, self.generation == id, self.foregroundActive else { return }
                self.statusText = "接单成功，已保存历史"
                self.refreshHistory()
                self.postGrabNotification()
            }
        }
        engine = worker
        if worker.start(phone: phone, password: password) {
            listenIssue = nil
            listenRetryAt = nil
            lastPollAt = nil
            isRunning = true
            foundCount = 0
            statusText = "正在检查订单连接，请保持前台"
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
            startPollTimer()
        } else { engine = nil; lastError = "监听无法启动，请检查设置" }
    }

    func stopListening() { stopWork(clearCredentials: false, background: false) }

    private func stopWork(clearCredentials: Bool, background: Bool) {
        // Entering the background while an earlier stop is awaiting cleanup still needs a bounded cleanup budget.
        if background, cleanupTaskID == .invalid,
           isStopping || engine != nil || loginTask != nil || actionTask != nil {
            cleanupTaskID = UIApplication.shared.beginBackgroundTask(withName: "Finish stopping order task") { [weak self] in
                Task { @MainActor in self?.endBackgroundCleanup() }
            }
        }
        guard !isStopping else { return }
        let stopID = UUID()
        generation = stopID
        let previous = engine
        let pendingLogin = loginTask
        let pendingAction = actionTask
        pendingLogin?.cancel()
        pendingAction?.cancel()
        previous?.stop()
        isStopping = true
        isRunning = false
        stopPollTimer()
        voice.stop()
        shutdownTask = Task { @MainActor in
            await pendingLogin?.value
            await pendingAction?.value
            await previous?.stopAndWait()
            guard generation == stopID else { endBackgroundCleanup(); return }
            engine = nil
            listenRetryAt = nil
            if clearCredentials {
                invalidateSession()
                phone = ""
                password = ""
                do {
                    try CredentialStore.delete()
                    // Settings never encode credentials. Do not overwrite a damaged settings file while clearing Keychain.
                    statusText = configurationReady ? "账号已清除" : "钥匙串账号已清除；原配置仍保留待修复"
                } catch { lastError = "凭据清理未完成，请重试"; statusText = "清理未完成" }
            } else {
                isLoggedIn = client?.session != nil && clientIdentity == identity()
                statusText = background ? "后台已停止，返回后请手动启动" : "已停止，未知结果请核验历史"
            }
            isStopping = false
            shutdownTask = nil
            refreshHistory()
            endBackgroundCleanup()
        }
    }

    func enteredBackground() { foregroundActive = false; stopWork(clearCredentials: false, background: true) }
    func enteredForeground() { foregroundActive = true; refreshHistory() }
    private func endBackgroundCleanup() {
        guard cleanupTaskID != .invalid else { return }
        UIApplication.shared.endBackgroundTask(cleanupTaskID)
        cleanupTaskID = .invalid
    }

    func blacklist(_ row: OrderHistoryRecord) {
        guard row.accountID == currentAccountID, !row.wechatID.isEmpty else { return }
        var updated = config
        updated.blacklist = Array(Set(updated.blacklist + [row.wechatID])).sorted()
        config = updated
    }

    func blockMemo(_ row: OrderHistoryRecord) {
        guard row.accountID == currentAccountID, !row.memo.isEmpty else { return }
        var updated = config
        updated.memoKeywords = Array(Set(updated.memoKeywords + [row.memo])).sorted()
        config = updated
    }

    func verifyUnknown(_ row: OrderHistoryRecord) {
        guard row.outcome == .unknown, row.accountID == currentAccountID, let api = client, api.session?.sid == row.shopID else {
            lastError = "请登录此历史记录对应的账号和店铺后，只读核验"; return
        }
        serverAction { [weak self] api in
            let clerkData = try await api.clerkGet()
            guard let clerk = extractClerk(clerkData) else { throw ApiError(.api, "无法核验店员身份") }
            let response = try await api.orderGet(orderID: row.orderID)
            try Task.checkCancellation()
            let verdict = reconcileOrder(response, ownClerkID: clerk.id)
            guard let self else { return }
            var updated = row
            switch verdict.verdict {
            case .ok: updated.outcome = .success; updated.reason = "手动只读核验为本人订单"
            case .fail:
                updated.outcome = verdict.reason.contains("他人") || verdict.reason.contains("其他人") ? .skipped : .failed
                updated.reason = verdict.reason
            case .pending, .unknown: updated.reason = "仍未确认归属；未再次提交"
            }
            guard let history = self.history else { throw ConfigError("历史存储不可用") }
            do { try history.put(updated) }
            catch {
                self.storageReady = false
                self.lastError = "核验结果保存失败，平台操作已锁定"
                throw error
            }
            self.refreshHistory()
        }
    }

    private func refreshHistory() {
        guard !phone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            historyRows = []; todayCounts = [:]; grabbedCount = 0; return
        }
        historyRows = history?.entries(accountID: currentAccountID) ?? []
        todayCounts = history?.todayCounts(accountID: currentAccountID) ?? [:]
        grabbedCount = todayCounts[.success] ?? 0
    }

    private func startPollTimer() {
        stopPollTimer()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.isRunning, self.engine?.isRunning == false {
                    let previous = self.engine
                    await previous?.stopAndWait()
                    guard self.engine === previous else { return }
                    self.isRunning = false
                    self.engine = nil
                    self.stopPollTimer()
                    self.refreshHistory()
                    self.isLoggedIn = self.client?.session != nil && self.clientIdentity == self.identity()
                    self.listenRetryAt = nil
                    if self.listenIssue != nil { self.statusText = "监听已暂停" }
                    else if self.statusText.hasPrefix("监听中") || self.statusText.hasPrefix("正在检查订单") || self.statusText == "正在监听" { self.statusText = "已停止" }
                }
            }
        }
    }
    private func stopPollTimer() { pollTimer?.invalidate(); pollTimer = nil }
    private func postGrabNotification() {
        let content = UNMutableNotificationContent()
        content.title = "订单确认成功"
        content.body = "已保存接单历史，请打开平台核对订单。"
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}
