// DPGrabber iOS - Shared models (PROTOCOL.md §1-§3).
//
// Orders are carried as JSON dictionaries ([String: Any]) decoded with
// JSONSerialization, mirroring the PC/Android clients: the server's
// response shapes vary (list vs data.list, clerk in several places), so
// field access goes through the helpers in this file.

import Foundation

/// One failure of an API call.
///
/// kind is one of: `auth` (401/403, login state invalid), `ip` (code 1
/// with "IP" in msg, IP control), `api` (other business error or
/// malformed response), `network` (transport failure), `cancelled`
/// (caller asked to stop).
public struct ApiError: Error, Equatable, LocalizedError {
    public var errorDescription: String? { message }
    public enum Kind: String, Equatable {
        case auth, ip, api, network, cancelled
    }
    public enum Detail: String, Equatable {
        case business, permission, shopOffline, qualification
        case responseJSON, responseCode, responseShape, decryption, invalidRequest
        case http, connectivity, timeout, tls
    }
    public let kind: Kind
    public let message: String
    public let detail: Detail?
    public let endpoint: String?
    public let httpStatus: Int?
    public let businessCode: Int?
    public let networkCode: Int?

    public init(_ kind: Kind, _ message: String, detail: Detail? = nil,
                endpoint: String? = nil, httpStatus: Int? = nil,
                businessCode: Int? = nil, networkCode: Int? = nil) {
        self.kind = kind
        self.message = message
        self.detail = detail
        self.endpoint = endpoint
        self.httpStatus = httpStatus
        self.businessCode = businessCode
        self.networkCode = networkCode
    }

    public func at(endpoint: String, httpStatus: Int? = nil) -> ApiError {
        ApiError(kind, message, detail: detail, endpoint: self.endpoint ?? endpoint,
                 httpStatus: self.httpStatus ?? httpStatus, businessCode: businessCode, networkCode: networkCode)
    }

    /// Safe for the user interface: never includes headers, query strings, URLs, response bodies or free-form server messages.
    public var safeDiagnostic: String {
        let summary: String
        switch detail {
        case .permission: summary = "没有操作权限，请检查店铺店员授权"
        case .shopOffline: summary = "店铺未上线，请先上线再开始"
        case .qualification: summary = "平台接单条件未达标，请核验续费率等要求"
        case .business: summary = "平台拒绝请求，请核验账号和店铺状态"
        case .responseJSON: summary = "服务器返回的内容不是有效 JSON"
        case .responseCode: summary = "服务器响应缺少有效业务码"
        case .responseShape: summary = "服务器订单列表格式不符合预期"
        case .decryption: summary = "服务器响应解密失败，请核验协议设置"
        case .invalidRequest: summary = "请求参数无效"
        case .timeout: summary = "网络请求超时"
        case .tls: summary = "安全连接校验失败，未绕过证书校验"
        case .http: summary = "服务器 HTTP 请求失败"
        case .connectivity: summary = "网络连接失败，请检查网络"
        case nil:
            switch kind {
            case .auth: summary = "登录状态失效，请重新登录"
            case .ip: summary = "平台风控限制，已暂停请求"
            case .api: summary = "接口响应异常，请核验平台状态"
            case .network: summary = "网络连接失败，请检查网络"
            case .cancelled: summary = "请求已取消"
            }
        }
        var codes: [String] = []
        if let endpoint {
            let path = String(endpoint.split(separator: "?", maxSplits: 1).first ?? "")
            let labels = ["/s/c/order/randomList": "randomList", "/s/c/clerk/get": "clerk/get",
                          "/s/c/order/get": "order/get", "/s/c/order/confirm": "order/confirm",
                          "/s/duopei-account/login": "login"]
            codes.append(labels[path] ?? "API")
        }
        if let httpStatus { codes.append("HTTP \(httpStatus)") }
        if let businessCode { codes.append("code \(businessCode)") }
        if let networkCode { codes.append("NSURLError \(networkCode)") }
        if let detail { codes.append(detail.rawValue) }
        return codes.isEmpty ? summary : summary + " [" + codes.joined(separator: " · ") + "]"
    }

    public static func transport(_ error: Error) -> ApiError {
        if error is CancellationError || (error as? URLError)?.code == .cancelled {
            return ApiError(.cancelled, "请求已取消")
        }
        let code = (error as? URLError)?.code
        let detail: Detail
        switch code {
        case .timedOut: detail = .timeout
        case .secureConnectionFailed, .serverCertificateHasBadDate, .serverCertificateUntrusted,
             .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
             .clientCertificateRejected, .clientCertificateRequired: detail = .tls
        default: detail = .connectivity
        }
        return ApiError(.network, "网络请求失败", detail: detail, networkCode: code?.rawValue)
    }
}

/// Match the first nonempty message field, as the PC client does; an empty msg must not hide message.
public func responseMessage(_ response: [String: Any]) -> String {
    for key in ["msg", "message"] {
        if let value = response[key] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return value }
    }
    return ""
}

public struct PollStatus: Equatable {
    public let issue: String?
    public let retryAt: Date?
    public let lastSuccessAt: Date?
    public init(issue: String? = nil, retryAt: Date? = nil, lastSuccessAt: Date? = nil) {
        self.issue = issue
        self.retryAt = retryAt
        self.lastSuccessAt = lastSuccessAt
    }
}

/// A logged-in session: token + shop id. Memory only.
public struct DPGSession: Equatable {
    public let token: String
    public let sid: String

    public init(token: String, sid: String = "50") {
        self.token = token
        self.sid = sid
    }
}

/// Clerk (store staff) info from `GET /s/c/clerk/get`.
public struct DPGClerk: Equatable {
    public let id: Int
    public let shopId: Int?
    public let name: String?

    public init(id: Int, shopId: Int?, name: String?) {
        self.id = id
        self.shopId = shopId
        self.name = name
    }
}

/// The id of an order dictionary (string or number), or "" when missing.
public func orderID(of order: Any?) -> String {
    guard let dict = order as? [String: Any] else { return "" }
    guard let value = dict["id"], !(value is NSNull) else { return "" }
    if let s = value as? String { return s }
    if let n = value as? NSNumber { return n.stringValue }
    return ""
}

/// Find the clerk object in a clerk/get response: try `response.clerk`,
/// `response.data` and the top level, in that order; take the first dict
/// with a positive integer `id` (PROTOCOL.md §2.1.6).
public func extractClerk(_ decoded: [String: Any]) -> DPGClerk? {
    var candidates = [[String: Any]]()
    for key in ["clerk", "data"] {
        if let value = decoded[key] as? [String: Any] {
            candidates.append(value)
        }
    }
    candidates.append(decoded)
    for cand in candidates {
        let id = parseCode(["code": cand["id"] ?? NSNull()]) ?? 0
        if id > 0 {
            let shopId = parseCode(["code": cand["shopId"] ?? NSNull()])
            let name = cand["name"] as? String
            return DPGClerk(id: id, shopId: shopId, name: name)
        }
    }
    return nil
}

public enum OrderOutcome: String, Codable, Equatable, Hashable {
    case success, failed, risk, unknown, skipped
}

public struct OrderHistoryRecord: Codable, Equatable, Identifiable {
    public var id: String
    public var accountID: String
    public var shopID: String
    public var orderID: String
    public var runID: String
    public var attemptID: String
    public var timestamp: Date
    public var amount: Double
    public var wechatID: String
    public var memo: String
    public var outcome: OrderOutcome
    public var reason: String
}

public final class OrderHistoryStore: @unchecked Sendable {
    private let lock = NSLock()
    private let url: URL?
    private var records: [OrderHistoryRecord]

    public init(url: URL? = nil) throws {
        self.url = url
        if let url, FileManager.default.fileExists(atPath: url.path) {
            records = try JSONDecoder().decode([OrderHistoryRecord].self, from: Data(contentsOf: url))
        } else { records = [] }
    }

    private init(memoryRecords: [OrderHistoryRecord]) {
        url = nil
        records = memoryRecords
    }

    public static func inMemory() -> OrderHistoryStore { OrderHistoryStore(memoryRecords: []) }

    public var all: [OrderHistoryRecord] { lock.withLock { records } }

    public func entries(accountID: String, shopID: String? = nil) -> [OrderHistoryRecord] {
        all.filter { $0.accountID == accountID && (shopID == nil || $0.shopID == shopID) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    public func put(_ record: OrderHistoryRecord) throws {
        try lock.withLock {
            var updated = records
            if let index = updated.firstIndex(where: { $0.id == record.id }) { updated[index] = record }
            else { updated.append(record) }
            if let url {
                try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try JSONEncoder().encode(updated).write(to: url, options: [.atomic])
                #if os(iOS)
                try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
                #endif
            }
            records = updated
        }
    }

    public func blocksConfirmation(accountID: String, shopID: String, orderID: String) -> Bool {
        all.contains { $0.accountID == accountID && $0.shopID == shopID && $0.orderID == orderID }
    }

    public func today(now: Date = Date(), accountID: String? = nil, shopID: String? = nil) -> [OrderHistoryRecord] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai") ?? TimeZone(secondsFromGMT: 8 * 3600)!
        return all.filter {
            calendar.isDate($0.timestamp, inSameDayAs: now) &&
            (accountID == nil || $0.accountID == accountID) && (shopID == nil || $0.shopID == shopID)
        }
    }

    public func todayCounts(accountID: String, shopID: String? = nil, now: Date = Date()) -> [OrderOutcome: Int] {
        var latest: [String: OrderHistoryRecord] = [:]
        for row in today(now: now, accountID: accountID, shopID: shopID).sorted(by: { $0.timestamp < $1.timestamp }) {
            latest["\(row.shopID):\(row.orderID)"] = row
        }
        return Dictionary(grouping: latest.values, by: { $0.outcome }).mapValues { $0.count }
    }
}

public struct WeeklyStatistics: Equatable {
    public let orderCount: Int?
    public let feeRate: Double?
    public let orderRate: Double?
    public let continuedUserCount: Int?
    public let isCountFallback: Bool

    public init(raw: [String: Any], fallbackCount: Int? = nil) {
        func number(_ key: String) -> Double? {
            guard let value = raw[key], !(value is NSNull) else { return nil }
            if let n = value as? NSNumber {
                guard String(cString: n.objCType) != "c", n.doubleValue.isFinite, n.doubleValue >= 0 else { return nil }
                return n.doubleValue
            }
            if let text = value as? String, let n = Double(text), n.isFinite, n >= 0 { return n }
            return nil
        }
        func count(_ key: String) -> Int? {
            guard let n = number(key), n.rounded() == n, n < Double(Int.max) else { return nil }
            return Int(n)
        }
        func ratio(_ numerator: String, _ denominator: String) -> Double? {
            guard let a = number(numerator), let b = number(denominator), b > 0 else { return nil }
            let value = a / b
            return value.isFinite ? value : nil
        }
        let countValue = count("orderCount")
        orderCount = countValue ?? fallbackCount.flatMap { $0 >= 0 ? $0 : nil }
        feeRate = ratio("continuedAmount", "orderAmount")
        orderRate = ratio("continuedCount", "orderCount")
        continuedUserCount = count("continuedUserCount")
        isCountFallback = countValue == nil && fallbackCount != nil
    }
}

public struct PlatformWeek {
    public let startDate: String
    public let endDate: String
    public var startDateTime: String { startDate + " 00:00:00" }
    public var endDateTime: String { endDate + " 23:59:59" }

    public init(now: Date = Date()) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai") ?? TimeZone(secondsFromGMT: 8 * 3600)!
        let today = calendar.startOfDay(for: now)
        let daysSinceMonday = (calendar.component(.weekday, from: today) + 5) % 7
        let monday = calendar.date(byAdding: .day, value: -daysSinceMonday, to: today) ?? today
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        startDate = formatter.string(from: monday)
        endDate = formatter.string(from: today)
    }
}

public struct ItemSwitchOption: Identifiable, Equatable {
    public let id: Int
    public let name: String
    public init(id: Int, name: String) { self.id = id; self.name = name }
}

/// Log levels used by the engine and the UI log view.
public enum LogLevel: String {
    case debug, info, warning, error, success
}
