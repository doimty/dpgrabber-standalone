// DPGrabber iOS - Order filter (PROTOCOL.md §7.1).
//
// Checks run in the reference tool's order; the first failure drops the
// order. Only the first order of each poll is evaluated (see ListenEngine).

import Foundation

/// The §7.1 filter chain. `check(order:)` returns whether the order
/// passes and a short human-readable reason when it does not.
public struct OrderFilter {
    public let minAmount: Double
    public let maxAmount: Double
    /// 0 = ignore memos, 1 = drop orders that have any memo.
    public let memoMode: Int
    public let memoKeywords: [String]
    public let blacklist: Set<String>

    public init(
        minAmount: Double,
        maxAmount: Double,
        memoMode: Int,
        memoKeywords: [String],
        blacklist: [String]
    ) {
        self.minAmount = minAmount
        self.maxAmount = maxAmount
        self.memoMode = memoMode
        self.memoKeywords = memoKeywords.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        self.blacklist = Set(blacklist.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
    }

    public func check(order: [String: Any]) -> (passed: Bool, reason: String) {
        guard order["totalAmount"] != nil, !(order["totalAmount"] is NSNull) else {
            return (false, "订单缺少金额字段")
        }
        guard !orderID(of: order).isEmpty else {
            return (false, "订单缺少 id 字段")
        }
        let amount = amountYuan(fromCents: order["totalAmount"])
        guard amount.isFinite, amount >= 0 else { return (false, "订单金额无效") }
        let wechat = (order["userWechatId"] as? String) ?? ""
        if !wechat.isEmpty, blacklist.contains(wechat) {
            return (false, "客户在黑名单中（标识已脱敏）")
        }
        if amount < minAmount {
            return (false, String(format: "金额 %.2f 元低于最低金额", amount))
        }
        if maxAmount > 0, amount > maxAmount {
            return (false, String(format: "金额 %.2f 元高于最高金额", amount))
        }
        let memo = (order["userMemo"] as? String) ?? ""
        if memoMode == 1, !memo.isEmpty {
            return (false, "订单有备注，不符合要求")
        }
        if !memo.isEmpty, !memoKeywords.isEmpty {
            let lowered = memo.lowercased()
            for keyword in memoKeywords where lowered.contains(keyword.lowercased()) {
                return (false, "订单备注命中屏蔽关键词")
            }
        }
        return (true, "")
    }
}
