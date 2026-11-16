// DPGrabber iOS app - Keychain credential store.
//
// The phone number and password are stored in the iOS Keychain only.
// They are NEVER written to config.json or to UserDefaults.
// 仅设备解锁时可读且不随备份迁移；摘要与原始密码同等敏感。

import Foundation
import Security

enum CredentialStoreError: Error, LocalizedError {
    case saveFailed(OSStatus)
    case loadFailed(OSStatus)
    case deleteFailed(OSStatus)

    var errorDescription: String? {
        switch self {
        case .saveFailed(let status): return "保存账号失败（\(status)）"
        case .loadFailed(let status): return "读取账号失败（\(status)）"
        case .deleteFailed(let status): return "删除账号失败（\(status)）"
        }
    }
}

struct SavedCredentials {
    var phone: String
    var password: String
}

enum CredentialStore {
    private static let service = "com.doimty.dpgrabber"
    private static let phoneAccount = "phone"
    private static let passwordAccount = "password"
    private static let credentialAccount = "credentials-v2"

    /// Store one atomic credential pair so a partial update cannot combine two accounts.
    static func save(phone: String, password: String) throws {
        let value = ["phone": phone, "password": password]
        let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        try saveItem(account: credentialAccount, value: String(decoding: data, as: UTF8.self))
        guard let check = try loadItem(account: credentialAccount), check == String(decoding: data, as: UTF8.self) else {
            throw CredentialStoreError.saveFailed(errSecIO)
        }
        try deleteItem(account: phoneAccount)
        try deleteItem(account: passwordAccount)
    }

    /// Load both items. Returns nil when nothing was saved yet.
    static func load() throws -> SavedCredentials? {
        if let encoded = try loadItem(account: credentialAccount) {
            guard let data = encoded.data(using: .utf8),
                  let pair = try JSONSerialization.jsonObject(with: data) as? [String: String],
                  let phone = pair["phone"], let password = pair["password"] else { throw CredentialStoreError.loadFailed(errSecDecode) }
            return SavedCredentials(phone: phone, password: password)
        }
        guard let phone = try loadItem(account: phoneAccount), let password = try loadItem(account: passwordAccount),
              !phone.isEmpty, !password.isEmpty else { return nil }
        try save(phone: phone, password: password)
        return SavedCredentials(phone: phone, password: password)
    }

    /// Delete both items (ignoring "not found").
    static func delete() throws {
        // Remove legacy pairs before v2; any failure leaves a coherent v2 pair and is reported.
        try deleteItem(account: phoneAccount)
        try deleteItem(account: passwordAccount)
        try deleteItem(account: credentialAccount)
    }

    // MARK: - Private helpers

    private static func saveItem(account: String, value: String) throws {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        var attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        if updateStatus != errSecItemNotFound {
            throw CredentialStoreError.saveFailed(updateStatus)
        }
        attributes[kSecClass as String] = kSecClassGenericPassword
        attributes[kSecAttrService as String] = service
        attributes[kSecAttrAccount as String] = account
        let addStatus = SecItemAdd(attributes as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw CredentialStoreError.saveFailed(addStatus)
        }
    }

    private static func loadItem(account: String) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw CredentialStoreError.loadFailed(status)
        }
        return String(data: data, encoding: .utf8)
    }

    private static func deleteItem(account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw CredentialStoreError.deleteFailed(status)
        }
    }
}
