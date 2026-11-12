// DPGrabber iOS - DuoPei API client (PROTOCOL.md §1-§5).
//
// * Base URL `https://api.duopei.feiniaowangluo.com`; only `/s/` paths are
//   allowed and the host is pinned (DPGAPIClient.m:225-233).
// * Fixed headers: DPGrabber UA, `api-version: 202501071`, `platform: app`.
// * Bodies: JSON or form (keys sorted, RFC 3986 percent-encoding).
// * Response envelope (§4.2): outer JSON `response` field; when
//   `isEncrypted` is set, or when the response string does not parse as
//   plain JSON, the payload is Base64-decoded and AES-CBC/PKCS#7-decrypted.
//   The primary AES pair is tried first, then the alternate pair
//   ("decrypt with whichever works", §10.6).
// * Login flow (§3.2): phone + password -> token (sid "50") ->
//   list-shop-account -> switch-account per shop -> getBalance validation.
//   Token is kept in memory only, never written to disk.
//
// The transport is injectable: pass any ``HTTPTransport``; the unit tests
// use a stub so no real request is ever sent from a test.

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public let dpBaseURL = "https://api.duopei.feiniaowangluo.com"
public let dpAPIHost = "api.duopei.feiniaowangluo.com"

public let dpUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 15_1_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Html5Plus/1.0 (Immersed/20) uni-app"
public let dpAPIVersion = "202501071"

/// Characters left unescaped in form bodies (RFC 3986 unreserved set).
private let formSafe = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

/// One HTTP round trip. Implementations return the status code and body.
public protocol HTTPTransport: Sendable {
    func request(method: String, url: URL, headers: [String: String], body: Data?, timeout: TimeInterval) async throws -> (statusCode: Int, data: Data)
}

/// URLSession-backed transport (production).
public final class URLSessionTransport: HTTPTransport, @unchecked Sendable {
    private let verifyTLS: Bool

    public init(verifyTLS: Bool = true) {
        self.verifyTLS = verifyTLS
    }

    public func request(method: String, url: URL, headers: [String: String], body: Data?, timeout: TimeInterval) async throws -> (statusCode: Int, data: Data) {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = max(0.1, timeout)
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        request.httpBody = body
        let session: URLSession
        #if canImport(Security)
        if verifyTLS {
            session = URLSession(configuration: .ephemeral)
        } else {
            // Explicitly disabled by the user (config verify_tls=false):
            // trust any server certificate. The UI warns about this.
            let delegate = TrustAllDelegate()
            session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        }
        #else
        // Linux (swift-corelibs-foundation): no Security framework, so
        // certificate trust cannot be overridden; always verify.
        _ = verifyTLS
        session = URLSession(configuration: .ephemeral)
        #endif
        do {
            let (data, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 200
            return (status, data)
        } catch {
            throw ApiError(.network, "request failed: \(error.localizedDescription)")
        }
    }

    #if canImport(Security)
    /// Trust-all delegate, only used when the user explicitly disabled
    /// TLS verification (config verify_tls=false). Apple platforms only.
    private final class TrustAllDelegate: NSObject, URLSessionDelegate {
        func urlSession(
            _ session: URLSession,
            didReceive challenge: URLAuthenticationChallenge,
            completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
        ) {
            if let trust = challenge.protectionSpace.serverTrust {
                completionHandler(.useCredential, URLCredential(trust: trust))
            } else {
                completionHandler(.performDefaultHandling, nil)
            }
        }
    }
    #endif
}

/// The API client. The live session lives in `session`.
public final class ApiClient: @unchecked Sendable {
    public let aesKeyHex: String
    public let aesIVHex: String
    public let aesKeyHexAlt: String
    public let aesIVHexAlt: String
    public let verifyTLS: Bool
    private let transport: HTTPTransport
    private let lock = NSLock()
    private var _session: DPGSession?

    public var session: DPGSession? {
        get { lock.withLock { _session } }
        set { lock.withLock { _session = newValue } }
    }

    public init(
        aesKeyHex: String,
        aesIVHex: String,
        aesKeyHexAlt: String = "",
        aesIVHexAlt: String = "",
        verifyTLS: Bool = true,
        transport: HTTPTransport? = nil
    ) {
        self.aesKeyHex = aesKeyHex
        self.aesIVHex = aesIVHex
        self.aesKeyHexAlt = aesKeyHexAlt
        self.aesIVHexAlt = aesIVHexAlt
        self.verifyTLS = verifyTLS
        self.transport = transport ?? URLSessionTransport(verifyTLS: verifyTLS)
    }

    // MARK: - Decryption

    private func decryptResponseText(_ payload: String) throws -> [String: Any] {
        var attempts = [(aesKeyHex, aesIVHex)]
        if !aesKeyHexAlt.isEmpty, !aesIVHexAlt.isEmpty {
            attempts.append((aesKeyHexAlt, aesIVHexAlt))
        }
        var lastError: Error?
        for (keyHex, ivHex) in attempts {
            do {
                let plain = try aesDecryptB64(payload, keyHex: keyHex, ivHex: ivHex)
                guard let inner = try JSONSerialization.jsonObject(with: plain) as? [String: Any] else {
                    throw ApiError(.api, "decrypted response is not a JSON object")
                }
                return inner
            } catch {
                lastError = error
                continue
            }
        }
        throw ApiError(.api, "response decryption failed: \(String(describing: lastError))")
    }

    /// Decode one HTTP response body per PROTOCOL.md §4.2, with one
    /// documented merge: when the `response` string does not parse as
    /// plain JSON, AES decryption is attempted as well (the PC tool
    /// decrypts unconditionally; DPGrabber only decrypts when
    /// `isEncrypted` is set). Returns the inner dict and enforces the
    /// `code` rules (missing code -> error, 401/403 -> auth, code 1 +
    /// "IP" in msg -> IP control).
    public func decodeResponse(_ raw: Data) throws -> [String: Any] {
        guard let outer = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] else {
            throw ApiError(.api, "response is not valid JSON")
        }
        let envelope = outer["response"]
        let encrypted = (outer["isEncrypted"] as? NSNumber)?.boolValue ?? false
        var inner: [String: Any]?
        if let dict = envelope as? [String: Any] {
            inner = dict
        } else if let text = envelope as? String, !text.isEmpty {
            if encrypted {
                inner = try decryptResponseText(text)
            } else if let parsed = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] {
                inner = parsed
            } else {
                // Plain parse failed: fall back to AES decryption.
                inner = try decryptResponseText(text)
            }
        }
        let resolved = inner ?? outer
        guard let code = parseCode(resolved) else {
            throw ApiError(.api, "response has no valid business code")
        }
        if code == 401 || code == 403 {
            throw ApiError(.auth, "login state invalid (code \(code))")
        }
        let msg = (resolved["msg"] as? String) ?? (resolved["message"] as? String) ?? ""
        if code == 1, msg.contains("IP") {
            throw ApiError(.ip, "IP control: \(msg)")
        }
        return resolved
    }

    // MARK: - Transport

    private func formEncode(_ body: [String: String]) -> Data {
        let pairs = body.keys.sorted().map { key -> String in
            let encodedKey = key.addingPercentEncoding(withAllowedCharacters: formSafe) ?? key
            let encodedValue = (body[key] ?? "").addingPercentEncoding(withAllowedCharacters: formSafe) ?? ""
            return "\(encodedKey)=\(encodedValue)"
        }
        return Data(pairs.joined(separator: "&").utf8)
    }

    public enum BodyType {
        case none, json, form
    }

    /// One API call. Raises ApiError on any failure.
    public func request(
        method: String,
        path: String,
        session: DPGSession? = nil,
        body: Any? = nil,
        bodyType: BodyType = .none,
        extraHeaders: [String: String] = [:],
        timeout: TimeInterval = 15
    ) async throws -> [String: Any] {
        guard path.hasPrefix("/s/"), timeout > 0 else {
            throw ApiError(.api, "invalid request path or timeout")
        }
        guard let url = URL(string: dpBaseURL + path) else {
            throw ApiError(.api, "invalid request URL")
        }
        var headers: [String: String] = [
            "User-Agent": dpUserAgent,
            "api-version": dpAPIVersion,
            "platform": "app",
            "Accept": "*/*",
            "Accept-Language": "zh-CN,zh-Hans;q=0.9",
        ]
        let useSession = session ?? self.session
        if let useSession, !useSession.token.isEmpty {
            headers["authorization-token"] = useSession.token
            headers["sid"] = useSession.sid
        }
        for (key, value) in extraHeaders {
            headers[key] = value
        }

        var data: Data?
        switch bodyType {
        case .json:
            if let body {
                data = try JSONSerialization.data(withJSONObject: body)
                headers["Content-Type"] = "application/json"
            }
        case .form:
            if let body = body as? [String: String] {
                data = formEncode(body)
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            }
        case .none:
            break
        }

        let (status, raw): (Int, Data)
        do {
            (status, raw) = try await transport.request(
                method: method, url: url, headers: headers, body: data, timeout: max(0.1, timeout)
            )
        } catch let error as ApiError {
            throw error
        } catch {
            throw ApiError(.network, "request failed: \(error.localizedDescription)")
        }
        if status == 401 || status == 403 {
            throw ApiError(.auth, "login state invalid (HTTP \(status))")
        }
        if status >= 400 {
            throw ApiError(.network, "HTTP \(status)")
        }
        return try decodeResponse(raw)
    }

    // MARK: - Login flow (PROTOCOL.md §3.2)

    /// POST /s/duopei-account/login. Returns (token, sid "50").
    public func loginAccount(phone: String, password: String) async throws -> DPGSession {
        let phone = phone.trimmingCharacters(in: .whitespaces)
        let secret = password.trimmingCharacters(in: .whitespaces)
        guard !phone.isEmpty, !secret.isEmpty else {
            throw ApiError(.api, "phone and password are required")
        }
        let body: [String: Any] = [
            "method": "password",
            "phoneNumber": ["regionCode": "CN", "mobileDial": "86", "mobile": phone],
            "password": secret,
        ]
        let resp = try await request(
            method: "POST", path: "/s/duopei-account/login", session: nil,
            body: body, bodyType: .json,
            extraHeaders: ["sid": "-1", "authorization-token": ""],
            timeout: 15
        )
        guard parseCode(resp) == 0 else {
            throw ApiError(.auth, "login rejected: \((resp["msg"] as? String) ?? (resp["message"] as? String) ?? "")")
        }
        guard let data = resp["data"] as? [String: Any],
              let token = data["token"] as? String, !token.isEmpty else {
            throw ApiError(.auth, "login response has no token")
        }
        return DPGSession(token: token, sid: "50")
    }

    /// Full manual login: login -> list shops -> switch per shop ->
    /// getBalance validation. Returns a usable session or throws ApiError.
    ///
    /// Auth / IP / cancelled failures abort immediately; other per-shop
    /// failures move on to the next shop (DPGAPIClient.m:298-361).
    public func sessionForAccount(phone: String, password: String, preferredShopID: Int = 92) async throws -> DPGSession {
        let base = try await loginAccount(phone: phone, password: password)
        let resp = try await request(
            method: "GET", path: "/s/duopei-account/list-shop-account", session: base,
            extraHeaders: ["sid": "50"], timeout: 15
        )
        guard parseCode(resp) == 0 else {
            throw ApiError(.api, "shop list rejected")
        }
        let shops = (resp["data"] as? [[String: Any]]) ?? []
        let shopID: ([String: Any]) -> Int = { ($0["shopId"] as? NSNumber)?.intValue ?? -1 }
        var ordered = shops.filter { shopID($0) == preferredShopID }
        ordered += shops.filter { shopID($0) != preferredShopID }
        var lastError: ApiError?
        for shop in ordered {
            guard let accountID = shop["accountId"].map({ "\($0)" }), !accountID.isEmpty,
                  let sid = shop["shopId"].map({ "\($0)" }), !sid.isEmpty else {
                continue
            }
            let switched: [String: Any]
            do {
                switched = try await request(
                    method: "POST", path: "/s/duopei-account/switch-account", session: base,
                    body: ["accountId": accountID], bodyType: .form,
                    extraHeaders: ["sid": "50"], timeout: 15
                )
            } catch let error as ApiError {
                if error.kind == .auth || error.kind == .ip || error.kind == .cancelled {
                    throw error
                }
                lastError = error
                continue
            }
            guard parseCode(switched) == 0 else { continue }
            guard let data = switched["data"] as? [String: Any],
                  let token = data["token"] as? String, !token.isEmpty else { continue }
            let candidate = DPGSession(token: token, sid: sid)
            do {
                let balance = try await request(
                    method: "GET", path: "/s/balance/getBalance", session: candidate, timeout: 15
                )
                if parseCode(balance) == 0 {
                    self.session = candidate
                    return candidate
                }
            } catch let error as ApiError {
                if error.kind == .ip || error.kind == .cancelled {
                    throw error
                }
                lastError = error
                continue
            }
        }
        throw ApiError(.auth, "no usable shop session: \(lastError?.message ?? "all shops rejected")")
    }

    // MARK: - Business calls

    /// GET /s/c/clerk/get. Returns the raw decoded dict; callers use
    /// extractClerk() to find the clerk object.
    public func clerkGet(session: DPGSession? = nil) async throws -> [String: Any] {
        try await request(method: "GET", path: "/s/c/clerk/get", session: session, timeout: 15)
    }

    /// GET /s/c/order/randomList. Returns the order list (possibly empty).
    /// A missing list is treated as empty, not fatal, so polling continues.
    public func randomList(pageSize: Int = 20, timeout: TimeInterval = 15) async throws -> [[String: Any]] {
        let resp = try await request(
            method: "GET",
            path: "/s/c/order/randomList?pageNo=1&pageSize=\(pageSize)",
            timeout: timeout
        )
        if let list = resp["list"] as? [[String: Any]] { return list }
        if let data = resp["data"] as? [String: Any],
           let list = data["list"] as? [[String: Any]] { return list }
        return []
    }

    /// POST /s/c/order/get?id=... (PC tool form, used for the precise
    /// grab time). Returns the decoded dict; the order object is in `order`.
    public func orderGet(orderID: String, timeout: TimeInterval = 10) async throws -> [String: Any] {
        let encoded = orderID.addingPercentEncoding(withAllowedCharacters: formSafe) ?? orderID
        return try await request(method: "POST", path: "/s/c/order/get?id=\(encoded)", timeout: timeout)
    }

    /// POST /s/c/order/confirm?id=... . Returns the decoded dict.
    public func orderConfirm(orderID: String, timeout: TimeInterval = 10) async throws -> [String: Any] {
        let encoded = orderID.addingPercentEncoding(withAllowedCharacters: formSafe) ?? orderID
        return try await request(method: "POST", path: "/s/c/order/confirm?id=\(encoded)", timeout: timeout)
    }
}
