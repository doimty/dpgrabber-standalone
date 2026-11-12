// DPGrabber iOS app - Keychain credential store.
//
// The phone number and password are stored in the iOS Keychain only.
// They are NEVER written to config.json or to UserDefaults.
// Accessibility is "after first unlock" so the listening engine can
// still read them when the app runs in the background.

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

    /// Save (or replace) both items.
    static func save(phone: String, password: String) throws {
        try saveItem(account: phoneAccount, value: phone)
        try saveItem(account: passwordAccount, value: password)
    }

    /// Load both items. Returns nil when nothing was saved yet.
    static func load() throws -> SavedCredentials? {
        guard let phone = try loadItem(account: phoneAccount) else { return nil }
        let password = try loadItem(account: passwordAccount) ?? ""
        return SavedCredentials(phone: phone, password: password)
    }

    /// Delete both items (ignoring "not found").
    static func delete() throws {
        try deleteItem(account: phoneAccount)
        try deleteItem(account: passwordAccount)
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
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
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
