// DPGrabber iOS - Order filter (PROTOCOL.md §7.1).
//
// Checks run in the reference tool's order; the first failure drops the
// order. Polling scans past filtered items and confirms at most one candidate.

import Foundation
import CoreFoundation

/// 严格解析正整数；不把布尔、浮点数、小数文本或溢出值当作类型 ID。
func positiveItemID(_ value: Any?) -> Int? {
    guard let value else { return nil }
    if type(of: value) == Double.self || type(of: value) == Float.self { return nil }
    if let number = value as? NSNumber {
        guard CFGetTypeID(number) != CFBooleanGetTypeID(),
              !["f", "d"].contains(String(cString: number.objCType)),
              let id = Int(number.stringValue), id > 0 else { return nil }
        return id
    }
    if let text = value as? String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.allSatisfy({ (48...57).contains($0) }),
              let id = Int(trimmed), id > 0 else { return nil }
        return id
    }
    return nil
}

public func orderItemIds(_ order: [String: Any]) -> Set<Int> {
    var ids = Set<Int>()
    func addID(_ value: Any?) {
        if let values = value as? [Any] { values.forEach { addID($0) } }
        else if let id = positiveItemID(value) { ids.insert(id) }
    }
    for key in ["itemId", "itemSwitchId", "itemCategoryId", "typeId", "itemType", "serviceItemId", "categoryId"] {
        addID(order[key])
    }
    if !ids.isEmpty { return ids }
    func addName(_ value: Any?) {
        if let values = value as? [Any] { values.forEach { addName($0) } }
        else if let object = value as? [String: Any] {
            addName(object["name"])
            addName(object["itemName"])
        } else if let text = value as? String {
            let name = text.lowercased()
            // 特殊店铺（如文字语音条）的归属尚未核验；只映射已知名称，未知类型不猜测。
            if ["连麦", "语音通话", "语音"].contains(where: { name.contains($0) }) { ids.insert(465) }
            if name.contains("视频") { ids.insert(2666) }
            if name.contains("游戏") { ids.insert(2667) }
        }
    }
    for key in ["itemNames", "item_names", "itemName", "item_name", "items", "serviceNames", "serviceName", "itemCategoryName"] {
        addName(order[key])
    }
    return ids
}

/// 类型列表必须完整合法后才允许覆盖当前账号、店铺的有效缓存。
public func parseItemSwitchSelection(_ response: [String: Any]) throws -> (options: [ItemSwitchOption], enabledIDs: [Int]) {
    let data = response["data"] as? [String: Any] ?? response
    guard let items = data["itemSwitchList"] as? [[String: Any]],
          let enabled = data["clerkItemSwitchIds"] as? [Any] else {
        throw ApiError(.api, "接单类型数据缺失")
    }
    var options: [ItemSwitchOption] = []
    var validIDs = Set<Int>()
    for item in items {
        guard let id = positiveItemID(item["id"]), validIDs.insert(id).inserted else {
            throw ApiError(.api, "接单类型列表格式无效")
        }
        let name = (item["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        options.append(ItemSwitchOption(id: id, name: name.isEmpty ? "类型 \(id)" : name))
    }
    let ids = try enabled.map { value -> Int in
        guard let id = positiveItemID(value), validIDs.contains(id) else {
            throw ApiError(.api, "接单类型选择无效")
        }
        return id
    }
    return (options.sorted { $0.id < $1.id }, Array(Set(ids)).sorted())
}

extension DPGConfig {
    public func itemSwitchScope(accountKey: String, shopKey: String) -> [Int] {
        guard !itemSwitchAccountKey.isEmpty, !shopKey.isEmpty,
              itemSwitchAccountKey == accountKey, itemSwitchShopId == shopKey else { return [] }
        return itemSwitchIds
    }
}

/// The §7.1 filter chain. `check(order:)` returns whether the order
/// passes and a short human-readable reason when it does not.
public struct OrderFilter {
    public let minAmount: Double
    public let maxAmount: Double
    /// 0 = ignore memos, 1 = drop orders that have any memo.
    public let memoMode: Int
    public let memoKeywords: [String]
    public let blacklist: Set<String>
    public let enabledItems: [Int]

    public init(
        minAmount: Double,
        maxAmount: Double,
        memoMode: Int,
        memoKeywords: [String],
        blacklist: [String],
        enabledItems: [Int] = []
    ) {
        self.enabledItems = enabledItems
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
        let itemIDs = orderItemIds(order)
        if !enabledItems.isEmpty, !itemIDs.isEmpty, itemIDs.isDisjoint(with: enabledItems) {
            return (false, "订单类型未启用")
        }
        return (true, "")
    }
}
