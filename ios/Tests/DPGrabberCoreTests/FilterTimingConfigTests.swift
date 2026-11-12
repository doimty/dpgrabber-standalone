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

    // MARK: - Grab timing

    func testGrabWaitOK() {
        // reTime 2026-11-12 12:00:00 UTC = 1762944000000 ms; countdown 30 s.
        let order: [String: Any] = ["reTime": 1_762_944_000_000, "confirmCountDown": 30, "createTime": 1_762_943_900_000]
        let wait = computeGrabWait(order: order, nowEpochSec: 1_762_944_000.0)
        XCTAssertEqual(wait.reason, .ok)
        XCTAssertEqual(wait.waitSeconds, 30.0, accuracy: 0.001)
        XCTAssertEqual(wait.grabEpochMs, 1_762_944_030_000)
    }

    func testGrabWaitAlreadyOpen() {
        let order: [String: Any] = ["reTime": 1_762_944_000_000, "confirmCountDown": 5]
        let wait = computeGrabWait(order: order, nowEpochSec: 1_762_944_010.0)
        XCTAssertEqual(wait.reason, .alreadyOpen)
        XCTAssertEqual(wait.waitSeconds, 0)
    }

    func testGrabWaitMissingFields() {
        XCTAssertEqual(computeGrabWait(order: ["createTime": 1_762_944_000_000], nowEpochSec: 1_762_944_000.0).reason, .missingFields)
        XCTAssertEqual(computeGrabWait(order: ["reTime": 1_762_944_000_000], nowEpochSec: 1_762_944_000.0).reason, .missingFields)
        XCTAssertEqual(computeGrabWait(order: ["reTime": 0, "confirmCountDown": 5], nowEpochSec: 0).reason, .missingFields)
    }

    func testGrabWaitIgnoresCreateTime() {
        // Same reTime/countdown, wildly different createTime -> same wait.
        let a = computeGrabWait(order: ["reTime": 1_762_944_000_000, "confirmCountDown": 30, "createTime": 1], nowEpochSec: 1_762_944_000.0)
        let b = computeGrabWait(order: ["reTime": 1_762_944_000_000, "confirmCountDown": 30, "createTime": 1_762_944_000_000], nowEpochSec: 1_762_944_000.0)
        XCTAssertEqual(a, b)
    }

    func testGrabWaitSecondsAsStrings() {
        let order: [String: Any] = ["reTime": "1762944000000", "confirmCountDown": "10"]
        let wait = computeGrabWait(order: order, nowEpochSec: 1_762_944_000.0)
        XCTAssertEqual(wait.reason, .ok)
        XCTAssertEqual(wait.waitSeconds, 10.0, accuracy: 0.001)
    }

    // MARK: - Config

    func testConfigDefaults() throws {
        let cfg = try DPGConfig().validated()
        XCTAssertEqual(cfg.phone, "")
        XCTAssertEqual(cfg.shopID, 92)
        XCTAssertEqual(cfg.minAmount, 10.0)
        XCTAssertEqual(cfg.maxAmount, 0.0)
        XCTAssertEqual(cfg.pollIntervalSec, 0.5)
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
        cfg.shopID = 0
        XCTAssertThrowsError(try cfg.validated())
    }

    func testConfigJSONRoundTrip() throws {
        var cfg = DPGConfig()
        cfg.phone = "13800000000"
        cfg.minAmount = 20
        cfg.memoKeywords = ["加急", " VIP "]
        cfg.blacklist = ["wx1", " "]
        let data = try cfg.jsonData()
        let decoded = try DPGConfig.from(jsonData: data)
        XCTAssertEqual(decoded.phone, "13800000000")
        XCTAssertEqual(decoded.minAmount, 20)
        XCTAssertEqual(decoded.memoKeywords, ["加急", "VIP"])
        XCTAssertEqual(decoded.blacklist, ["wx1"])
    }

    func testConfigMissingKeysTakeDefaults() throws {
        let data = Data("{\"phone\": \"13800000000\"}".utf8)
        let cfg = try DPGConfig.from(jsonData: data)
        XCTAssertEqual(cfg.phone, "13800000000")
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
        XCTAssertEqual(loaded.phone, "13800000000")
        XCTAssertEqual(loaded.password, "secret")
        try FileManager.default.removeItem(at: dir)
    }

    func testMaskPhone() {
        XCTAssertEqual(maskPhone("13800000000"), "138****0000")
        XCTAssertEqual(maskPhone("123"), "***")
    }
}
