// DPGrabber iOS - Shared test stub transport (offline only).

import Foundation
@testable import DPGrabberCore

/// Scripted HTTP transport for tests. Records every request; answers
/// from `handler`. No real request is ever sent.
final class StubTransport: HTTPTransport, @unchecked Sendable {
    struct Recorded {
        let method: String
        let url: URL
        let headers: [String: String]
        let body: Data?
    }

    var recorded: [Recorded] = []
    var handler: ((String, URL, [String: String], Data?) throws -> (Int, Data))?

    func request(method: String, url: URL, headers: [String: String], body: Data?, timeout: TimeInterval) async throws -> (statusCode: Int, data: Data) {
        recorded.append(Recorded(method: method, url: url, headers: headers, body: body))
        guard let handler else {
            throw ApiError(.network, "no stub handler configured")
        }
        return try handler(method, url, headers, body)
    }
}

/// Build a JSON response body from a dictionary.
func jsonData(_ dict: [String: Any]) -> Data {
    // swiftlint:disable:next force_try
    try! JSONSerialization.data(withJSONObject: dict)
}

/// A plain (unencrypted) outer envelope: {"response": <inner>, ...}.
func plainEnvelope(_ inner: [String: Any], encrypted: Bool = false) -> Data {
    var outer: [String: Any] = ["response": inner]
    if encrypted { outer["isEncrypted"] = true }
    return jsonData(outer)
}

/// An encrypted outer envelope: {"response": <base64 ciphertext>,
/// "isEncrypted": true}.
func encryptedEnvelope(_ payloadB64: String) -> Data {
    jsonData(["response": payloadB64, "isEncrypted": true])
}

extension StubTransport {
    /// The last request whose URL path contains `substring`, if any.
    func lastRequest(containing substring: String) -> Recorded? {
        recorded.last { $0.url.absoluteString.contains(substring) }
    }
}
