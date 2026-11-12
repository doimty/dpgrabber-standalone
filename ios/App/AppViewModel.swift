// DPGrabber iOS app - View model bridging the UI and DPGrabberCore.
//
// Responsibilities:
// - Load/save DPGConfig (Application Support/DPGrabber/config.json).
//   The password is NEVER written to the config file: it lives in the
//   Keychain only, and config.password is cleared before every save.
// - Build the ApiClient / ListenEngine from the current config.
// - Start/stop listening, drive the silent-audio keep-alive, speak
//   announcements, and post a local notification on a successful grab.
// - Publish running state and a short status line for the UI.

import Foundation
import UserNotifications

@MainActor
final class AppViewModel: ObservableObject {
    // MARK: - Published UI state

    @Published var config: DPGConfig
    @Published var phone: String = ""
    @Published var password: String = ""
    @Published var isRunning: Bool = false
    @Published var statusText: String = "未开始"
    @Published var keepAliveText: String = "保活：未启动"
    @Published var foundCount: Int = 0
    @Published var grabbedCount: Int = 0
    @Published var lastError: String?

    // MARK: - Services

    let logStore = LogStore()
    private let voice = VoiceAnnouncer()
    private let keepAlive = AudioKeepAlive()
    private var engine: ListenEngine?
    private var pollTimer: Timer?

    init() {
        var loaded: DPGConfig
        do {
            loaded = try loadConfig()
        } catch {
            loaded = DPGConfig()
        }
        // The password is never persisted in the config file.
        loaded.password = ""
        self.config = loaded
        if let saved = try? CredentialStore.load() {
            phone = saved.phone
            password = saved.password
            if config.phone.isEmpty { config.phone = saved.phone }
        } else if !config.phone.isEmpty {
            phone = config.phone
        }
        voice.enabled = config.voiceEnabled
        keepAlive.onStateChange = { [weak self] state in
            Task { @MainActor in
                self?.keepAliveText = "保活：\(state.rawValue)"
            }
        }
    }

    // MARK: - Credentials

    func saveCredentials() {
        do {
            try CredentialStore.save(phone: phone, password: password)
            config.phone = phone
            log(.success, "账号已保存到钥匙串")
        } catch {
            lastError = error.localizedDescription
            log(.error, "保存账号失败：\(error.localizedDescription)")
        }
    }

    func clearCredentials() {
        do {
            try CredentialStore.delete()
            phone = ""
            password = ""
            config.phone = ""
            log(.info, "已清除保存的账号")
        } catch {
            lastError = error.localizedDescription
            log(.error, "清除账号失败：\(error.localizedDescription)")
        }
    }

    // MARK: - Settings

    /// Validate and persist the current config (password excluded).
    func saveSettings() {
        do {
            var toSave = config
            toSave.phone = phone
            toSave.password = ""
            toSave = try toSave.validated()
            config = toSave
            try saveConfig(toSave)
            voice.enabled = toSave.voiceEnabled
            log(.success, "设置已保存")
        } catch {
            lastError = error.localizedDescription
            log(.error, "保存设置失败：\(error.localizedDescription)")
        }
    }

    /// Restore the built-in AES key/IV pairs (primary + alternate).
    func resetAESKeys() {
        config.aesKeyHex = defaultAESKeyHex
        config.aesIVHex = defaultAESIVHex
        config.aesKeyHexAlt = altAESKeyHex
        config.aesIVHexAlt = altAESIVHex
        log(.info, "AES 密钥已恢复为默认值")
    }

    // MARK: - Listening

    func startListening() {
        let phone = phone.trimmingCharacters(in: .whitespaces)
        let password = password.trimmingCharacters(in: .whitespaces)
        guard !phone.isEmpty, !password.isEmpty else {
            lastError = "请先填写手机号和密码"
            log(.error, "请先填写手机号和密码")
            return
        }
        do {
            var cfg = config
            cfg.phone = phone
            cfg = try cfg.validated()
            config = cfg
        } catch {
            lastError = error.localizedDescription
            log(.error, "设置无效：\(error.localizedDescription)")
            return
        }
        requestNotificationPermission()
        let client = ApiClient(
            aesKeyHex: config.aesKeyHex, aesIVHex: config.aesIVHex,
            aesKeyHexAlt: config.aesKeyHexAlt, aesIVHexAlt: config.aesIVHexAlt,
            verifyTLS: config.verifyTLS
        )
        let engine = ListenEngine(
            client: client,
            config: config,
            log: { [weak self] level, message in
                Task { @MainActor in self?.handleEngineLog(level, message) }
            },
            speak: { [weak self] text in
                Task { @MainActor in self?.voice.speak(text) }
            }
        )
        engine.onGrabSuccess = { [weak self] order in
            Task { @MainActor in self?.handleGrabSuccess(order) }
        }
        self.engine = engine
        foundCount = 0
        if engine.start(phone: phone, password: password) {
            isRunning = true
            statusText = "监听中"
            keepAlive.start()
            startPollTimer()
            log(.info, "开始监听（店铺 \(config.shopID)）")
        } else {
            lastError = "监听已在运行"
        }
    }

    func stopListening() {
        engine?.stop()
        engine = nil
        voice.stop()
        keepAlive.stop()
        stopPollTimer()
        isRunning = false
        statusText = "已停止"
        log(.info, "已停止监听")
    }

    // MARK: - Private

    private func handleEngineLog(_ level: LogLevel, _ message: String) {
        if message.hasPrefix("检测到新订单") { foundCount += 1 }
        logStore.append(level, message)
        if level == .error || level == .warning {
            statusText = message
        }
    }

    private func handleGrabSuccess(_ order: [String: Any]) {
        grabbedCount += 1
        statusText = "抢单成功"
        postGrabNotification(order: order)
        // The engine auto-stops on success; mirror that in the UI.
        engine = nil
        voice.stop()
        keepAlive.stop()
        stopPollTimer()
        isRunning = false
    }

    private func log(_ level: LogLevel, _ message: String) {
        logStore.append(level, message)
    }

    private func startPollTimer() {
        stopPollTimer()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let running = self.engine?.isRunning ?? false
                if self.isRunning && !running {
                    // Engine stopped on its own (grabbed, or a fatal error).
                    self.isRunning = false
                    self.keepAlive.stop()
                    self.stopPollTimer()
                    if self.statusText == "监听中" { self.statusText = "已停止" }
                }
            }
        }
    }

    private func stopPollTimer() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func postGrabNotification(order: [String: Any]) {
        let amount = amountYuan(fromCents: order["totalAmount"])
        let content = UNMutableNotificationContent()
        content.title = "抢单成功"
        content.body = "已抢到一单，金额 \(String(format: "%.2f", amount)) 元，请去查看订单。"
        content.sound = .default
        let request = UNNotificationRequest(identifier: "dpgrabber-grab-\(Date().timeIntervalSince1970)",
                                            content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
