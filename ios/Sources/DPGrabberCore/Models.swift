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
    public let kind: Kind
    public let message: String

    public init(_ kind: Kind, _ message: String) {
        self.kind = kind
        self.message = message
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
