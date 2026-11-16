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

    func testItemAccountSHA256IsIndependentFromLegacyHistoryKey() throws {
        XCTAssertEqual(accountKeyForItemSwitch("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(accountKeyForItemSwitch(String(repeating: "a", count: 64)), "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb")
        XCTAssertEqual(accountKeyForItemSwitch("账号测试"), "cbce8c754af35883cd1134d6be017136415c10f2516ce658d2f7890b91baa0a0")
        XCTAssertEqual(accountKeyForItemSwitch(" offline \n"), "8e2c7ac508139a02af859de64a4743c1f3946837279332c35ec8f5ddf20654ae")
        XCTAssertEqual(accountKeyForItemSwitch(" \n"), "")
        XCTAssertEqual(loginPasswordDigest("offline").count, 32)
        var cfg = DPGConfig()
        cfg.itemSwitchIds = [465]
        cfg.itemSwitchShopId = "92"
        cfg.itemSwitchAccountKey = accountKeyForItemSwitch("offline")
        XCTAssertNoThrow(try cfg.validated())
        XCTAssertEqual(cfg.itemSwitchScope(accountKey: accountKeyForItemSwitch(" offline "), shopKey: "92"), [465])
        XCTAssertEqual(cfg.itemSwitchScope(accountKey: loginPasswordDigest("offline"), shopKey: "92"), [])
    }

    func testItemSelectionRejectsIncompleteOrContradictoryResponses() throws {
        let valid: [String: Any] = ["data": ["itemSwitchList": [["id": 465, "name": "语音"], ["id": "2666", "name": "视频"]],
                                          "clerkItemSwitchIds": [465, "2666", 465]]]
        let parsed = try parseItemSwitchSelection(valid)
        XCTAssertEqual(parsed.options.map { $0.id }, [465, 2666])
        XCTAssertEqual(parsed.enabledIDs, [465, 2666])
        let invalidResponses: [[String: Any]] = [
            [:], ["itemSwitchList": []],
            ["itemSwitchList": [["id": 465], ["id": 465]], "clerkItemSwitchIds": [465]],
            ["itemSwitchList": [["id": true]], "clerkItemSwitchIds": []],
            ["itemSwitchList": [["id": 465]], "clerkItemSwitchIds": [2666]],
            ["itemSwitchList": [["id": 465]], "clerkItemSwitchIds": [true]]
        ]
        for invalid in invalidResponses {
            XCTAssertThrowsError(try parseItemSwitchSelection(invalid))
        }
        XCTAssertTrue(try parseItemSwitchSelection(["itemSwitchList": [], "clerkItemSwitchIds": []]).enabledIDs.isEmpty)
    }

    func testEmptyMessageDoesNotHideBusyOrTerminalStatus() {
        XCTAssertEqual(classifyConfirm(["code": 0, "msg": "", "message": "同时接单数超过限制的3单"]).outcome, .busy)
        XCTAssertEqual(classifyConfirm(["code": 0, "msg": " ", "message": "无确认权限"]).outcome, .fail)
    }

    func testBusyClassificationPrecedesSuccessAndTerminalWords() {
        for code in [0, 2] {
            let result = classifyConfirm(["code": code, "msg": "同时接单数超过限制的 3 单，已达上限"])
            XCTAssertEqual(result.outcome, .busy)
            XCTAssertEqual(result.reason, "同时接单名额已满（上限3单），本次跳过")
        }
        XCTAssertEqual(classifyConfirm(["code": 0, "message": "接单数超过限制"]).outcome, .busy)
        XCTAssertEqual(classifyConfirm(["code": 0, "msg": " ", "message": "接单数超过限制"]).outcome, .busy)
        XCTAssertEqual(busyCapFrom("超过限制的 12 单"), 12)
        XCTAssertNil(busyCapFrom("超过限制的0单"))
        XCTAssertNil(busyCapFrom("超过限制的999999999999999999999999单"))
        XCTAssertNil(busyCapFrom("其他信息"))
        XCTAssertEqual(busyReason("接单数超过限制"), "同时接单名额已满，本次跳过")
    }

    func testWaitOffsetOnlyAppliesToSelectedShops() {
        var cfg = DPGConfig()
        cfg.autoWaitOffset = 0.3
        let now = 1_800_000_000.0
        for sid in ["50", "105", "1391"] {
            XCTAssertEqual(orderWaitSeconds(order: [:], sid: sid, config: cfg, now: now), 3.3, accuracy: 0.0001)
            for created in [now - 2, (now - 2) * 1000] {
                XCTAssertEqual(orderWaitSeconds(order: ["createTime": created], sid: sid, config: cfg, now: now), 1.3, accuracy: 0.0001)
            }
        }
        XCTAssertEqual(orderWaitSeconds(order: [:], sid: "92", config: cfg, now: now), 5)
        XCTAssertEqual(orderWaitSeconds(order: [:], sid: "999", config: cfg, now: now), 3)
        cfg.useShopWait = false
        cfg.globalWaitSec = 1
        XCTAssertEqual(orderWaitSeconds(order: [:], sid: "50", config: cfg, now: now), 1.3, accuracy: 0.0001)
        cfg.autoWaitOffset = -0.5
        cfg.globalWaitSec = 0.1
        XCTAssertEqual(orderWaitSeconds(order: [:], sid: "50", config: cfg, now: now), 0)
    }

    func testNewConfigKeysRoundTripAndDefaults() throws {
        let defaults = try DPGConfig.from(jsonData: Data("{}".utf8))
        XCTAssertEqual(defaults.busyCooldownSec, 60)
        XCTAssertEqual(defaults.autoWaitOffset, 0)
        XCTAssertEqual(defaults.itemSwitchIds, [])
        XCTAssertEqual(defaults.itemSwitchAccountKey, "")
        XCTAssertEqual(defaults.itemSwitchShopId, "")
        var cfg = DPGConfig()
        cfg.busyCooldownSec = 125
        cfg.autoWaitOffset = -0.125
        cfg.itemSwitchIds = [465, 2666]
        cfg.itemSwitchAccountKey = String(repeating: "a", count: 64)
        cfg.itemSwitchShopId = "50"
        let data = try cfg.jsonData()
        let raw = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for key in ["busy_cooldown_sec", "auto_wait_offset", "item_switch_ids", "item_switch_account_key", "item_switch_shop_id"] {
            XCTAssertNotNil(raw[key])
        }
        XCTAssertEqual(try DPGConfig.from(jsonData: data), cfg)
    }

    func testNewConfigValidationRejectsInvalidValues() {
        for offset in [-0.501, 0.501, 0.0001, Double.nan, Double.infinity] {
            var cfg = DPGConfig(); cfg.autoWaitOffset = offset
            XCTAssertThrowsError(try cfg.validated()) { XCTAssertTrue($0 is ConfigError) }
        }
        for offset in [-0.5, 0.5, 0.001, 0.145] {
            var cfg = DPGConfig(); cfg.autoWaitOffset = offset
            XCTAssertNoThrow(try cfg.validated())
        }
        for seconds in [0, 601] {
            var cfg = DPGConfig(); cfg.busyCooldownSec = seconds
            XCTAssertThrowsError(try cfg.validated())
        }
        for ids in [[0], [-1], [465, 0]] {
            var cfg = DPGConfig(); cfg.itemSwitchIds = ids
            XCTAssertThrowsError(try cfg.validated())
        }
        for key in ["account", String(repeating: "A", count: 64), String(repeating: "a", count: 32), String(repeating: "a", count: 63)] {
            var cfg = DPGConfig(); cfg.itemSwitchAccountKey = key
            XCTAssertThrowsError(try cfg.validated())
        }
        for sid in ["0", "-1", "92.5", " 92", "+92"] {
            var cfg = DPGConfig(); cfg.itemSwitchShopId = sid
            XCTAssertThrowsError(try cfg.validated())
        }
        let invalidInputs: [[String: Any]] = [["item_switch_ids": [465, true]], ["item_switch_ids": [465, 1.5]],
                                              ["item_switch_ids": "465"], ["busy_cooldown_sec": true], ["busy_cooldown_sec": 60.5]]
        for raw in invalidInputs {
            XCTAssertThrowsError(try DPGConfig.validated(from: raw))
        }
    }

    func testOrderItemIDsUseStrictNumbersBeforeNameFallback() {
        for key in ["itemId", "itemSwitchId", "itemCategoryId", "typeId", "itemType", "serviceItemId", "categoryId"] {
            XCTAssertEqual(orderItemIds([key: 465]), Set([465]))
            XCTAssertEqual(orderItemIds([key: " 2666 "]), Set([2666]))
        }
        XCTAssertEqual(orderItemIds(["itemId": [465, "2666"], "itemName": "游戏"]), Set([465, 2666]))
        let invalidIDs: [Any] = [true, NSNumber(value: true), Double(465), Float(465), 465.5, NSNumber(value: 465.5), "465.0", "-1", "0", "999999999999999999999999", UInt64.max]
        for value in invalidIDs {
            XCTAssertTrue(orderItemIds(["itemId": value]).isEmpty)
        }
        XCTAssertEqual(orderItemIds(["itemId": true, "typeId": 2667]), Set([2667]))
        XCTAssertEqual(orderItemIds(["items": [["name": "语音通话"], ["itemName": "视频游戏"]]]), Set([465, 2666, 2667]))
        for key in ["itemNames", "item_names", "itemName", "item_name", "serviceNames", "serviceName", "itemCategoryName"] {
            XCTAssertEqual(orderItemIds([key: "连麦"]), Set([465]))
        }
        XCTAssertTrue(orderItemIds(["itemName": "未知类型"]).isEmpty)
    }

    func testEnabledItemFilterAndScope() {
        let filter = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 0, memoKeywords: [], blacklist: [], enabledItems: [465])
        XCTAssertTrue(filter.check(order: ["id": "1", "totalAmount": 2500, "itemId": 465]).passed)
        let rejected = filter.check(order: ["id": "1", "totalAmount": 2500, "itemId": 2666])
        XCTAssertFalse(rejected.passed)
        XCTAssertEqual(rejected.reason, "订单类型未启用")
        XCTAssertTrue(filter.check(order: ["id": "1", "totalAmount": 2500, "itemName": "未知"]).passed)
        let unrestricted = OrderFilter(minAmount: 10, maxAmount: 0, memoMode: 0, memoKeywords: [], blacklist: [])
        XCTAssertTrue(unrestricted.check(order: ["id": "1", "totalAmount": 2500, "itemId": 2666]).passed)
        var cfg = DPGConfig()
        cfg.itemSwitchIds = [465]
        XCTAssertEqual(cfg.itemSwitchScope(accountKey: "", shopKey: ""), [])
        cfg.itemSwitchAccountKey = String(repeating: "a", count: 64)
        cfg.itemSwitchShopId = "92"
        XCTAssertEqual(cfg.itemSwitchScope(accountKey: cfg.itemSwitchAccountKey, shopKey: "92"), [465])
        XCTAssertEqual(cfg.itemSwitchScope(accountKey: "other", shopKey: "92"), [])
        XCTAssertEqual(cfg.itemSwitchScope(accountKey: cfg.itemSwitchAccountKey, shopKey: "50"), [])
    }

    func testMaskPhone() {
        XCTAssertEqual(maskPhone("13800000000"), "138****0000")
        XCTAssertEqual(maskPhone("123"), "***")
    }
}
