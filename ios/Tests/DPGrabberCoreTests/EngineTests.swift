// DPGrabber iOS - Listening engine unit tests (offline, stub transport).
//
// The engine's clocks and sleeper are injected: `now` is fixed, the
// sleeper returns immediately (and stops the engine after a bounded
// number of sleeps, so looping tests terminate deterministically),
// and `monotonic` can be a fake clock advanced by the sleeper, so
// deadline tests run deterministically without real time.

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

    /// A fake monotonic clock, advanced by the injected sleeper, so
    /// confirm-deadline tests are deterministic (no real time).
    private final class FakeClock: @unchecked Sendable {
        private let lock = NSLock()
        private var _now: TimeInterval
        init(_ start: TimeInterval) { _now = start }
        var now: TimeInterval { lock.withLock { _now } }
        func advance(by seconds: TimeInterval) { lock.withLock { _now += seconds } }
    }

    /// A stub handler answering the whole login flow plus scripted
    /// business calls. `orderScript` decides what randomList returns on
    /// each call; `confirmScript` decides the confirm response body and
    /// `confirmStatusScript` its HTTP status (default 200);
    /// `orderGetScript` decides the order/get payload for readbacks
    /// (default: the legacy precise-time detail, kept for tests that
    /// never read back).
    private func fullFlowStub(
        orderScript: @escaping (Int) -> [[String: Any]],
        confirmScript: @escaping (Int) -> [String: Any],
        orderDetail: [String: Any]? = nil,
        orderGetScript: ((Int) -> [String: Any])? = nil,
        confirmStatusScript: ((Int) -> Int)? = nil
    ) -> StubTransport {
        let stub = StubTransport()
        var pollCount = 0
        var confirmCount = 0
        var getCount = 0
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
                getCount += 1
                if let orderGetScript {
                    return (200, plainEnvelope(["code": 0, "order": orderGetScript(getCount)]))
                }
                let detail = orderDetail ?? [
                    "reTime": Int((self.fixedNow + 10) * 1000),
                    "confirmCountDown": 5,
                    "createTime": Int(self.fixedNow * 1000),
                ]
                return (200, plainEnvelope(["code": 0, "order": detail]))
            case "/s/c/order/confirm":
                confirmCount += 1
                let status = confirmStatusScript?(confirmCount) ?? 200
                return (status, plainEnvelope(confirmScript(confirmCount)))
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

    /// Wait until `condition` holds (or the timeout passes).
    private func waitUntil(_ condition: @escaping () -> Bool, timeout: TimeInterval = 5) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return condition()
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
        XCTAssertTrue(logs.contains("尝试接单"))
        XCTAssertTrue(logs.contains("抢单成功"))
        // No order/get detail fetch: the confirm task starts immediately.
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/get") }.count, 0)
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
        // The confirm task starts immediately, without an order/get fetch.
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/get") }.count, 0)
    }

    func testEngineDedupsRepeatedOrder() async throws {
        let order: [String: Any] = ["id": "ord-3", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        var getCalls = 0
        let stub = fullFlowStub(
            orderScript: { _ in [order] },
            confirmScript: { _ in ["code": 2, "msg": "已被抢"] }
        )
        // Count order/get calls by wrapping the stub handler.
        let original = stub.handler
        stub.handler = { method, url, headers, body in
            if url.path.contains("order/get") { getCalls += 1 }
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
        // confirm failed terminally (not success), the order was
        // de-duped afterwards, and no readback ran for a terminal fail.
        XCTAssertEqual(getCalls, 0)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
        XCTAssertTrue(logs.contains("确认终态失败"))
    }

    func testEngineRetriesConfirmThenSucceeds() async throws {
        let order: [String: Any] = ["id": "ord-4", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        let stub = fullFlowStub(
            orderScript: { $0 == 1 ? [order] : [] },
            confirmScript: { $0 == 1 ? ["code": 2, "msg": "稍后再试"] : ["code": 0] }
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
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 2)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/get") }.count, 0)
        XCTAssertTrue(logs.contains("抢单成功"))
    }

    func testEngineAuthInConfirmRefreshesAndRetries() async throws {
        let order: [String: Any] = ["id": "ord-5", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        let stub = fullFlowStub(
            orderScript: { _ in [order] },
            confirmScript: { $0 == 1 ? ["code": 401] : ["code": 0] }
        )
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
        XCTAssertTrue(stopped, "engine did not stop after a successful grab")
        XCTAssertEqual(
            stub.recorded.filter { $0.url.path.contains("/s/duopei-account/login") }.count,
            2, "expected one login plus one refresh login"
        )
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 2)
        XCTAssertTrue(logs.contains("确认认证失败"))
        XCTAssertTrue(logs.contains("抢单成功"))
    }

    func testEngineNetworkErrorOnConfirmReadsBackAndSucceeds() async throws {
        let order: [String: Any] = ["id": "ord-6", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        let stub = fullFlowStub(
            orderScript: { $0 == 1 ? [order] : [] },
            confirmScript: { _ in ["code": 0] },
            orderGetScript: { _ in ["status": 2, "clerkId": 5] },
            confirmStatusScript: { $0 == 1 ? 500 : 200 }
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
        XCTAssertTrue(stopped, "engine did not stop after readback success")
        XCTAssertEqual(grabbed.count, 1)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/get") }.count, 1)
        XCTAssertTrue(logs.contains("抢单成功"))
    }

    func testEngineReadbackPendingRetriesAndSucceeds() async throws {
        let order: [String: Any] = ["id": "ord-7", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        let stub = fullFlowStub(
            orderScript: { $0 == 1 ? [order] : [] },
            confirmScript: { _ in ["code": 0] },
            orderGetScript: { _ in ["status": 0] },
            confirmStatusScript: { $0 == 1 ? 500 : 200 }
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
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 2)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/get") }.count, 1)
        XCTAssertTrue(logs.contains("抢单成功"))
    }

    func testEngineReadbackUnknownStopsBlindRetry() async throws {
        let order: [String: Any] = ["id": "ord-8", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        let stub = fullFlowStub(
            orderScript: { _ in [order] },
            confirmScript: { _ in ["code": 0] },
            orderGetScript: { _ in ["status": 9, "msg": "奇怪"] },
            confirmStatusScript: { _ in 500 }
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
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/get") }.count, 1)
        XCTAssertTrue(logs.contains("停止盲重试"))
    }

    func testEngineReadbackClerkMismatchFails() async throws {
        let order: [String: Any] = ["id": "ord-9", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]
        let stub = fullFlowStub(
            orderScript: { _ in [order] },
            confirmScript: { _ in ["code": 0] },
            orderGetScript: { _ in ["status": 2, "clerkId": 99] },
            confirmStatusScript: { _ in 500 }
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
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
        XCTAssertEqual(stub.recorded.filter { $0.url.path.contains("order/get") }.count, 1)
        XCTAssertTrue(logs.contains("被他人接走"))
    }

    func testEngineDeadlinePendingReleasesOrder() async throws {
        // createTime present, so the 0.3 s window (not the fallback) applies.
        let order: [String: Any] = [
            "id": "ord-10", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": "",
            "createTime": Int(fixedNow * 1000),
        ]
        let stub = fullFlowStub(
            orderScript: { _ in [order] },
            confirmScript: { _ in ["code": 2, "msg": "稍后再试"] },
            orderGetScript: { _ in ["status": 0] }
        )
        let client = ApiClient(
            aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex,
            aesKeyHexAlt: altAESKeyHex, aesIVHexAlt: altAESIVHex,
            transport: stub
        )
        var cfg = makeConfig()
        cfg.randomConfirmWindowSec = 0.3
        cfg.confirmRetryDelaySec = 0.05
        let logs = LogCapture()
        let clock = FakeClock(1000)
        let engine = ListenEngine(
            client: client, config: cfg,
            log: { logs.append($0, $1) },
            now: { self.fixedNow },
            monotonic: { clock.now },
            sleeper: { seconds in clock.advance(by: seconds); return true }
        )
        XCTAssertTrue(engine.start(phone: "13800000000", password: "secret"))
        let released = await waitUntil({ logs.contains("本轮确认预算耗尽") })
        XCTAssertTrue(released, "expected the deadline release log")
        XCTAssertTrue(engine.isRunning, "engine should still be running after a pending release")
        // The released order was picked up again, starting a new task.
        let retried = await waitUntil({ logs.entries.filter { $0.1.contains("尝试接单") }.count >= 2 })
        XCTAssertTrue(retried, "expected at least two confirm tasks after release")
        XCTAssertTrue(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count > 1)
        engine.stop()
        let stopped = await waitUntilStopped(engine)
        XCTAssertTrue(stopped)
    }

    func testEngineDeadlineFinalReadbackOkSucceeds() async throws {
        // createTime present, so the 0.3 s window (not the fallback) applies.
        let order: [String: Any] = [
            "id": "ord-11", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": "",
            "createTime": Int(fixedNow * 1000),
        ]
        let stub = fullFlowStub(
            orderScript: { $0 == 1 ? [order] : [] },
            confirmScript: { _ in ["code": 2, "msg": "稍后再试"] },
            orderGetScript: { _ in ["status": 2, "clerkId": 5] }
        )
        let client = ApiClient(
            aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex,
            aesKeyHexAlt: altAESKeyHex, aesIVHexAlt: altAESIVHex,
            transport: stub
        )
        var cfg = makeConfig()
        cfg.randomConfirmWindowSec = 0.3
        cfg.confirmRetryDelaySec = 0.05
        let logs = LogCapture()
        let clock = FakeClock(1000)
        var grabbed: [[String: Any]] = []
        let engine = ListenEngine(
            client: client, config: cfg,
            log: { logs.append($0, $1) },
            now: { self.fixedNow },
            monotonic: { clock.now },
            sleeper: { seconds in clock.advance(by: seconds); return true }
        )
        engine.onGrabSuccess = { grabbed.append($0) }
        XCTAssertTrue(engine.start(phone: "13800000000", password: "secret"))
        let stopped = await waitUntilStopped(engine)
        XCTAssertTrue(stopped, "engine did not stop after final readback success")
        XCTAssertEqual(grabbed.count, 1)
        XCTAssertTrue(stub.recorded.filter { $0.url.path.contains("order/confirm") }.count > 1)
        XCTAssertTrue(logs.contains("抢单成功"))
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
