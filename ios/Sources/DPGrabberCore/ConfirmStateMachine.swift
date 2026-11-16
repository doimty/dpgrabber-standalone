// DPGrabber iOS - Confirm task (DPGConfirmationTask, DPGEngine.m).
// Pure helpers, shared semantics with the PC / Android engines.
//
// A passing order waits for its configured shop delay, then uses one bounded
// confirmation window. Explicit retry responses may retry inside that window;
// ambiguous transport outcomes get one readback and are never blindly resubmitted.

import Foundation

/// A confirm reply whose message contains one of these means success.
public let CONFIRMED_WORDS = ["已接单", "已确认", "接单成功", "确认成功"]
public let BUSY_WORDS = ["同时接单数超过限制", "接单数超过限制", "超过限制的"]

public func busyCapFrom(_ message: String) -> Int? {
    guard let regex = try? NSRegularExpression(pattern: "超过限制的\\s*(\\d+)\\s*单"),
          let match = regex.firstMatch(in: message, range: NSRange(message.startIndex..., in: message)),
          let range = Range(match.range(at: 1), in: message),
          let cap = Int(message[range]), cap > 0 else { return nil }
    return cap
}

public func busyReason(_ message: String) -> String {
    if let cap = busyCapFrom(message) { return "同时接单名额已满（上限\(cap)单），本次跳过" }
    return "同时接单名额已满，本次跳过"
}

/// A confirm reply whose message contains one of these is terminally failed.
public let QUALIFICATION_WORDS = ["续费率", "达标", "资质", "资格", "额度", "次数不足", "上限", "本周订单"]
public let TERMINAL_WORDS = ["取消", "已被", "不存在", "无权", "权限", "失效", "超时", "跑走", "跑掉"] + QUALIFICATION_WORDS

public func orderWaitSeconds(order: [String: Any], sid: String, config: DPGConfig, now: TimeInterval) -> TimeInterval {
    let base = config.useShopWait ? (config.confirmWaitByShop[sid] ?? config.globalWaitSec) : config.globalWaitSec
    let offset = ["50", "105", "1391"].contains(sid) ? config.autoWaitOffset : 0
    let wait = max(0, base + offset)
    let created = Double(normalizeMs(order["createTime"])) / 1000
    let elapsed = created > 0 ? max(0, now - created) : 0
    return max(0, wait - elapsed)
}

/// Human-readable reason for a terminal confirm failure (DPGTerminalReason).
public func terminalReason(_ message: String?) -> String {
    let text = message ?? ""
    if QUALIFICATION_WORDS.contains(where: { text.contains($0) }) { return text }
    for word in ["已被", "被其他", "他人抢", "别人抢", "抢走", "跑走", "跑掉"] {
        if text.contains(word) { return "被其他人抢走" }
    }
    if text.contains("取消") { return "已取消" }
    if text.contains("不存在") { return "订单不存在" }
    if text.contains("无权") || text.contains("权限") { return "无确认权限" }
    if text.contains("失效") { return "订单已失效" }
    if text.contains("超时") { return "确认超时" }
    return text.isEmpty ? "平台拒绝确认" : text
}

/// Outcome of classifying one confirm response.
public enum ConfirmOutcome: Equatable {
    /// code 0 or a confirmed message.
    case ok
    /// 接单名额满：跳过本单，不再确认或回读。
    case busy
    /// A terminal message; `reason` explains it.
    case fail
    /// A non-success code with success-like wording requires one ownership readback, never a blind resubmit.
    case verify
    /// Anything else: retry until the deadline.
    case retry
}

public struct ConfirmClassification: Equatable {
    public let outcome: ConfirmOutcome
    public let reason: String

    public init(outcome: ConfirmOutcome, reason: String) {
        self.outcome = outcome
        self.reason = reason
    }
}

/// 满额与终态拒绝优先于 code 0；非零成功文案仍须只读核验。
public func classifyConfirm(_ result: [String: Any]?) -> ConfirmClassification {
    guard let result else { return ConfirmClassification(outcome: .retry, reason: "") }
    let code = parseCode(result)
    let message = responseMessage(result)
    if BUSY_WORDS.contains(where: { message.contains($0) }) {
        return ConfirmClassification(outcome: .busy, reason: busyReason(message))
    }
    if TERMINAL_WORDS.contains(where: { message.contains($0) }) {
        return ConfirmClassification(outcome: .fail, reason: terminalReason(message))
    }
    let negative = ["未确认成功", "未接单", "未成功", "没有确认成功", "没有接单成功", "尚未确认", "尚未接单"].contains { message.contains($0) }
    if code == 0 && !negative { return ConfirmClassification(outcome: .ok, reason: "") }
    if !negative, CONFIRMED_WORDS.contains(where: { message.contains($0) }) {
        return ConfirmClassification(outcome: .verify, reason: "返回码未确认成功，需回查归属")
    }
    return ConfirmClassification(outcome: .retry, reason: message)
}

/// Confirm window budget in seconds (deadlineForOrderAtWall, random
/// kind): the window when the order has a createTime, the fallback
/// window when it does not; 20 s when the configured value is invalid.
public func confirmBudgetSeconds(order: [String: Any]?, windowSec: Double, fallbackSec: Double) -> Double {
    let createMs = normalizeMs(order?["createTime"])
    let budget = createMs > 0 ? windowSec : fallbackSec
    if !budget.isFinite || budget <= 0 { return 20.0 }
    return budget
}

/// Verdict of one order/get verification.
public enum ReconcileVerdict: Equatable {
    /// Confirmed (status 2/3 or a confirmed message).
    case ok
    /// Terminally failed.
    case fail
    /// Still pending (status 0).
    case pending
    /// Cannot tell; stop blind retry.
    case unknown
}

public struct ReconcileResult: Equatable {
    public let verdict: ReconcileVerdict
    public let reason: String

    public init(verdict: ReconcileVerdict, reason: String) {
        self.verdict = verdict
        self.reason = reason
    }
}

private func reconcileStatus(_ value: Any?) -> Int? {
    if let n = value as? NSNumber {
        let number = n.doubleValue
        guard String(cString: n.objCType) != "c", number.isFinite, number.rounded() == number,
              number >= Double(Int.min), number < Double(Int.max) else { return nil }
        return n.intValue
    }
    if let s = value as? String {
        let trimmed = s.trimmingCharacters(in: .whitespaces)
        if let i = Int(trimmed) { return i }
        if let d = Double(trimmed), d.isFinite, d == d.rounded(), d >= Double(Int.min), d < Double(Int.max) { return Int(d) }
    }
    return nil
}

/// Verify a confirm result with one order/get response
/// (reconcileWithSession, DPGEngine.m).
///
/// The payload is the first dict among response["order"],
/// response["data"]["order"], response["data"] and the response itself
/// that carries a "status" or "msg" field.
public func reconcileOrder(_ response: [String: Any]?, ownClerkID: Int) -> ReconcileResult {
    var payload: [String: Any]? = nil
    if let response {
        var candidates: [[String: Any]?] = []
        candidates.append(response["order"] as? [String: Any])
        if let data = response["data"] as? [String: Any] {
            candidates.append(data["order"] as? [String: Any])
            candidates.append(data)
        }
        candidates.append(response)
        for cand in candidates {
            if let cand, cand["status"] != nil || cand["msg"] != nil {
                payload = cand
                break
            }
        }
        if payload == nil { payload = response }
    }
    let payloadDict = payload ?? [:]
    var message = (payloadDict["msg"] as? String) ?? (payloadDict["message"] as? String) ?? ""
    if message.isEmpty {
        message = (response?["msg"] as? String) ?? ""
    }
    let status = reconcileStatus(payloadDict["status"])
    if TERMINAL_WORDS.contains(where: { message.contains($0) }) || status == -1 {
        return ReconcileResult(verdict: .fail, reason: terminalReason(message))
    }
    let negative = ["未确认成功", "未接单", "未成功", "没有确认成功", "尚未确认", "尚未接单"].contains { message.contains($0) }
    if negative { return ReconcileResult(verdict: .unknown, reason: "订单状态与文案未能确认成功") }
    let confirmed = CONFIRMED_WORDS.contains(where: { message.contains($0) }) || status == 2 || status == 3
    if confirmed {
        let clerkID = reconcileStatus(payloadDict["clerkId"]) ?? 0
        guard ownClerkID > 0, clerkID > 0 else {
            return ReconcileResult(verdict: .unknown, reason: "订单已接单，但无法核实归属")
        }
        if clerkID != ownClerkID {
            return ReconcileResult(verdict: .fail, reason: "被他人接走")
        }
        return ReconcileResult(verdict: .ok, reason: "")
    }
    if TERMINAL_WORDS.contains(where: { message.contains($0) }) || status == -1 {
        return ReconcileResult(verdict: .fail, reason: terminalReason(message))
    }
    if status == 0 { return ReconcileResult(verdict: .pending, reason: "") }
    return ReconcileResult(verdict: .unknown, reason: message)
}
