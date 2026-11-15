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
// * Login flow (§3.2): phone + password -> token + 实际 shopId ->
//   list-shop-account -> switch-account per shop -> getBalance validation.
//   Token is kept in memory only, never written to disk.
//
// The transport is injectable: pass any ``HTTPTransport``; the unit tests
// use a stub so no real request is ever sent from a test.

import Foundation
import CoreFoundation
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
    private let session: URLSession

    public init(verifyTLS: Bool = true, configuration: URLSessionConfiguration = .ephemeral) {
        // 正式客户端始终校验证书；旧配置不能绕过系统信任链。
        self.session = URLSession(configuration: configuration)
    }

    deinit { session.invalidateAndCancel() }

    public func request(method: String, url: URL, headers: [String: String], body: Data?, timeout: TimeInterval) async throws -> (statusCode: Int, data: Data) {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = max(0.1, timeout)
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        request.httpBody = body
        try Task.checkCancellation()
        do {
            let (data, response) = try await session.data(for: request)
            try Task.checkCancellation()
            let status = (response as? HTTPURLResponse)?.statusCode ?? 200
            return (status, data)
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled || Task.isCancelled {
                throw ApiError(.cancelled, "请求已取消")
            }
            throw ApiError.transport(error)
        }
    }
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
        self.verifyTLS = true
        self.transport = transport ?? URLSessionTransport(verifyTLS: verifyTLS)
    }

    // MARK: - Decryption

    private func decryptResponseText(_ payload: String) throws -> [String: Any] {
        var attempts = [(aesKeyHex, aesIVHex)]
        if !aesKeyHexAlt.isEmpty, !aesIVHexAlt.isEmpty {
            attempts.append((aesKeyHexAlt, aesIVHexAlt))
        }
        for (keyHex, ivHex) in attempts {
            do {
                let plain = try aesDecryptB64(payload, keyHex: keyHex, ivHex: ivHex)
                guard let inner = try JSONSerialization.jsonObject(with: plain) as? [String: Any] else {
                    throw ApiError(.api, "decrypted response is not a JSON object")
                }
                return inner
            } catch {
                continue
            }
        }
        throw ApiError(.api, "服务器响应解密失败", detail: .decryption)
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
            throw ApiError(.api, "服务器返回的内容不是有效 JSON", detail: .responseJSON)
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
            throw ApiError(.api, "服务器响应缺少有效业务码", detail: .responseCode)
        }
        if code == 401 || code == 403 {
            throw ApiError(.auth, "登录状态失效", businessCode: code)
        }
        let msg = responseMessage(resolved)
        if code == 1, msg.contains("IP") {
            throw ApiError(.ip, "平台风控限制", businessCode: code)
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
            throw ApiError(.api, "请求路径或超时设置无效", detail: .invalidRequest)
        }
        guard let url = URL(string: dpBaseURL + path) else {
            throw ApiError(.api, "请求URL无效", detail: .invalidRequest)
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

        guard !Task.isCancelled else { throw ApiError(.cancelled, "请求已取消") }
        let (status, raw): (Int, Data)
        do {
            (status, raw) = try await transport.request(
                method: method, url: url, headers: headers, body: data, timeout: max(0.1, timeout)
            )
        } catch let error as ApiError {
            throw error.at(endpoint: url.path)
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled || Task.isCancelled {
                throw ApiError(.cancelled, "请求已取消", endpoint: url.path)
            }
            throw ApiError.transport(error).at(endpoint: url.path)
        }
        guard !Task.isCancelled else { throw ApiError(.cancelled, "请求已取消", endpoint: url.path) }
        if status == 401 || status == 403 {
            throw ApiError(.auth, "登录状态失效", endpoint: url.path, httpStatus: status)
        }
        if status >= 400 {
            throw ApiError(.network, "HTTP请求失败", detail: .http, endpoint: url.path, httpStatus: status)
        }
        do { return try decodeResponse(raw) }
        catch let error as ApiError { throw error.at(endpoint: url.path, httpStatus: status) }
    }

    // MARK: - Login flow (PROTOCOL.md §3.2)

    /// 登录响应必须提供有效 shopId，不猜测默认店铺。
    private func positiveShopID(_ value: Any?) -> Int? {
        if let string = value as? String {
            guard let id = Int(string), id > 0 else { return nil }
            return id
        }
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let id = number.doubleValue
        guard id.isFinite, id > 0, id.rounded() == id, id < Double(Int.max) else { return nil }
        return Int(id)
    }
    public func loginAccount(phone: String, password: String) async throws -> DPGSession {
        let phone = phone.trimmingCharacters(in: .whitespaces)
        let secret = password.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !phone.isEmpty, !secret.isEmpty else {
            throw ApiError(.api, "phone and password are required")
        }
        let body: [String: Any] = [
            "method": "password",
            "phoneNumber": ["regionCode": "CN", "mobileDial": "86", "mobile": phone],
            "password": loginPasswordDigest(secret),
        ]
        let resp = try await request(
            method: "POST", path: "/s/duopei-account/login", session: nil,
            body: body, bodyType: .json,
            extraHeaders: ["sid": "-1", "authorization-token": ""],
            timeout: 15
        )
        guard parseCode(resp) == 0 else {
            throw ApiError(.auth, "登录被平台拒绝", endpoint: "/s/duopei-account/login", businessCode: parseCode(resp))
        }
        guard let data = resp["data"] as? [String: Any],
              let token = data["token"] as? String, !token.isEmpty else {
            throw ApiError(.auth, "login response has no token")
        }
        guard let sid = positiveShopID(data["shopId"]) else {
            throw ApiError(.auth, "登录响应缺少有效 shopId")
        }
        return DPGSession(token: token, sid: String(sid))
    }

    /// Full manual login: login -> list shops -> switch per shop ->
    /// getBalance validation. Returns a usable session or throws ApiError.
    ///
    /// 基础令牌 AUTH、IP 与取消立即终止；候选店铺 AUTH/业务拒绝尝试下一店铺。
    public func sessionForAccount(phone: String, password: String, preferredShopID: Int = 92) async throws -> DPGSession {
        let base = try await loginAccount(phone: phone, password: password)
        let resp = try await request(
            method: "GET", path: "/s/duopei-account/list-shop-account", session: base,
            extraHeaders: ["sid": base.sid], timeout: 15
        )
        guard parseCode(resp) == 0 else {
            throw ApiError(.api, "shop list rejected")
        }
        let shops = (resp["data"] as? [[String: Any]]) ?? []
        let shopID: ([String: Any]) -> Int = { self.positiveShopID($0["shopId"]) ?? -1 }
        var ordered = shops.filter { shopID($0) == preferredShopID }
        ordered += shops.filter { shopID($0) != preferredShopID }
        var lastError: ApiError?
        for shop in ordered {
            guard let accountID = shop["accountId"].map({ "\($0)" }), !accountID.isEmpty,
                  let numericSID = positiveShopID(shop["shopId"]) else {
                continue
            }
            let sid = String(numericSID)
            let switched: [String: Any]
            do {
                switched = try await request(
                    method: "POST", path: "/s/duopei-account/switch-account", session: base,
                    body: ["accountId": accountID], bodyType: .form,
                    extraHeaders: ["sid": base.sid], timeout: 15
                )
            } catch let error as ApiError {
                // 切换使用基础令牌，其 AUTH 失效不能误当候选店铺权限失败。
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
                guard parseCode(balance) == 0 else { throw ApiError(.api, "候选店铺余额权限被拒绝") }
                let clerkResponse = try await clerkGet(session: candidate)
                guard let clerk = extractClerk(clerkResponse), clerk.shopId == nil || String(clerk.shopId ?? 0) == sid else {
                    throw ApiError(.api, "候选店铺无可用店员权限")
                }
                guard !Task.isCancelled else { throw ApiError(.cancelled, "登录已取消") }
                self.session = candidate
                return candidate
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

    private func requireSuccess(_ response: [String: Any], endpoint: String? = nil) throws -> [String: Any] {
        guard parseCode(response) == 0 else {
            let text = responseMessage(response)
            let detail: ApiError.Detail
            let message: String
            if ["权限", "非店员", "不是店员", "无店员"].contains(where: { text.contains($0) }) {
                detail = .permission; message = "没有操作权限，请检查店铺店员授权"
            } else if ["未上线", "未在线", "已下线", "不在线", "离线"].contains(where: { text.contains($0) }) {
                detail = .shopOffline; message = "店铺未上线，请先上线"
            } else if ["续费率", "达标", "资质", "资格", "上限", "额度"].contains(where: { text.contains($0) }) {
                detail = .qualification; message = "平台接单条件未达标"
            } else {
                detail = .business; message = "平台拒绝业务操作"
            }
            throw ApiError(.api, message, detail: detail, endpoint: endpoint, businessCode: parseCode(response))
        }
        return response
    }

    private func dateRangeQuery(startTime: String, endTime: String, includesTime: Bool) throws -> String {
        let pattern = includesTime ? "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$" : "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
        formatter.dateFormat = includesTime ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd"
        formatter.isLenient = false
        guard startTime.range(of: pattern, options: .regularExpression) != nil,
              endTime.range(of: pattern, options: .regularExpression) != nil,
              let start = formatter.date(from: startTime), formatter.string(from: start) == startTime,
              let end = formatter.date(from: endTime), formatter.string(from: end) == endTime,
              start <= end else {
            throw ApiError(.api, includesTime ? "日期范围必须为有效 yyyy-MM-dd HH:mm:ss" : "日期范围必须为有效 yyyy-MM-dd")
        }
        let encodedStart = startTime.addingPercentEncoding(withAllowedCharacters: formSafe)!
        let encodedEnd = endTime.addingPercentEncoding(withAllowedCharacters: formSafe)!
        return "startTime=\(encodedStart)&endTime=\(encodedEnd)"
    }

    @discardableResult
    public func updateStatus(online: Bool, updateAutoOnline: Bool = false) async throws -> [String: Any] {
        try requireSuccess(await request(method: "POST", path: "/s/c/clerk/updateStatus",
            body: ["status": online ? "1" : "2", "updateAutoOnline": updateAutoOnline ? "true" : "false"], bodyType: .form))
    }

    @discardableResult
    public func updateRandomOrderSwitch(enabled: Bool) async throws -> [String: Any] {
        try requireSuccess(await request(method: "POST", path: "/s/c/clerk/updateRandomOrderSwitch",
            body: ["randomOrderSwitch": enabled ? "true" : "false"], bodyType: .form))
    }

    public func getItemSwitchList() async throws -> [String: Any] {
        try requireSuccess(await request(method: "POST", path: "/s/c/clerk/getItemSwitchList",
            body: [String: String](), bodyType: .form))
    }

    @discardableResult
    public func updateItemSwitch(switchID: Int, enabledIDs: [Int]) async throws -> [String: Any] {
        let encoded = try JSONSerialization.data(withJSONObject: enabledIDs)
        let ids = String(decoding: encoded, as: UTF8.self)
        return try requireSuccess(await request(method: "POST", path: "/s/c/clerk/updateItemSwitch",
            body: ["switchId": String(switchID), "switchIds": ids], bodyType: .form))
    }

    public func statisticsClerkOrder(startTime: String, endTime: String) async throws -> [String: Any] {
        let query = try dateRangeQuery(startTime: startTime, endTime: endTime, includesTime: false)
        return try requireSuccess(await request(method: "GET", path: "/s/c/statistics/statisticsClerkOrder?\(query)"))
    }

    public func orderList(startTime: String, endTime: String, pageNo: Int = 1, pageSize: Int = 1) async throws -> [String: Any] {
        guard pageNo > 0, pageSize > 0 else { throw ApiError(.api, "分页参数必须为正整数") }
        let query = try dateRangeQuery(startTime: startTime, endTime: endTime, includesTime: true)
        return try requireSuccess(await request(method: "GET", path: "/s/c/order/list?pageNo=\(pageNo)&pageSize=\(pageSize)&\(query)"))
    }

    // MARK: - Business calls

    /// GET /s/c/clerk/get. Returns the raw decoded dict; callers use
    /// extractClerk() to find the clerk object.
    public func clerkGet(session: DPGSession? = nil) async throws -> [String: Any] {
        try requireSuccess(await request(method: "GET", path: "/s/c/clerk/get", session: session, timeout: 15))
    }

    /// GET /s/c/order/randomList. Returns the order list (possibly empty).
    /// code 0 的缺失/null列表兼容为空；非空但类型错误或业务拒绝仍抛错。
    public func randomList(pageSize: Int = 20, timeout: TimeInterval = 15) async throws -> [[String: Any]] {
        let resp = try await request(
            method: "GET",
            path: "/s/c/order/randomList?pageNo=1&pageSize=\(pageSize)",
            timeout: timeout
        )
        let endpoint = "/s/c/order/randomList"
        _ = try requireSuccess(resp, endpoint: endpoint)
        // The working PC client treats an absent/null list on code 0 as an empty hall.
        // Do not turn explicit business refusals or malformed non-null payloads into success.
        let nested = resp["data"] as? [String: Any]
        let top = resp["list"]
        if (top == nil || top is NSNull), let data = resp["data"], !(data is NSNull), nested == nil {
            throw ApiError(.api, "订单列表容器格式错误", detail: .responseShape, endpoint: endpoint, businessCode: 0)
        }
        let candidate = (top == nil || top is NSNull) ? nested?["list"] : top
        guard let candidate, !(candidate is NSNull) else { return [] }
        guard let list = candidate as? [[String: Any]] else {
            throw ApiError(.api, "订单列表格式错误", detail: .responseShape, endpoint: endpoint, businessCode: 0)
        }
        return list
    }

    /// GET /s/c/order/get?id=... (DPGrabber reconcile form, used to verify
    /// a confirm result whose response never arrived). Returns the decoded
    /// dict; the order object is in `order`.
    public func orderGet(orderID: String, timeout: TimeInterval = 15) async throws -> [String: Any] {
        let encoded = orderID.addingPercentEncoding(withAllowedCharacters: formSafe) ?? orderID
        return try requireSuccess(await request(method: "GET", path: "/s/c/order/get?id=\(encoded)", timeout: timeout))
    }

    /// POST /s/c/order/confirm?id=... . Returns the decoded dict.
    public func orderConfirm(orderID: String, timeout: TimeInterval = 10) async throws -> [String: Any] {
        let encoded = orderID.addingPercentEncoding(withAllowedCharacters: formSafe) ?? orderID
        return try await request(method: "POST", path: "/s/c/order/confirm?id=\(encoded)", timeout: timeout)
    }
}
