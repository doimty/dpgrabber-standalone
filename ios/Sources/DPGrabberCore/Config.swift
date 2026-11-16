// DPGrabber iOS - Local configuration.
//
// 设置 JSON 仅保存监听规则与协议参数，不序列化手机号、密码或密码摘要。
// 凭据只由 App 层存入设备限定 Keychain；旧版明文字段加载后擦除。
//
// No real account, token or password is ever shipped in code or in the
// template: `phone` / `password` default to empty strings and the user
// fills them in through the UI.
//
// Key/IV defaults follow PROTOCOL.md §4 and §10.6: the primary pair is
// DPGrabber's AES-256 set, the alternate pair is the PC reference tool's
// AES-128 set. The API client tries the primary pair first and falls back
// to the alternate pair when decryption fails ("decrypt with whichever
// works"). Both pairs are editable in Advanced settings.

import Foundation
import CoreFoundation

/// Errors raised by config validation / IO.
public struct ConfigError: Error, Equatable {
    public let message: String
    public init(_ message: String) { self.message = message }
}

/// DPGrabber AES-256 set (DPGAPIClient.m:12-13). Primary.
public let defaultAESKeyHex = "faf6bcb4eeaa8aa62154ed3b8c14cf5bce08cdf83086b9188abdca6f2c8bcbb1"
public let defaultAESIVHex = "d45df726d59d21fac023cdad3dfcea86"
/// PC reference tool AES-128 set (DP.py:305-306). Alternate / fallback.
/// NOTE: in the reference tool's config the key named "key" holds
/// 81b120ef... and the key named "iv" holds e6a4cc05...; the AES key is
/// 81b120ef... and the AES IV is e6a4cc05... (PROTOCOL.md §4.3).
public let altAESKeyHex = "81b120ef00216c33b266763abb02e6d1"
public let altAESIVHex = "e6a4cc0507dfe344b042289eeb945dce"

public struct DPGConfig: Codable, Equatable {
    // --- login account (manual mode, PROTOCOL.md §3.2) ---
    public var phone: String = ""
    public var password: String = ""
    /// Preferred shop to switch to after login (DPGrabber default 92).
    public var shopID: Int = 92
    // --- listening rules (PROTOCOL.md §6-§7) ---
    /// Yuan; orders below this are dropped.
    public var minAmount: Double = 10.0
    /// Yuan; 0 means no upper limit.
    public var maxAmount: Double = 0.0
    /// Confirm window (seconds) for a random order that has a createTime
    /// (DPGConfirmationTask deadlineForOrderAtWall).
    public var randomConfirmWindowSec: Double = 20
    /// Confirm window (seconds) for a random order without a createTime.
    public var randomConfirmFallbackSec: Double = 20
    /// Delay (seconds) between confirm retries inside the window.
    public var confirmRetryDelaySec: Double = 0.5
    /// Delay between empty polls (reference tool ListenDelay default).
    public var pollIntervalSec: Double = 0.6
    public var autoStopOnSuccess: Bool = true
    public var onlineAfterLogin: Bool = false
    public var useShopWait: Bool = true
    public var globalWaitSec: Double = 3
    public var confirmWaitByShop: [String: Double] = ["92": 5, "50": 3, "105": 3, "1391": 3]
    /// 同时接单名额满（busy）后的暂停时长（秒）。
    public var busyCooldownSec: Int = 60
    /// 特殊店铺（50/105/1391）的等待微调（秒）；92 及其他店铺不应用。
    public var autoWaitOffset: Double = 0
    /// 当前启用的接单类型 ID（仅在下方作用域匹配时用于过滤）。
    public var itemSwitchIds: [Int] = []
    /// 接单类型缓存归属的 SHA-256 账号摘要；独立于已有历史记录的 MD5 键。
    public var itemSwitchAccountKey: String = ""
    /// 接单类型缓存归属的店铺 sid。
    public var itemSwitchShopId: String = ""
    public var runRestEnabled: Bool = false
    public var runDurationSec: Double = 1800
    public var restDurationSec: Double = 300
    /// 0 = ignore memos, 1 = drop orders that have any memo.
    public var memoMode: Int = 0
    /// Drop an order if its memo contains any of these (case-insensitive).
    public var memoKeywords: [String] = []
    /// WeChat ids; drop orders from these customers.
    public var blacklist: [String] = []
    // --- crypto (PROTOCOL.md §4) ---
    public var aesKeyHex: String = defaultAESKeyHex
    public var aesIVHex: String = defaultAESIVHex
    public var aesKeyHexAlt: String = altAESKeyHex
    public var aesIVHexAlt: String = altAESIVHex
    // --- transport / misc ---
    public var requestTimeoutSec: Double = 15
    /// Verify the server TLS certificate (recommended).
    public var verifyTLS: Bool = true
    /// Spoken announcements (AVSpeechSynthesizer).
    public var voiceEnabled: Bool = true
    /// De-dup window for already-seen order ids (seconds).
    public var seenTTLSec: Double = 60

    public init() {}

    enum CodingKeys: String, CodingKey {
        case autoStopOnSuccess = "auto_stop_on_success"
        case onlineAfterLogin = "online_after_login"
        case useShopWait = "use_shop_wait"
        case globalWaitSec = "global_wait_sec"
        case confirmWaitByShop = "confirm_wait_by_shop"
        case busyCooldownSec = "busy_cooldown_sec"
        case autoWaitOffset = "auto_wait_offset"
        case itemSwitchIds = "item_switch_ids"
        case itemSwitchAccountKey = "item_switch_account_key"
        case itemSwitchShopId = "item_switch_shop_id"
        case runRestEnabled = "run_rest_enabled"
        case runDurationSec = "run_duration_sec"
        case restDurationSec = "rest_duration_sec"
        case shopID = "shop_id"
        case minAmount = "min_amount"
        case maxAmount = "max_amount"
        case randomConfirmWindowSec = "random_confirm_window_sec"
        case randomConfirmFallbackSec = "random_confirm_fallback_sec"
        case confirmRetryDelaySec = "confirm_retry_delay_sec"
        case pollIntervalSec = "poll_interval_sec"
        case memoMode = "memo_mode"
        case memoKeywords = "memo_keywords"
        case blacklist
        case aesKeyHex = "aes_key_hex"
        case aesIVHex = "aes_iv_hex"
        case aesKeyHexAlt = "aes_key_hex_alt"
        case aesIVHexAlt = "aes_iv_hex_alt"
        case requestTimeoutSec = "request_timeout_sec"
        case verifyTLS = "verify_tls"
        case voiceEnabled = "voice_enabled"
        case seenTTLSec = "seen_ttl_sec"
    }

    /// Validate a decoded config, filling defaults for missing keys.
    /// Throws ConfigError on invalid values.
    public static func validated(from raw: [String: Any]) throws -> DPGConfig {
        var cfg = DPGConfig()
        func decode<T>(_ type: T.Type, _ key: String, _ defaultValue: T) -> T where T: Decodable {
            guard let value = raw[key] else { return defaultValue }
            if let direct = value as? T { return direct }
            // Numbers may arrive as a different numeric type (Int vs
            // Double) and booleans as NSNumber; coerce explicitly.
            // NOTE: JSONSerialization.data(withJSONObject:) must NOT be
            // used here: it only accepts NSArray/NSDictionary at the top
            // level, and swift-corelibs-foundation traps (fatalError)
            // on scalar values instead of throwing.
            switch type {
            case is Double.Type:
                if let number = value as? NSNumber { return number.doubleValue as! T }
            case is Int.Type:
                if let number = value as? NSNumber { return number.intValue as! T }
            case is Bool.Type:
                if let number = value as? NSNumber { return number.boolValue as! T }
            case is [String].Type:
                if let array = value as? [Any] { return array.compactMap { $0 as? String } as! T }
            default:
                break
            }
            return defaultValue
        }
        // 旧版明文凭据不自动恢复；载入时由 loadConfig 擦除，要求重新登录。
        cfg.shopID = decode(Int.self, "shop_id", 92)
        cfg.minAmount = decode(Double.self, "min_amount", 10.0)
        cfg.maxAmount = decode(Double.self, "max_amount", 0.0)
        cfg.randomConfirmWindowSec = decode(Double.self, "random_confirm_window_sec", 20)
        cfg.randomConfirmFallbackSec = decode(Double.self, "random_confirm_fallback_sec", 20)
        cfg.confirmRetryDelaySec = decode(Double.self, "confirm_retry_delay_sec", 0.5)
        cfg.pollIntervalSec = decode(Double.self, "poll_interval_sec", 0.6)
        cfg.autoStopOnSuccess = decode(Bool.self, "auto_stop_on_success", decode(Bool.self, "auto_stop_after_grab", true))
        cfg.onlineAfterLogin = decode(Bool.self, "online_after_login", false)
        cfg.useShopWait = decode(Bool.self, "use_shop_wait", true)
        cfg.globalWaitSec = decode(Double.self, "global_wait_sec", 3)
        if let value = raw["busy_cooldown_sec"] {
            guard let seconds = positiveItemID(value) else { throw ConfigError("满额冷却必须为正整数") }
            cfg.busyCooldownSec = seconds
        }
        if let value = raw["auto_wait_offset"] {
            guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else {
                throw ConfigError("等待微调必须是数值")
            }
            cfg.autoWaitOffset = number.doubleValue
        }
        if let value = raw["item_switch_ids"] {
            guard let values = value as? [Any] else { throw ConfigError("接单类型必须为正整数数组") }
            cfg.itemSwitchIds = try values.map { value in
                guard let id = positiveItemID(value) else { throw ConfigError("接单类型必须为正整数") }
                return id
            }
        }
        if let value = raw["item_switch_account_key"] {
            guard let key = value as? String else { throw ConfigError("接单类型账号作用域必须为字符串") }
            cfg.itemSwitchAccountKey = key
        }
        if let value = raw["item_switch_shop_id"] {
            guard let sid = value as? String else { throw ConfigError("接单类型店铺作用域必须为字符串") }
            cfg.itemSwitchShopId = sid
        }
        if let table = raw["confirm_wait_by_shop"] as? [String: Any] {
            var waits: [String: Double] = [:]
            for (sid, value) in table {
                guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else {
                    throw ConfigError("店铺等待必须是数值")
                }
                waits[sid] = number.doubleValue
            }
            cfg.confirmWaitByShop = waits
        }
        cfg.runRestEnabled = decode(Bool.self, "run_rest_enabled", false)
        cfg.runDurationSec = decode(Double.self, "run_duration_sec", 1800)
        cfg.restDurationSec = decode(Double.self, "rest_duration_sec", 300)
        cfg.memoMode = decode(Int.self, "memo_mode", 0)
        cfg.memoKeywords = decode([String].self, "memo_keywords", [])
        cfg.blacklist = decode([String].self, "blacklist", [])
        cfg.aesKeyHex = decode(String.self, "aes_key_hex", defaultAESKeyHex)
        cfg.aesIVHex = decode(String.self, "aes_iv_hex", defaultAESIVHex)
        cfg.aesKeyHexAlt = decode(String.self, "aes_key_hex_alt", altAESKeyHex)
        cfg.aesIVHexAlt = decode(String.self, "aes_iv_hex_alt", altAESIVHex)
        cfg.requestTimeoutSec = decode(Double.self, "request_timeout_sec", 15)
        cfg.verifyTLS = true
        cfg.voiceEnabled = decode(Bool.self, "voice_enabled", true)
        cfg.seenTTLSec = decode(Double.self, "seen_ttl_sec", 60)
        return try cfg.validated()
    }

    /// Validate this config in place (trims lists) and return it.
    public func validated() throws -> DPGConfig {
        var cfg = self
        cfg.verifyTLS = true
        guard cfg.globalWaitSec.isFinite, (0...120).contains(cfg.globalWaitSec),
              cfg.runDurationSec.isFinite, cfg.runDurationSec > 0, cfg.runDurationSec <= 86400,
              cfg.restDurationSec.isFinite, cfg.restDurationSec >= 0, cfg.restDurationSec <= 86400 else { throw ConfigError("等待与运行休息时间无效") }
        guard cfg.busyCooldownSec >= 1, cfg.busyCooldownSec <= 600 else {
            throw ConfigError("满额冷却必须为 1–600 秒")
        }
        let offsetScale = cfg.autoWaitOffset * 1000
        guard cfg.autoWaitOffset.isFinite, (-0.5...0.5).contains(cfg.autoWaitOffset),
              offsetScale.rounded() / 1000 == cfg.autoWaitOffset else {
            throw ConfigError("等待微调必须为 -0.5 至 0.5 秒，最多 3 位小数")
        }
        guard cfg.itemSwitchIds.allSatisfy({ $0 > 0 }) else {
            throw ConfigError("接单类型必须为正整数")
        }
        guard cfg.itemSwitchAccountKey.isEmpty || (cfg.itemSwitchAccountKey.count == 64 &&
              cfg.itemSwitchAccountKey.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) })) else {
            throw ConfigError("接单类型账号作用域必须为 SHA-256 小写十六进制摘要")
        }
        guard cfg.itemSwitchShopId.isEmpty || (positiveItemID(cfg.itemSwitchShopId) != nil &&
              cfg.itemSwitchShopId.utf8.allSatisfy({ (48...57).contains($0) })) else {
            throw ConfigError("接单类型店铺作用域必须为正整数字符串")
        }
        guard cfg.confirmWaitByShop.allSatisfy({
            (Int($0.key) ?? 0) > 0 && $0.value.isFinite && (0...120).contains($0.value)
        }) else { throw ConfigError("店铺等待必须为 0–120 秒，店铺 ID 必须为正整数") }
        for (name, hex) in [("aes_key_hex", cfg.aesKeyHex), ("aes_key_hex_alt", cfg.aesKeyHexAlt)] {
            let bytes = try hexDecode(hex)
            guard bytes.count == 16 || bytes.count == 32 else {
                throw ConfigError("\(name) must decode to 16 bytes (AES-128) or 32 bytes (AES-256)")
            }
        }
        for (name, hex) in [("aes_iv_hex", cfg.aesIVHex), ("aes_iv_hex_alt", cfg.aesIVHexAlt)] {
            let bytes = try hexDecode(hex)
            guard bytes.count == 16 else {
                throw ConfigError("\(name) must decode to 16 bytes")
            }
        }
        guard cfg.shopID > 0 else { throw ConfigError("shop_id must be a positive integer") }
        guard cfg.minAmount.isFinite, cfg.maxAmount.isFinite, cfg.minAmount >= 0, cfg.maxAmount >= 0 else {
            throw ConfigError("amounts must not be negative")
        }
        guard cfg.maxAmount == 0 || cfg.maxAmount >= cfg.minAmount else {
            throw ConfigError("max_amount must be 0 (unlimited) or >= min_amount")
        }
        guard cfg.randomConfirmWindowSec >= 0.1, cfg.randomConfirmWindowSec <= 86400 else {
            throw ConfigError("random_confirm_window_sec must be between 0.1 and 86400")
        }
        guard cfg.randomConfirmFallbackSec >= 0.1, cfg.randomConfirmFallbackSec <= 86400 else {
            throw ConfigError("random_confirm_fallback_sec must be between 0.1 and 86400")
        }
        guard cfg.confirmRetryDelaySec >= 0, cfg.confirmRetryDelaySec <= 60 else {
            throw ConfigError("confirm_retry_delay_sec must be between 0 and 60")
        }
        guard cfg.pollIntervalSec.isFinite, cfg.pollIntervalSec > 0, cfg.pollIntervalSec <= 60 else { throw ConfigError("poll_interval_sec must be between 0 and 60") }
        guard cfg.memoMode == 0 || cfg.memoMode == 1 else {
            throw ConfigError("memo_mode must be 0 (ignore memos) or 1 (drop memo orders)")
        }
        guard cfg.requestTimeoutSec.isFinite, cfg.requestTimeoutSec > 0, cfg.requestTimeoutSec <= 300 else { throw ConfigError("request_timeout_sec must be between 0 and 300") }
        guard cfg.seenTTLSec.isFinite, cfg.seenTTLSec > 0, cfg.seenTTLSec <= 86400 else { throw ConfigError("seen_ttl_sec must be between 0 and 86400") }
        cfg.memoKeywords = cfg.memoKeywords.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        cfg.blacklist = cfg.blacklist.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        cfg.phone = cfg.phone.trimmingCharacters(in: .whitespaces)
        return cfg
    }

    /// Encode to JSON data (sorted keys, pretty printed).
    public func jsonData() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(self)
    }

    /// Decode from JSON data (missing keys take defaults).
    public static func from(jsonData: Data) throws -> DPGConfig {
        guard let raw = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            throw ConfigError("config file must contain a JSON object")
        }
        return try validated(from: raw)
    }
}

/// Default config file location: Application Support/DPGrabber/config.json.
public func defaultConfigURL() -> URL? {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
        .appendingPathComponent("DPGrabber")
        .appendingPathComponent("config.json")
}

/// Load the config; create the default template (no credentials) when
/// the file does not exist.
public func loadConfig(from url: URL? = nil) throws -> DPGConfig {
    let url = url ?? defaultConfigURL()
    guard let url else { return DPGConfig() }
    if !FileManager.default.fileExists(atPath: url.path) {
        let cfg = DPGConfig()
        try saveConfig(cfg, to: url)
        return cfg
    }
    let data = try Data(contentsOf: url)
    let config = try DPGConfig.from(jsonData: data)
    if let raw = try JSONSerialization.jsonObject(with: data) as? [String: Any], raw["phone"] != nil || raw["password"] != nil {
        try saveConfig(config, to: url)
    }
    return config
}

/// Save the config (validated first), creating parent directories.
public func saveConfig(_ config: DPGConfig, to url: URL? = nil) throws {
    let url = url ?? defaultConfigURL()
    guard let url else { throw ConfigError("no config location available") }
    let cfg = try config.validated()
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try cfg.jsonData().write(to: url, options: [.atomic])
}

/// Mask a phone number for logs: keep first 3 and last 4 digits.
public func maskPhone(_ phone: String) -> String {
    if phone.count <= 7 { return "***" }
    return String(phone.prefix(3)) + "****" + String(phone.suffix(4))
}
