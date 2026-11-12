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
public struct ApiError: Error, Equatable {
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
        if let n = cand["id"] as? NSNumber, n.intValue > 0 {
            let shopId = (cand["shopId"] as? NSNumber)?.intValue
            let name = cand["name"] as? String
            return DPGClerk(id: n.intValue, shopId: shopId, name: name)
        }
    }
    return nil
}

/// Log levels used by the engine and the UI log view.
public enum LogLevel: String {
    case debug, info, warning, error, success
}
