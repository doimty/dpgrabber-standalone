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
                return (200, plainEnvelope(["code": 0, "data": ["token": "tok-login"]]))
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
                return (200, plainEnvelope(["code": 0, "clerk": ["id": 5, "shopId": 92, "name": "店员"]]))
            default:
                return (404, Data())
            }
        }
        return stub
    }

    func testLoginAccountReturnsSid50() async throws {
        let stub = loginStub()
        let client = makeClient(stub)
        let session = try await client.loginAccount(phone: "13800000000", password: "secret")
        XCTAssertEqual(session.token, "tok-login")
        XCTAssertEqual(session.sid, "50")
        let login = stub.lastRequest(containing: "duopei-account/login")
        XCTAssertEqual(login?.headers["sid"], "-1")
        XCTAssertEqual(login?.headers["authorization-token"], "")
        let body = try XCTUnwrap(login?.body.flatMap {
            try JSONSerialization.jsonObject(with: $0) as? [String: Any]
        })
        XCTAssertEqual(body["method"] as? String, "password")
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
        let second = try await client.randomList()
        XCTAssertTrue(second.isEmpty)
    }

    func testExtractClerk() {
        XCTAssertEqual(extractClerk(["clerk": ["id": 5, "shopId": 92, "name": "店员"]])?.id, 5)
        XCTAssertEqual(extractClerk(["data": ["id": 7]])?.id, 7)
        XCTAssertEqual(extractClerk(["id": 9, "name": "x"])?.id, 9)
        XCTAssertNil(extractClerk(["clerk": ["id": 0]]))
        XCTAssertNil(extractClerk([:]))
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
