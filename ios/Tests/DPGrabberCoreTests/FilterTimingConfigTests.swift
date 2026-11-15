// DPGrabber iOS - Filter, timing and config unit tests (offline).

import XCTest
@testable import DPGrabberCore

final class FilterTimingConfigTests: XCTestCase {

    // MARK: - OrderFilter

    private func order(_ fields: [String: Any]) -> [String: Any] { fields }

    func testFilterPassesCleanOrder() {
        let filter = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 0, memoKeywords: [], blacklist: [])
        let (passed, reason) = filter.check(order: order(["id": "1", "totalAmount": 2500, "userWechatId": "wx1", "userMemo": ""]))
        XCTAssertTrue(passed)
        XCTAssertEqual(reason, "")
    }

    func testFilterRejectsMissingAmountAndID() {
        let filter = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 0, memoKeywords: [], blacklist: [])
        XCTAssertFalse(filter.check(order: order(["id": "1"])).passed)
        XCTAssertFalse(filter.check(order: order(["totalAmount": 2500])).passed)
    }

    func testFilterBlacklist() {
        let filter = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 0, memoKeywords: [], blacklist: ["badwx"])
        XCTAssertFalse(filter.check(order: order(["id": "1", "totalAmount": 2500, "userWechatId": "badwx"])).passed)
        XCTAssertTrue(filter.check(order: order(["id": "1", "totalAmount": 2500, "userWechatId": "goodwx"])).passed)
        // Empty WeChat id is never blacklisted.
        XCTAssertTrue(filter.check(order: order(["id": "1", "totalAmount": 2500, "userWechatId": ""])).passed)
    }

    func testFilterAmountBounds() {
        let filter = OrderFilter(minAmount: 10, maxAmount: 100, memoMode: 0, memoKeywords: [], blacklist: [])
        XCTAssertFalse(filter.check(order: order(["id": "1", "totalAmount": 999])).passed) // ¥9.99 < ¥10
        XCTAssertFalse(filter.check(order: order(["id": "1", "totalAmount": 10001])).passed) // ¥100.01 > ¥100
        XCTAssertTrue(filter.check(order: order(["id": "1", "totalAmount": 1000])).passed) // ¥10
        XCTAssertTrue(filter.check(order: order(["id": "1", "totalAmount": 10000])).passed) // ¥100
        // maxAmount 0 means no upper limit.
        let unlimited = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 0, memoKeywords: [], blacklist: [])
        XCTAssertTrue(unlimited.check(order: order(["id": "1", "totalAmount": 999999])).passed)
    }

    func testFilterMemoMode() {
        let filter = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 1, memoKeywords: [], blacklist: [])
        XCTAssertFalse(filter.check(order: order(["id": "1", "totalAmount": 2500, "userMemo": "加微信"])).passed)
        XCTAssertTrue(filter.check(order: order(["id": "1", "totalAmount": 2500, "userMemo": ""])).passed)
        let ignored = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 0, memoKeywords: [], blacklist: [])
        XCTAssertTrue(ignored.check(order: order(["id": "1", "totalAmount": 2500, "userMemo": "加微信"])).passed)
    }

    func testFilterMemoKeywordsCaseInsensitive() {
        let filter = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 0, memoKeywords: ["ABC", "加急"], blacklist: [])
        XCTAssertFalse(filter.check(order: order(["id": "1", "totalAmount": 2500, "userMemo": "请 abc 联系"])).passed)
        XCTAssertFalse(filter.check(order: order(["id": "1", "totalAmount": 2500, "userMemo": "很加急的单"])).passed)
        XCTAssertTrue(filter.check(order: order(["id": "1", "totalAmount": 2500, "userMemo": "普通备注"])).passed)
    }

    // MARK: - Confirm logic

    func testClassifyConfirmRequiresConsistentSuccess() {
        XCTAssertEqual(classifyConfirm(["code": 0]).outcome, .ok)
        XCTAssertEqual(classifyConfirm(["code": 0, "msg": "已被抢"]).outcome, .fail)
        XCTAssertEqual(classifyConfirm(["code": 1, "msg": "订单已被其他人确认成功"]).outcome, .fail)
        XCTAssertEqual(classifyConfirm(["code": 1, "msg": "未确认成功"]).outcome, .retry)
    }

    func testClassifyConfirmConfirmedWords() {
        for word in CONFIRMED_WORDS {
            XCTAssertEqual(classifyConfirm(["code": 2, "msg": "订单\(word)了"]).outcome, .verify)
        }
    }

    func testClassifyConfirmTerminalWords() {
        XCTAssertEqual(classifyConfirm(["code": 2, "msg": "订单已被抢"]).outcome, .fail)
        XCTAssertEqual(classifyConfirm(["code": 2, "msg": "订单已被抢"]).reason, "被其他人抢走")
        XCTAssertEqual(classifyConfirm(["code": 2, "msg": "订单已取消"]).reason, "已取消")
        XCTAssertEqual(classifyConfirm(["code": 2, "msg": "订单不存在"]).reason, "订单不存在")
        XCTAssertEqual(classifyConfirm(["code": 2, "msg": "无确认权限"]).reason, "无确认权限")
        XCTAssertEqual(classifyConfirm(["code": 2, "msg": "订单已失效"]).reason, "订单已失效")
        XCTAssertEqual(classifyConfirm(["code": 2, "msg": "确认超时"]).reason, "确认超时")
        XCTAssertEqual(classifyConfirm(["code": 2, "msg": "稍后再试"]).outcome, .retry)
        XCTAssertEqual(classifyConfirm(nil).outcome, .retry)
    }

    func testTerminalReasonFallback() {
        XCTAssertEqual(terminalReason(""), "平台拒绝确认")
        XCTAssertEqual(terminalReason(nil), "平台拒绝确认")
        XCTAssertEqual(terminalReason("奇怪的错误"), "奇怪的错误")
    }

    func testConfirmBudgetSeconds() {
        // createTime present (ms and seconds both count) -> window.
        XCTAssertEqual(confirmBudgetSeconds(order: ["createTime": 1_762_944_000_000], windowSec: 30, fallbackSec: 20), 30)
        XCTAssertEqual(confirmBudgetSeconds(order: ["createTime": 1_762_944_000], windowSec: 30, fallbackSec: 20), 30)
        // No createTime -> fallback.
        XCTAssertEqual(confirmBudgetSeconds(order: [:], windowSec: 30, fallbackSec: 20), 20)
        XCTAssertEqual(confirmBudgetSeconds(order: nil, windowSec: 30, fallbackSec: 20), 20)
        XCTAssertEqual(confirmBudgetSeconds(order: ["createTime": 0], windowSec: 30, fallbackSec: 20), 20)
        // Invalid configured value -> 20.
        XCTAssertEqual(confirmBudgetSeconds(order: ["createTime": 1], windowSec: 0, fallbackSec: 0), 20)
        XCTAssertEqual(confirmBudgetSeconds(order: ["createTime": 1], windowSec: -5, fallbackSec: 20), 20)
    }

    func testReconcileOrder() {
        // status 2/3 -> ok; clerk id must match when both are known.
        XCTAssertEqual(reconcileOrder(["status": 2, "clerkId": 7], ownClerkID: 7).verdict, .ok)
        XCTAssertEqual(reconcileOrder(["status": 3], ownClerkID: 7).verdict, .unknown)
        XCTAssertEqual(reconcileOrder(["status": 2, "clerkId": 99], ownClerkID: 7).verdict, .fail)
        XCTAssertEqual(reconcileOrder(["status": 2, "clerkId": 99], ownClerkID: 7).reason, "被他人接走")
        // A confirmation message without verified ownership is not proof of our success.
        XCTAssertEqual(reconcileOrder(["msg": "已接单"], ownClerkID: 0).verdict, .unknown)
        XCTAssertEqual(reconcileOrder(["status": true, "clerkId": 7], ownClerkID: 7).verdict, .unknown)
        XCTAssertEqual(reconcileOrder(["status": 2.5, "clerkId": 7], ownClerkID: 7).verdict, .unknown)
        // Terminal message or status -1 -> fail.
        XCTAssertEqual(reconcileOrder(["status": -1], ownClerkID: 0).verdict, .fail)
        XCTAssertEqual(reconcileOrder(["msg": "订单已被抢"], ownClerkID: 0).reason, "被其他人抢走")
        // status 0 -> pending; anything else -> unknown.
        XCTAssertEqual(reconcileOrder(["status": 0], ownClerkID: 0).verdict, .pending)
        XCTAssertEqual(reconcileOrder(["status": 9, "msg": "奇怪"], ownClerkID: 0).verdict, .unknown)
        // Payload nested under data.order.
        XCTAssertEqual(
            reconcileOrder(["code": 0, "data": ["order": ["status": 2, "clerkId": 7]]], ownClerkID: 7).verdict,
            .ok
        )
        // Payload nested under order.
        XCTAssertEqual(
            reconcileOrder(["code": 0, "order": ["status": 0]], ownClerkID: 0).verdict,
            .pending
        )
    }

    // MARK: - Config

    func testConfigDefaults() throws {
        let cfg = try DPGConfig().validated()
        XCTAssertEqual(cfg.phone, "")
        XCTAssertEqual(cfg.shopID, 92)
        XCTAssertEqual(cfg.minAmount, 10.0)
        XCTAssertEqual(cfg.maxAmount, 0.0)
        XCTAssertEqual(cfg.pollIntervalSec, 0.6)
        XCTAssertEqual(cfg.randomConfirmWindowSec, 20)
        XCTAssertEqual(cfg.randomConfirmFallbackSec, 20)
        XCTAssertEqual(cfg.confirmRetryDelaySec, 0.5)
        XCTAssertEqual(cfg.aesKeyHex, defaultAESKeyHex)
        XCTAssertEqual(cfg.aesKeyHexAlt, altAESKeyHex)
        XCTAssertTrue(cfg.verifyTLS)
        XCTAssertEqual(cfg.seenTTLSec, 60)
    }

    func testConfigValidationRejectsBadValues() {
        var cfg = DPGConfig()
        cfg.aesKeyHex = "zz"
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.maxAmount = 5
        cfg.minAmount = 10
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.memoMode = 2
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.pollIntervalSec = 0
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.randomConfirmWindowSec = 0
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.randomConfirmWindowSec = 90000
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.randomConfirmFallbackSec = 0.05
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.confirmRetryDelaySec = -1
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.confirmRetryDelaySec = 61
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig()
        cfg.shopID = 0
        XCTAssertThrowsError(try cfg.validated())
    }

    func testConfigConfirmKeysRoundTrip() throws {
        var cfg = DPGConfig()
        cfg.randomConfirmWindowSec = 30
        cfg.randomConfirmFallbackSec = 10
        cfg.confirmRetryDelaySec = 1
        let data = try cfg.jsonData()
        let decoded = try DPGConfig.from(jsonData: data)
        XCTAssertEqual(decoded.randomConfirmWindowSec, 30)
        XCTAssertEqual(decoded.randomConfirmFallbackSec, 10)
        XCTAssertEqual(decoded.confirmRetryDelaySec, 1)
    }

    func testConfigLegacyUserDelayKeyIsIgnored() throws {
        // Old configs may still carry user_delay; it must not break
        // loading and must not come back on save.
        let data = Data("{\"user_delay\": 5}".utf8)
        let cfg = try DPGConfig.from(jsonData: data)
        XCTAssertEqual(cfg.randomConfirmWindowSec, 20)
        let saved = try cfg.jsonData()
        XCTAssertFalse(String(decoding: saved, as: UTF8.self).contains("user_delay"))
    }

    func testConfigJSONRoundTrip() throws {
        var cfg = DPGConfig()
        cfg.phone = "13800000000"
        cfg.minAmount = 20
        cfg.memoKeywords = ["加急", " VIP "]
        cfg.blacklist = ["wx1", " "]
        let data = try cfg.jsonData()
        let decoded = try DPGConfig.from(jsonData: data)
        XCTAssertEqual(decoded.phone, "")
        XCTAssertEqual(decoded.minAmount, 20)
        XCTAssertEqual(decoded.memoKeywords, ["加急", "VIP"])
        XCTAssertEqual(decoded.blacklist, ["wx1"])
    }

    func testConfigMissingKeysTakeDefaults() throws {
        let data = Data("{\"phone\": \"13800000000\"}".utf8)
        let cfg = try DPGConfig.from(jsonData: data)
        XCTAssertEqual(cfg.phone, "")
        XCTAssertEqual(cfg.shopID, 92)
        XCTAssertEqual(cfg.aesKeyHex, defaultAESKeyHex)
    }

    func testConfigSaveLoadRoundTripInTempDir() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("dpgrabber-config-test-\(UUID().uuidString)")
        let url = dir.appendingPathComponent("config.json")
        var cfg = DPGConfig()
        cfg.phone = "13800000000"
        cfg.password = "secret"
        try saveConfig(cfg, to: url)
        let loaded = try loadConfig(from: url)
        XCTAssertEqual(loaded.phone, "")
        XCTAssertEqual(loaded.password, "")
        let text = String(decoding: try Data(contentsOf: url), as: UTF8.self)
        XCTAssertFalse(text.contains("secret"))
        XCTAssertFalse(text.contains("password"))
        XCTAssertFalse(text.contains("13800000000"))
        try FileManager.default.removeItem(at: dir)
    }

    func testWaitUsesActualSIDAndTimestampUnits() {
        let cfg = DPGConfig()
        let now: Double = 1_800_000_000
        XCTAssertEqual(orderWaitSeconds(order: [:], sid: "92", config: cfg, now: now), 5)
        XCTAssertEqual(orderWaitSeconds(order: ["createTime": now - 2], sid: "92", config: cfg, now: now), 3)
        XCTAssertEqual(orderWaitSeconds(order: ["createTime": (now - 2) * 1000], sid: "50", config: cfg, now: now), 1)
        XCTAssertEqual(orderWaitSeconds(order: ["createTime": now + 100], sid: "105", config: cfg, now: now), 3)
        XCTAssertEqual(orderWaitSeconds(order: ["createTime": now - 100], sid: "1391", config: cfg, now: now), 0)
        XCTAssertEqual(orderWaitSeconds(order: [:], sid: "unknown", config: cfg, now: now), cfg.globalWaitSec)
        XCTAssertEqual(normalizeMs(Double.nan), 0)
        XCTAssertEqual(normalizeMs(Double.infinity), 0)
        XCTAssertEqual(reconcileOrder([:], ownClerkID: 1).verdict, .unknown)
    }

    func testHistoryPersistenceIdempotencyAndShanghaiDay() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("history.json")
        let store = try OrderHistoryStore(url: url)
        let time = Date(timeIntervalSince1970: 1_800_000_000)
        var row = OrderHistoryRecord(id: "run:attempt", accountID: "a", shopID: "92", orderID: "o", runID: "run", attemptID: "attempt", timestamp: time, amount: 10, wechatID: "wx", memo: "", outcome: .unknown, reason: "offline")
        try store.put(row)
        try store.put(row)
        XCTAssertEqual(store.all.count, 1)
        XCTAssertTrue(store.blocksConfirmation(accountID: "a", shopID: "92", orderID: "o"))
        row.outcome = .success
        try store.put(row)
        let loaded = try OrderHistoryStore(url: url)
        XCTAssertEqual(loaded.all.count, 1)
        XCTAssertEqual(loaded.all.first?.outcome, .success)
        XCTAssertEqual(loaded.today(now: time).count, 1)
        XCTAssertEqual(loaded.today(now: time.addingTimeInterval(86400)).count, 0)
    }

    func testLegacyCredentialFieldsAreErasedOnLoad() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("config.json")
        try Data("{\"phone\":\"offline\",\"password\":\"old-secret\",\"verify_tls\":false}".utf8).write(to: url)
        let loaded = try loadConfig(from: url)
        XCTAssertTrue(loaded.phone.isEmpty)
        XCTAssertTrue(loaded.password.isEmpty)
        XCTAssertTrue(loaded.verifyTLS)
        let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        XCTAssertNil(raw?["phone"])
        XCTAssertNil(raw?["password"])
    }

    func testStoreAndWeeklyStatisticsKeepAccountAndMetricBoundaries() throws {
        let store = OrderHistoryStore.inMemory()
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        try store.put(OrderHistoryRecord(id: "a:1", accountID: "a", shopID: "92", orderID: "1", runID: "r", attemptID: "1", timestamp: now, amount: 10, wechatID: "", memo: "", outcome: .success, reason: ""))
        try store.put(OrderHistoryRecord(id: "b:1", accountID: "b", shopID: "92", orderID: "2", runID: "r", attemptID: "2", timestamp: now, amount: 10, wechatID: "", memo: "", outcome: .unknown, reason: ""))
        XCTAssertEqual(store.entries(accountID: "a").count, 1)
        XCTAssertEqual(store.todayCounts(accountID: "a", now: now)[.success], 1)
        XCTAssertNil(store.todayCounts(accountID: "a", now: now)[.unknown])
        XCTAssertFalse(store.blocksConfirmation(accountID: "a", shopID: "92", orderID: "2"))
        let stats = WeeklyStatistics(raw: ["orderCount": 8, "continuedCount": 2, "continuedAmount": 6000, "orderAmount": 25000, "continuedUserCount": 1])
        XCTAssertEqual(stats.orderCount, 8)
        XCTAssertEqual(stats.feeRate ?? -1, 0.24, accuracy: 0.0001)
        XCTAssertEqual(stats.orderRate ?? -1, 0.25, accuracy: 0.0001)
        XCTAssertEqual(stats.continuedUserCount, 1)
        let empty = WeeklyStatistics(raw: [:], fallbackCount: 12)
        XCTAssertEqual(empty.orderCount, 12)
        XCTAssertNil(empty.feeRate)
        XCTAssertNil(empty.orderRate)
        XCTAssertTrue(empty.isCountFallback)
    }

    func testShopWaitTableRoundTripAndValidation() throws {
        var cfg = DPGConfig()
        cfg.confirmWaitByShop = ["92": 4.5, "105": 0]
        let decoded = try DPGConfig.from(jsonData: cfg.jsonData())
        XCTAssertEqual(decoded.confirmWaitByShop["92"], 4.5)
        XCTAssertEqual(orderWaitSeconds(order: [:], sid: "105", config: decoded, now: 100), 0)
        cfg.confirmWaitByShop = ["invalid": 3]
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig(); cfg.requestTimeoutSec = .infinity
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig(); cfg.minAmount = .infinity
        XCTAssertThrowsError(try cfg.validated())
        cfg = DPGConfig(); cfg.seenTTLSec = .nan
        XCTAssertThrowsError(try cfg.validated())
    }

    func testPlatformWeekStartsMondayInShanghai() throws {
        let formatter = ISO8601DateFormatter()
        let now = try XCTUnwrap(formatter.date(from: "2026-11-15T01:00:00Z"))
        let week = PlatformWeek(now: now)
        XCTAssertEqual(week.startDate, "2026-11-09")
        XCTAssertEqual(week.endDate, "2026-11-15")
        XCTAssertEqual(week.startDateTime, "2026-11-09 00:00:00")
        XCTAssertEqual(week.endDateTime, "2026-11-15 23:59:59")
    }

    func testQualificationAndGoneMessagesAreTerminal() {
        for message in ["当前续费率24%，需要达标续费率30%", "本周订单已达上限", "订单跑走了", "订单跑掉了"] {
            XCTAssertEqual(classifyConfirm(["code": 1, "msg": message]).outcome, .fail)
        }
        XCTAssertNil(parseCode(["code": Double.infinity]))
        XCTAssertNil(parseCode(["code": "nan"]))
    }

    func testMaskPhone() {
        XCTAssertEqual(maskPhone("13800000000"), "138****0000")
        XCTAssertEqual(maskPhone("123"), "***")
    }
}
