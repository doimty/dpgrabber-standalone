import XCTest
@testable import DPGrabberCore

final class EngineTests: XCTestCase {
    private func client(_ stub: StubTransport) -> ApiClient {
        let client = ApiClient(aesKeyHex: defaultAESKeyHex, aesIVHex: defaultAESIVHex, transport: stub)
        client.session = DPGSession(token: "offline-token", sid: "92")
        return client
    }
    private func stub(order: [String: Any] = ["id": "o1", "totalAmount": 2500], confirm: [String: Any] = ["code": 0]) -> StubTransport {
        let stub = StubTransport()
        stub.handler = { _, url, _, _ in
            switch url.path {
            case "/s/c/clerk/get": return (200, plainEnvelope(["code": 0, "clerk": ["id": 5]]))
            case "/s/c/order/randomList": return (200, plainEnvelope(["code": 0, "list": [order]]))
            case "/s/c/order/confirm": return (200, plainEnvelope(confirm))
            case "/s/c/order/get": return (200, plainEnvelope(["code": 0, "order": [:]]))
            default: return (404, Data())
            }
        }
        return stub
    }
    private func finish(_ engine: ListenEngine) async {
        for _ in 0..<1000 {
            if !engine.isRunning { break }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        await engine.stopAndWait()
    }

    func testSharedSessionAndSuccessHistoryAreIdempotent() async throws {
        let transport = stub()
        let history = try OrderHistoryStore()
        let engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in true }, history: history)
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertEqual(transport.recorded.filter { $0.url.path.contains("login") }.count, 0)
        XCTAssertEqual(history.all.count, 1)
        XCTAssertEqual(history.all.first?.outcome, .success)
        XCTAssertEqual(history.all.first?.shopID, "92")
        XCTAssertFalse(history.all.first?.runID.isEmpty ?? true)
        XCTAssertFalse(history.all.first?.attemptID.isEmpty ?? true)
    }

    func testSuccessIsPersistedBeforeNotification() async throws {
        let transport = stub()
        let history = OrderHistoryStore.inMemory()
        var persistedAtCallback = false
        let engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in true }, history: history)
        engine.onGrabSuccess = { _ in persistedAtCallback = history.all.first?.outcome == .success }
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertTrue(persistedAtCallback)
    }

    func testMissingOwnershipReadbackRemainsUnknown() async throws {
        let transport = stub()
        let original = transport.handler!
        transport.handler = { method, url, headers, body in
            if url.path.contains("order/confirm") { throw URLError(.timedOut) }
            if url.path.contains("order/get") { return (200, plainEnvelope(["code": 0, "status": 2])) }
            return try original(method, url, headers, body)
        }
        let history = OrderHistoryStore.inMemory()
        var sleeps = 0
        var engine: ListenEngine!
        engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in
            sleeps += 1
            if sleeps > 4 { engine.stop(); return false }
            return true
        }, history: history)
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertEqual(history.all.first?.outcome, .unknown)
        XCTAssertEqual(transport.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
    }

    func testStopDuringShopWaitDoesNotConfirmOrReadback() async throws {
        let transport = stub()
        var engine: ListenEngine!
        engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in engine.stop(); return true })
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertFalse(transport.recorded.contains { $0.url.path.contains("order/confirm") || $0.url.path.contains("order/get") })
    }

    func testStopInConfirmCannotPublishSuccessOrFinalReadback() async throws {
        let transport = stub()
        let original = transport.handler!
        var engine: ListenEngine!
        transport.handler = { method, url, headers, body in
            if url.path.contains("order/confirm") { engine.stop() }
            return try original(method, url, headers, body)
        }
        let history = try OrderHistoryStore()
        var successes = 0
        engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in true }, history: history)
        engine.onGrabSuccess = { _ in successes += 1 }
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertEqual(successes, 0)
        XCTAssertFalse(transport.recorded.contains { $0.url.path.contains("order/get") })
        XCTAssertEqual(history.all.first?.outcome, .unknown)
    }

    func testUnknownDoesNotResubmitAfterAnotherRun() async throws {
        let transport = stub()
        let original = transport.handler!
        transport.handler = { method, url, headers, body in
            if url.path.contains("order/confirm") { throw URLError(.timedOut) }
            return try original(method, url, headers, body)
        }
        let history = try OrderHistoryStore()
        var sleeps = 0
        var engine: ListenEngine!
        engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in
            sleeps += 1
            if sleeps > 3 { engine.stop(); return false }
            return true
        }, history: history)
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertEqual(history.all.first?.outcome, .unknown)
        XCTAssertEqual(transport.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
        sleeps = 0
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertEqual(transport.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
    }

    func testLatestConfigAffectsNextRound() async throws {
        let transport = stub()
        let original = transport.handler!
        var polls = 0
        var engine: ListenEngine!
        transport.handler = { method, url, headers, body in
            if url.path.contains("randomList") {
                polls += 1
                if polls == 1 {
                    var cfg = DPGConfig(); cfg.blacklist = ["blocked"]
                    engine.updateConfig(cfg)
                    return (200, plainEnvelope(["code": 0, "list": []]))
                }
                return (200, plainEnvelope(["code": 0, "list": [["id": "o2", "totalAmount": 5000, "userWechatId": "blocked"]]]))
            }
            return try original(method, url, headers, body)
        }
        var sleeps = 0
        engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in
            sleeps += 1; if sleeps > 3 { engine.stop(); return false }; return true
        })
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertFalse(transport.recorded.contains { $0.url.path.contains("order/confirm") })
    }

    func testFilteredFirstOrderDoesNotStarveSecondOrder() async throws {
        let transport = stub()
        let original = transport.handler!
        transport.handler = { method, url, headers, body in
            if url.path.contains("randomList") {
                return (200, plainEnvelope(["code": 0, "list": [["id": "filtered", "totalAmount": 500], ["id": "second", "totalAmount": 2500]]]))
            }
            return try original(method, url, headers, body)
        }
        let history = OrderHistoryStore.inMemory()
        let engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in true }, history: history)
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertEqual(history.all.first?.orderID, "second")
        XCTAssertEqual(history.all.first?.outcome, .success)
    }

    func testSuccessLikeNonzeroCodeOnlyReadsBackOnce() async throws {
        let transport = stub(confirm: ["code": 2, "msg": "确认成功"])
        let history = OrderHistoryStore.inMemory()
        var sleeps = 0
        var engine: ListenEngine!
        engine = ListenEngine(client: client(transport), config: DPGConfig(), log: { _, _ in }, sleeper: { _ in
            sleeps += 1
            if sleeps > 3 { engine.stop(); return false }
            return true
        }, history: history)
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertEqual(history.all.first?.outcome, .unknown)
        XCTAssertEqual(transport.recorded.filter { $0.url.path.contains("order/confirm") }.count, 1)
        XCTAssertEqual(transport.recorded.filter { $0.url.path.contains("order/get") }.count, 1)
    }

    func testRunRestSleepIsCancelledWithoutNewPoll() async throws {
        let transport = stub()
        var cfg = DPGConfig(); cfg.runRestEnabled = true; cfg.runDurationSec = 1
        var ticks: Double = 0
        var engine: ListenEngine!
        engine = ListenEngine(client: client(transport), config: cfg, log: { _, _ in }, monotonic: { ticks += 2; return ticks }, sleeper: { seconds in
            XCTAssertEqual(seconds, cfg.restDurationSec)
            engine.stop(); return false
        })
        XCTAssertTrue(engine.start(phone: "offline", password: "offline"))
        await finish(engine)
        XCTAssertFalse(transport.recorded.contains { $0.url.path.contains("randomList") })
    }
}
