// DPGrabber iOS - API client unit tests (offline, stub transport).

import XCTest
@testable import DPGrabberCore

final class ApiClientTests: XCTestCase {

    private func makeClient(_ stub: StubTransport) -> ApiClient {
        ApiClient(
            aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex,
            aesKeyHexAlt: altAESKeyHex, aesIVHexAlt: altAESIVHex,
            verifyTLS: true, transport: stub
        )
    }

    // MARK: - Envelope decoding

    func testDecodePlainObjectResponse() throws {
        let client = makeClient(StubTransport())
        let decoded = try client.decodeResponse(plainEnvelope(["code": 0, "list": []]))
        XCTAssertEqual(decoded["code"] as? NSNumber, 0)
    }

    func testDecodePlainStringResponse() throws {
        let client = makeClient(StubTransport())
        let raw = jsonData(["response": "{\"code\": 0, \"msg\": \"ok\"}"])
        let decoded = try client.decodeResponse(raw)
        XCTAssertEqual(decoded["msg"] as? String, "ok")
    }

    func testDecodeEncryptedResponsePrimaryKey() throws {
        let client = makeClient(StubTransport())
        let b64 = try aesEncryptB64(Data("{\"code\": 0, \"list\": []}".utf8),
                                    keyHex: defaultAESKeyHex, ivHex: defaultAESIVHex)
        let decoded = try client.decodeResponse(encryptedEnvelope(b64))
        XCTAssertEqual(decoded["code"] as? NSNumber, 0)
    }

    func testDecodeEncryptedResponseFallsBackToAltKey() throws {
        let client = makeClient(StubTransport())
        let b64 = try aesEncryptB64(Data("{\"code\": 0, \"list\": []}".utf8),
                                    keyHex: altAESKeyHex, ivHex: altAESIVHex)
        let decoded = try client.decodeResponse(encryptedEnvelope(b64))
        XCTAssertEqual(decoded["code"] as? NSNumber, 0)
    }

    func testDecodeFallsBackToAESWhenPlainParseFails() throws {
        let client = makeClient(StubTransport())
        let b64 = try aesEncryptB64(Data("{\"code\": 0}".utf8),
                                    keyHex: defaultAESKeyHex, ivHex: defaultAESIVHex)
        // No isEncrypted flag, but the string is not plain JSON.
        let raw = jsonData(["response": b64])
        let decoded = try client.decodeResponse(raw)
        XCTAssertEqual(decoded["code"] as? NSNumber, 0)
    }

    func testDecodeMissingCodeThrows() {
        let client = makeClient(StubTransport())
        do {
            _ = try client.decodeResponse(plainEnvelope(["msg": "no code"]))
            XCTFail("expected an error")
        } catch let error as ApiError {
            XCTAssertEqual(error.kind, .api)
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testDecodeAuthAndIPClassification() throws {
        let client = makeClient(StubTransport())
        func kind(of raw: Data) -> ApiError.Kind? {
            do {
                _ = try client.decodeResponse(raw)
                return nil
            } catch let error as ApiError {
                return error.kind
            } catch {
                return nil
            }
        }
        XCTAssertEqual(kind(of: plainEnvelope(["code": 401])), .auth)
        XCTAssertEqual(kind(of: plainEnvelope(["code": 403])), .auth)
        XCTAssertEqual(kind(of: plainEnvelope(["code": 1, "msg": "IP 受限"])), .ip)
        // code 1 without "IP" in msg is a business failure, not IP
        // control: the envelope is returned and the caller decides
        // (loginAccount and the engine's confirm handling check the
        // code themselves), so decodeResponse must not throw here.
        let other = try client.decodeResponse(plainEnvelope(["code": 1, "msg": "其他错误"]))
        XCTAssertEqual(parseCode(other), 1)
    }

    func testDecodeNonJSONThrows() {
        let client = makeClient(StubTransport())
        XCTAssertThrowsError(try client.decodeResponse(Data("not json".utf8)))
    }

    // MARK: - Request construction

    func testRequestHeadersAndSession() async throws {
        let stub = StubTransport()
        var seenHeaders: [String: String] = [:]
        stub.handler = { _, _, headers, _ in
            seenHeaders = headers
            return (200, plainEnvelope(["code": 0]))
        }
        let client = makeClient(stub)
        client.session = DPGSession(token: "tok123", sid: "92")
        _ = try await client.request(method: "GET", path: "/s/c/clerk/get")
        XCTAssertEqual(seenHeaders["User-Agent"], dpUserAgent)
        XCTAssertEqual(seenHeaders["api-version"], dpAPIVersion)
        XCTAssertEqual(seenHeaders["platform"], "app")
        XCTAssertEqual(seenHeaders["authorization-token"], "tok123")
        XCTAssertEqual(seenHeaders["sid"], "92")
        XCTAssertTrue(stub.recorded.first?.url.absoluteString.hasPrefix(dpBaseURL + "/s/c/clerk/get") == true)
    }

    func testRequestRejectsNonSPath() async {
        let client = makeClient(StubTransport())
        do {
            _ = try await client.request(method: "GET", path: "/other/path")
            XCTFail("expected an error")
        } catch let error as ApiError {
            XCTAssertEqual(error.kind, .api)
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testHTTP401BecomesAuthError() async {
        let stub = StubTransport()
        stub.handler = { _, _, _, _ in (401, Data()) }
        let client = makeClient(stub)
        do {
            _ = try await client.request(method: "GET", path: "/s/c/clerk/get")
            XCTFail("expected an error")
        } catch let error as ApiError {
            XCTAssertEqual(error.kind, .auth)
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testFormEncodingSortedAndPercentEncoded() async throws {
        let stub = StubTransport()
        var seenBody = Data()
        var seenContentType = ""
        stub.handler = { _, _, headers, body in
            seenBody = body ?? Data()
            seenContentType = headers["Content-Type"] ?? ""
            return (200, plainEnvelope(["code": 0]))
        }
        let client = makeClient(stub)
        _ = try await client.request(
            method: "POST", path: "/s/duopei-account/switch-account",
            body: ["z": "a b", "a": "1&2"], bodyType: .form
        )
        XCTAssertEqual(seenContentType, "application/x-www-form-urlencoded")
        XCTAssertEqual(String(data: seenBody, encoding: .utf8), "a=1%262&z=a%20b")
    }

    // MARK: - Login flow

    private func loginStub(preferredShopWorks: Bool = true) -> StubTransport {
        let stub = StubTransport()
        stub.handler = { method, url, _, body in
            let path = url.path
            switch path {
            case "/s/duopei-account/login":
                return (200, plainEnvelope(["code": 0, "data": ["token": "tok-login", "shopId": 105]]))
            case "/s/duopei-account/list-shop-account":
                return (200, plainEnvelope(["code": 0, "data": [
                    ["shopId": 92, "accountId": "A92"],
                    ["shopId": 7, "accountId": "A7"],
                ]]))
            case "/s/duopei-account/switch-account":
                let text = String(data: body ?? Data(), encoding: .utf8) ?? ""
                if text.contains("accountId=A92") {
                    if preferredShopWorks {
                        return (200, plainEnvelope(["code": 0, "data": ["token": "tok-92"]]))
                    }
                    return (200, plainEnvelope(["code": 2, "msg": "shop busy"]))
                }
                return (200, plainEnvelope(["code": 0, "data": ["token": "tok-7"]]))
            case "/s/balance/getBalance":
                return (200, plainEnvelope(["code": 0]))
            case "/s/c/clerk/get":
                return (200, plainEnvelope(["code": 0, "clerk": ["id": 5, "name": "店员"]]))
            default:
                return (404, Data())
            }
        }
        return stub
    }

    func testLoginAccountUsesResponseShopID() async throws {
        let stub = loginStub()
        let client = makeClient(stub)
        let session = try await client.loginAccount(phone: "13800000000", password: "secret")
        XCTAssertEqual(session.token, "tok-login")
        XCTAssertEqual(session.sid, "105")
        let login = stub.lastRequest(containing: "duopei-account/login")
        XCTAssertEqual(login?.headers["sid"], "-1")
        XCTAssertEqual(login?.headers["authorization-token"], "")
        let body = try XCTUnwrap(login?.body.flatMap {
            try JSONSerialization.jsonObject(with: $0) as? [String: Any]
        })
        XCTAssertEqual(body["method"] as? String, "password")
        XCTAssertEqual(body["password"] as? String, "5ebe2294ecd0e0f08eab7690d2a6ee69")
        let phone = (body["phoneNumber"] as? [String: Any])?["mobile"] as? String
        XCTAssertEqual(phone, "13800000000")
    }

    func testSessionForAccountPrefersConfiguredShop() async throws {
        let stub = loginStub()
        let client = makeClient(stub)
        let session = try await client.sessionForAccount(phone: "13800000000", password: "secret", preferredShopID: 92)
        XCTAssertEqual(session.token, "tok-92")
        XCTAssertEqual(session.sid, "92")
        XCTAssertEqual(client.session, session)
        // The first switch attempt must be the preferred shop.
        let switches = stub.recorded.filter { $0.url.path.contains("switch-account") }
        XCTAssertEqual(switches.count, 1)
        XCTAssertTrue((String(data: switches[0].body ?? Data(), encoding: .utf8) ?? "").contains("accountId=A92"))
    }

    func testSessionForAccountFallsBackToNextShop() async throws {
        let stub = loginStub(preferredShopWorks: false)
        let client = makeClient(stub)
        let session = try await client.sessionForAccount(phone: "13800000000", password: "secret", preferredShopID: 92)
        XCTAssertEqual(session.token, "tok-7")
        XCTAssertEqual(session.sid, "7")
    }

    func testLoginRejectedThrowsAuth() async {
        let stub = StubTransport()
        stub.handler = { _, url, _, _ in
            if url.path.contains("login") {
                return (200, plainEnvelope(["code": 1001, "msg": "密码错误"]))
            }
            return (404, Data())
        }
        let client = makeClient(stub)
        do {
            _ = try await client.sessionForAccount(phone: "13800000000", password: "wrong")
            XCTFail("expected an error")
        } catch let error as ApiError {
            XCTAssertEqual(error.kind, .auth)
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testLoginRequiresPhoneAndPassword() async {
        let client = makeClient(StubTransport())
        await XCTAssertThrowsErrorAsync(try await client.loginAccount(phone: "", password: "x"))
        await XCTAssertThrowsErrorAsync(try await client.loginAccount(phone: "13800000000", password: ""))
        await XCTAssertThrowsErrorAsync(try await client.loginAccount(phone: "13800000000", password: " \n "))
    }

    // MARK: - Business calls

    func testRandomListShapes() async throws {
        let stub = StubTransport()
        stub.handler = { _, url, _, _ in
            if url.absoluteString.contains("pageSize=20") {
                return (200, plainEnvelope(["code": 0, "list": [["id": "1"], ["id": "2"]]]))
            }
            return (404, Data())
        }
        let client = makeClient(stub)
        let orders = try await client.randomList()
        XCTAssertEqual(orders.count, 2)
        XCTAssertEqual(orderID(of: orders[0]), "1")
    }

    func testRandomListDataListFallbackAndMissing() async throws {
        let stub = StubTransport()
        var calls = 0
        stub.handler = { _, _, _, _ in
            calls += 1
            if calls == 1 {
                return (200, plainEnvelope(["code": 0, "data": ["list": [["id": "9"]]]]))
            }
            return (200, plainEnvelope(["code": 0]))
        }
        let client = makeClient(stub)
        let first = try await client.randomList()
        XCTAssertEqual(first.count, 1)
        await XCTAssertThrowsErrorAsync(try await client.randomList())
    }

    func testExtractClerk() {
        XCTAssertEqual(extractClerk(["clerk": ["id": 5, "shopId": 92, "name": "店员"]])?.id, 5)
        XCTAssertEqual(extractClerk(["data": ["id": 7]])?.id, 7)
        XCTAssertEqual(extractClerk(["id": 9, "name": "x"])?.id, 9)
        XCTAssertNil(extractClerk(["clerk": ["id": 0]]))
        XCTAssertNil(extractClerk([:]))
    }
}

extension ApiClientTests {
    func testSixPortOperationsUseExactFormsAndPreservePayloads() async throws {
        let stub = StubTransport()
        stub.handler = { _, url, _, _ in
            if url.path.contains("statisticsClerkOrder") {
                return (200, plainEnvelope(["code": 0, "list": ["orderCount": 7, "orderAmount": 1000]]))
            }
            if url.path == "/s/c/order/list" { return (200, plainEnvelope(["code": 0, "total": 17])) }
            if url.path.contains("getItemSwitchList") {
                return (200, plainEnvelope(["code": 0, "itemSwitchList": [["id": 465, "name": "类型"]], "clerkItemSwitchIds": [465]]))
            }
            return (200, plainEnvelope(["code": 0]))
        }
        let client = makeClient(stub)
        client.session = DPGSession(token: "offline", sid: "1391")
        _ = try await client.updateStatus(online: true, updateAutoOnline: false)
        _ = try await client.updateStatus(online: false, updateAutoOnline: true)
        _ = try await client.updateRandomOrderSwitch(enabled: true)
        _ = try await client.updateRandomOrderSwitch(enabled: false)
        let switches = try await client.getItemSwitchList()
        XCTAssertEqual((switches["itemSwitchList"] as? [[String: Any]])?.count, 1)
        _ = try await client.updateItemSwitch(switchID: 465, enabledIDs: [465, 2666])
        _ = try await client.updateItemSwitch(switchID: 465, enabledIDs: [])
        let stats = try await client.statisticsClerkOrder(startTime: "2026-11-01", endTime: "2026-11-15")
        XCTAssertEqual((stats["list"] as? [String: Any])?["orderCount"] as? Int, 7)
        let orders = try await client.orderList(startTime: "2026-11-01 00:00:00", endTime: "2026-11-15 23:59:59")
        XCTAssertEqual(orders["total"] as? Int, 17)
        let forms = stub.recorded.filter { $0.method == "POST" }
        XCTAssertEqual(forms.map { $0.url.path }, [
            "/s/c/clerk/updateStatus", "/s/c/clerk/updateStatus",
            "/s/c/clerk/updateRandomOrderSwitch", "/s/c/clerk/updateRandomOrderSwitch",
            "/s/c/clerk/getItemSwitchList", "/s/c/clerk/updateItemSwitch", "/s/c/clerk/updateItemSwitch"
        ])
        XCTAssertEqual(forms.map { String(decoding: $0.body ?? Data(), as: UTF8.self) }, [
            "status=1&updateAutoOnline=false", "status=2&updateAutoOnline=true",
            "randomOrderSwitch=true", "randomOrderSwitch=false", "",
            "switchId=465&switchIds=%5B465%2C2666%5D", "switchId=465&switchIds=%5B%5D"
        ])
        for request in forms {
            XCTAssertEqual(request.headers["Content-Type"], "application/x-www-form-urlencoded")
            XCTAssertNotNil(request.body)
        }
        for request in stub.recorded {
            XCTAssertEqual(request.headers["sid"], "1391")
            XCTAssertEqual(request.headers["authorization-token"], "offline")
        }
        let statistics = try XCTUnwrap(stub.lastRequest(containing: "statisticsClerkOrder"))
        XCTAssertEqual(statistics.method, "GET")
        XCTAssertEqual(statistics.url.query, "startTime=2026-11-01&endTime=2026-11-15")
        XCTAssertNil(statistics.body)
        let list = try XCTUnwrap(stub.lastRequest(containing: "/s/c/order/list"))
        XCTAssertEqual(list.method, "GET")
        XCTAssertEqual(list.url.query, "pageNo=1&pageSize=1&startTime=2026-11-01%2000%3A00%3A00&endTime=2026-11-15%2023%3A59%3A59")
        XCTAssertFalse(list.url.absoluteString.contains("%2520"))
        XCTAssertFalse(list.url.absoluteString.contains("pageNum"))
        XCTAssertNil(list.body)
    }

    func testAllSixPortOperationsRejectBusinessErrors() async {
        let stub = StubTransport()
        stub.handler = { _, _, _, _ in (200, plainEnvelope(["code": 2, "msg": "没有操作权限"])) }
        let client = makeClient(stub)
        let calls: [() async throws -> [String: Any]] = [
            { try await client.updateStatus(online: true) },
            { try await client.updateRandomOrderSwitch(enabled: true) },
            { try await client.getItemSwitchList() },
            { try await client.updateItemSwitch(switchID: 465, enabledIDs: [465]) },
            { try await client.statisticsClerkOrder(startTime: "2026-11-01", endTime: "2026-11-15") },
            { try await client.orderList(startTime: "2026-11-01 00:00:00", endTime: "2026-11-15 23:59:59") }
        ]
        for call in calls {
            do { _ = try await call(); XCTFail("业务错误不能返回成功") }
            catch let error as ApiError { XCTAssertEqual(error.kind, .api); XCTAssertTrue(error.message.contains("没有操作权限")) }
            catch { XCTFail("非预期错误：\(error)") }
        }
        XCTAssertEqual(stub.recorded.count, 6)
    }

    func testDateValidationRejectsWrongShapeCalendarAndRangeWithoutTransport() async throws {
        let stub = StubTransport()
        let client = makeClient(stub)
        let invalidDates = ["2026-02-30", "2026-2-01", "2026-11-01 00:00:00", "2026-11-01&x=y", "2026-11-01%20"]
        for value in invalidDates {
            await XCTAssertThrowsErrorAsync(try await client.statisticsClerkOrder(startTime: value, endTime: "2026-12-31"))
        }
        await XCTAssertThrowsErrorAsync(try await client.statisticsClerkOrder(startTime: "2026-11-15", endTime: "2026-11-01"))
        for value in ["2026-11-01", "2026-11-01 24:00:00", "2026-11-01 12:60:00", "2026-02-30 00:00:00", "2026-11-01 00:00:00&x=y"] {
            await XCTAssertThrowsErrorAsync(try await client.orderList(startTime: value, endTime: "2026-12-31 23:59:59"))
        }
        await XCTAssertThrowsErrorAsync(try await client.orderList(startTime: "2026-11-15 00:00:00", endTime: "2026-11-01 00:00:00"))
        await XCTAssertThrowsErrorAsync(try await client.orderList(startTime: "2026-11-01 00:00:00", endTime: "2026-11-15 00:00:00", pageNo: 0))
        await XCTAssertThrowsErrorAsync(try await client.orderList(startTime: "2026-11-01 00:00:00", endTime: "2026-11-15 00:00:00", pageSize: 0))
        XCTAssertTrue(stub.recorded.isEmpty)
        stub.handler = { _, _, _, _ in (200, plainEnvelope(["code": 0, "list": [:]])) }
        _ = try await client.statisticsClerkOrder(startTime: "2024-02-29", endTime: "2024-02-29")
        XCTAssertEqual(stub.recorded.count, 1)
    }

    func testLoginTrimsSecretPreservesHashAndValidatesShopID() async throws {
        let stub = StubTransport()
        var shopID: Any? = "105"
        stub.handler = { _, _, _, _ in
            var data: [String: Any] = ["token": "offline"]
            data["shopId"] = shopID
            return (200, plainEnvelope(["code": 0, "data": data]))
        }
        let client = makeClient(stub)
        let first = try await client.loginAccount(phone: " 13800000000 ", password: " secret \n")
        XCTAssertEqual(first.sid, "105")
        var body = try XCTUnwrap(stub.recorded.last?.body)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["password"] as? String, "5ebe2294ecd0e0f08eab7690d2a6ee69")
        let hash = "ABCDEF0123456789ABCDEF0123456789"
        shopID = 92
        _ = try await client.loginAccount(phone: "13800000000", password: hash)
        body = try XCTUnwrap(stub.recorded.last?.body)
        json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["password"] as? String, hash)
        shopID = 92.0
        let numericSession = try await client.loginAccount(phone: "13800000000", password: "secret")
        XCTAssertEqual(numericSession.sid, "92")
        let invalidIDs: [Any?] = [nil, 0, -1, true, "", "abc", "0", 1.5]
        for invalid in invalidIDs {
            shopID = invalid
            do { _ = try await client.loginAccount(phone: "13800000000", password: "secret"); XCTFail("无效 shopId 不能猜默认值") }
            catch let error as ApiError { XCTAssertEqual(error.kind, .auth); XCTAssertTrue(error.message.contains("shopId")) }
        }
    }

    func testCandidateAuthContinuesButBaseSwitchAuthAborts() async throws {
        for failurePath in ["/s/balance/getBalance", "/s/c/clerk/get", "/s/duopei-account/switch-account"] {
            let stub = loginStub()
            let original = stub.handler!
            stub.handler = { method, url, headers, body in
                if url.path == "/s/duopei-account/list-shop-account" {
                    XCTAssertEqual(headers["sid"], "105")
                    return (200, plainEnvelope(["code": 0, "data": [["shopId": "92", "accountId": "A92"], ["shopId": "7", "accountId": "A7"]]]))
                }
                if url.path == "/s/duopei-account/switch-account" { XCTAssertEqual(headers["sid"], "105") }
                if url.path == failurePath && (headers["sid"] == "92" || failurePath.contains("switch-account")) {
                    return (200, plainEnvelope(["code": 401]))
                }
                return try original(method, url, headers, body)
            }
            let client = makeClient(stub)
            if failurePath.contains("switch-account") {
                do { _ = try await client.sessionForAccount(phone: "13800000000", password: "secret"); XCTFail("基础令牌失效必须终止") }
                catch let error as ApiError { XCTAssertEqual(error.kind, .auth) }
                XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("switch-account") }.count, 1)
                XCTAssertNil(client.session)
            } else {
                let session = try await client.sessionForAccount(phone: "13800000000", password: "secret")
                XCTAssertEqual(session.sid, "7")
                XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("switch-account") }.count, 2)
            }
        }
    }

    func testCandidateBusinessDenialContinuesToNextShop() async throws {
        for failurePath in ["/s/balance/getBalance", "/s/c/clerk/get"] {
            let stub = loginStub()
            let original = stub.handler!
            stub.handler = { method, url, headers, body in
                if url.path == failurePath && headers["sid"] == "92" {
                    return (200, plainEnvelope(["code": 2, "msg": "没有操作权限"]))
                }
                return try original(method, url, headers, body)
            }
            let client = makeClient(stub)
            let session = try await client.sessionForAccount(phone: "13800000000", password: "secret")
            XCTAssertEqual(session.sid, "7")
            XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("switch-account") }.count, 2)
        }
    }

    func testRandomListBusinessFailureAndMalformedListThrow() async {
        let stub = StubTransport()
        let client = makeClient(stub)
        let responses: [[String: Any]] = [["code": 2, "msg": "拒绝"], ["code": 0], ["code": 0, "list": "错误"], ["code": 0, "data": ["list": [:]]]]
        for response in responses {
            stub.handler = { _, _, _, _ in (200, plainEnvelope(response)) }
            await XCTAssertThrowsErrorAsync(try await client.randomList())
        }
    }
}

// MARK: - Helpers

func XCTAssertThrowsErrorAsync(
    _ expression: @autoclosure () async throws -> some Any,
    file: StaticString = #filePath, line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("expected an error", file: file, line: line)
    } catch {
        // Expected.
    }
}
