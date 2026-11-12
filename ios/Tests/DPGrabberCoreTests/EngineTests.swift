// DPGrabber iOS - Listening engine unit tests (offline, stub transport).
//
// The engine's clock and sleeper are injected: `now` is fixed, the
// sleeper returns immediately (and stops the engine after a bounded
// number of sleeps, so looping tests terminate deterministically).

import XCTest
@testable import DPGrabberCore

final class EngineTests: XCTestCase {

    private final class LogCapture {
        private let lock = NSLock()
        private var _entries: [(LogLevel, String)] = []
        var entries: [(LogLevel, String)] { lock.withLock { _entries } }
        func append(_ level: LogLevel, _ message: String) {
            lock.withLock { _entries.append((level, message)) }
        }
        func contains(_ substring: String) -> Bool {
            entries.contains { $0.1.contains(substring) }
        }
    }

    /// Fixed wall clock: 2026-11-12 12:00:00 UTC.
    private let fixedNow: TimeInterval = 1_762_944_000.0

    private func makeConfig() -> DPGConfig {
        var cfg = DPGConfig()
        cfg.minAmount = 10
        cfg.maxAmount = 0
        cfg.memoMode = 0
        cfg.pollIntervalSec = 0.5
        return cfg
    }

    /// A stub handler answering the whole login flow plus scripted
    /// business calls. `orderScript` decides what randomList returns on
    /// each call; `confirmScript` decides the confirm response.
    private func fullFlowStub(
        orderScript: @escaping (Int) -> [[String: Any]],
        confirmScript: @escaping (Int) -> [String: Any],
        orderDetail: [String: Any]? = nil
    ) -> StubTransport {
        let stub = StubTransport()
        var pollCount = 0
        var confirmCount = 0
        var loginCount = 0
        stub.handler = { _, url, _, body in
            let path = url.path
            switch path {
            case "/s/duopei-account/login":
                loginCount += 1
                return (200, plainEnvelope(["code": 0, "data": ["token": "tok-login-\(loginCount)"]]))
            case "/s/duopei-account/list-shop-account":
                return (200, plainEnvelope(["code": 0, "data": [["shopId": 92, "accountId": "A92"]]]))
            case "/s/duopei-account/switch-account":
                return (200, plainEnvelope(["code": 0, "data": ["token": "tok-92"]]))
            case "/s/balance/getBalance":
                return (200, plainEnvelope(["code": 0]))
            case "/s/c/clerk/get":
                return (200, plainEnvelope(["code": 0, "clerk": ["id": 5, "shopId": 92, "name": "店员"]]))
            case "/s/c/order/randomList":
                pollCount += 1
                return (200, plainEnvelope(["code": 0, "list": orderScript(pollCount)]))
            case "/s/c/order/get":
                let detail = orderDetail ?? [
                    "reTime": Int((self.fixedNow + 10) * 1000),
                    "confirmCountDown": 5,
                    "createTime": Int(self.fixedNow * 1000),
                ]
                return (200, plainEnvelope(["code": 0, "order": detail]))
            case "/s/c/order/confirm":
                confirmCount += 1
                return (200, plainEnvelope(confirmScript(confirmCount)))
            default:
                return (404, Data())
            }
        }
        return stub
    }

    private func waitUntilStopped(_ engine: ListenEngine, timeout: TimeInterval = 5) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !engine.isRunning { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return !engine.isRunning
    }

    // MARK: - End to end

    func testEngineGrabsSuccessfullyAndStops() async throws {
        let order: [String: Any] = ["id": "ord-1", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        let stub = fullFlowStub(
            orderScript: { $0 == 1 ? [order] : [] },
            confirmScript: { _ in ["code": 0] }
        )
        let client = ApiClient(
            aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex,
            aesKeyHexAlt: altAESKeyHex, aesIVHexAlt: altAESIVHex,
            transport: stub
        )
        let logs = LogCapture()
        var grabbed: [[String: Any]] = []
        let engine = ListenEngine(
            client: client, config: makeConfig(),
            log: { logs.append($0, $1) },
            now: { self.fixedNow },
            sleeper: { _ in true }
        )
        engine.onGrabSuccess = { grabbed.append($0) }
        XCTAssertTrue(engine.start(phone: "13800000000", password: "secret"))
        let stopped = await waitUntilStopped(engine)
        XCTAssertTrue(stopped, "engine did not stop after a successful grab")
        XCTAssertEqual(grabbed.count, 1)
        XCTAssertEqual(orderID(of: grabbed.first), "ord-1")
        XCTAssertTrue(logs.contains("登录成功"))
        XCTAssertTrue(logs.contains("抢单成功"))
        XCTAssertTrue(logs.contains("准确抢单时间"))
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
    }

    func testEngineSkipsFilteredOrderWithoutConfirming() async throws {
        let cheap: [String: Any] = ["id": "ord-cheap", "totalAmount": 500, "userWechatId": "wx1", "userMemo": ""]
        let stub = fullFlowStub(
            orderScript: { _ in [cheap] },
            confirmScript: { _ in ["code": 0] }
        )
        let client = ApiClient(
            aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex,
            aesKeyHexAlt: altAESKeyHex, aesIVHexAlt: altAESIVHex,
            transport: stub
        )
        let logs = LogCapture()
        var engine: ListenEngine!
        var sleeps = 0
        engine = ListenEngine(
            client: client, config: makeConfig(),
            log: { logs.append($0, $1) },
            now: { self.fixedNow },
            sleeper: { _ in
                sleeps += 1
                if sleeps >= 3 { engine.stop(); return false }
                return true
            }
        )
        XCTAssertTrue(engine.start(phone: "13800000000", password: "secret"))
        let stopped = await waitUntilStopped(engine)
        XCTAssertTrue(stopped)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 0)
        XCTAssertTrue(logs.contains("低于最低金额"))
    }

    func testEngineRefreshesSessionOnAuthFailure() async throws {
        let order: [String: Any] = ["id": "ord-2", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        let stub = StubTransport()
        var pollCount = 0
        var loginCount = 0
        stub.handler = { _, url, _, _ in
            switch url.path {
            case "/s/duopei-account/login":
                loginCount += 1
                return (200, plainEnvelope(["code": 0, "data": ["token": "tok-\(loginCount)"]]))
            case "/s/duopei-account/list-shop-account":
                return (200, plainEnvelope(["code": 0, "data": [["shopId": 92, "accountId": "A92"]]]))
            case "/s/duopei-account/switch-account":
                return (200, plainEnvelope(["code": 0, "data": ["token": "tok-92"]]))
            case "/s/balance/getBalance":
                return (200, plainEnvelope(["code": 0]))
            case "/s/c/clerk/get":
                return (200, plainEnvelope(["code": 0, "clerk": ["id": 5, "shopId": 92, "name": "店员"]]))
            case "/s/c/order/randomList":
                pollCount += 1
                if pollCount == 1 {
                    // First poll fails with 401: the engine must re-login.
                    return (200, plainEnvelope(["code": 401]))
                }
                return (200, plainEnvelope(["code": 0, "list": [order]]))
            case "/s/c/order/get":
                return (200, plainEnvelope(["code": 0, "order": [
                    "reTime": Int((self.fixedNow + 10) * 1000),
                    "confirmCountDown": 5,
                ]]))
            case "/s/c/order/confirm":
                return (200, plainEnvelope(["code": 0]))
            default:
                return (404, Data())
            }
        }
        let client = ApiClient(
            aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex,
            aesKeyHexAlt: altAESKeyHex, aesIVHexAlt: altAESIVHex,
            transport: stub
        )
        let logs = LogCapture()
        let engine = ListenEngine(
            client: client, config: makeConfig(),
            log: { logs.append($0, $1) },
            now: { self.fixedNow },
            sleeper: { _ in true }
        )
        XCTAssertTrue(engine.start(phone: "13800000000", password: "secret"))
        let stopped = await waitUntilStopped(engine)
        XCTAssertTrue(stopped)
        XCTAssertEqual(loginCount, 2, "expected one login plus one refresh login")
        XCTAssertTrue(logs.contains("重新建立"))
        XCTAssertTrue(logs.contains("抢单成功"))
    }

    func testEngineDedupsRepeatedOrder() async throws {
        let order: [String: Any] = ["id": "ord-3", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        var detailCalls = 0
        let stub = fullFlowStub(
            orderScript: { _ in [order] },
            confirmScript: { _ in ["code": 2, "msg": "已被抢"] }
        )
        // Count order/get calls by wrapping the stub handler.
        let original = stub.handler
        stub.handler = { method, url, headers, body in
            if url.path.contains("order/get") { detailCalls += 1 }
            return try original!(method, url, headers, body)
        }
        let client = ApiClient(
            aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex,
            aesKeyHexAlt: altAESKeyHex, aesIVHexAlt: altAESIVHex,
            transport: stub
        )
        let logs = LogCapture()
        var engine: ListenEngine!
        var sleeps = 0
        engine = ListenEngine(
            client: client, config: makeConfig(),
            log: { logs.append($0, $1) },
            now: { self.fixedNow },
            sleeper: { _ in
                sleeps += 1
                if sleeps >= 4 { engine.stop(); return false }
                return true
            }
        )
        XCTAssertTrue(engine.start(phone: "13800000000", password: "secret"))
        let stopped = await waitUntilStopped(engine)
        XCTAssertTrue(stopped)
        // The same order was polled repeatedly but handled only once:
        // confirm failed (not success), the order was de-duped afterwards.
        XCTAssertEqual(detailCalls, 1)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
        XCTAssertTrue(logs.contains("抢单未成功"))
    }

    func testEngineStartWhileRunningReturnsFalse() async throws {
        let stub = fullFlowStub(orderScript: { _ in [] }, confirmScript: { _ in ["code": 0] })
        let client = ApiClient(
            aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex,
            aesKeyHexAlt: altAESKeyHex, aesIVHexAlt: altAESIVHex,
            transport: stub
        )
        let engine = ListenEngine(
            client: client, config: makeConfig(),
            log: { _, _ in },
            now: { self.fixedNow },
            sleeper: { _ in true }
        )
        XCTAssertTrue(engine.start(phone: "13800000000", password: "secret"))
        XCTAssertFalse(engine.start(phone: "13800000000", password: "secret"))
        engine.stop()
        let stopped = await waitUntilStopped(engine)
        XCTAssertTrue(stopped)
    }
}
