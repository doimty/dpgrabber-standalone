// DPGrabber iOS - Crypto layer (PROTOCOL.md §4.5).
//
// Callers only pass hex strings (key/IV) and Base64 strings (payload);
// the hex -> bytes and Base64 -> bytes conversions happen inside this
// layer, exactly like DPGrabber's DPGHexDecode / decryptBase64Response
// and the PC tool's binascii.unhexlify / a2b_base64.
//
// Key length selects the variant: 16 bytes -> AES-128, 32 bytes -> AES-256.

import Foundation

public enum CryptoError: Error, Equatable {
    case badHexString
    case badBase64
    case badKeyLength
    case badIVLength
    case decryptionFailed(String)
}

/// Hex string -> bytes. Raises on bad input.
public func hexDecode(_ text: String) throws -> [UInt8] {
    let clean = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard !clean.isEmpty, clean.count % 2 == 0,
          clean.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) })
    else { throw CryptoError.badHexString }
    var out = [UInt8]()
    out.reserveCapacity(clean.count / 2)
    var index = clean.startIndex
    while index < clean.endIndex {
        let next = clean.index(index, offsetBy: 2)
        guard let byte = UInt8(clean[index..<next], radix: 16) else {
            throw CryptoError.badHexString
        }
        out.append(byte)
        index = next
    }
    return out
}

/// Validate a key/IV pair the way the config layer does.
public func validateKeyIV(keyHex: String, ivHex: String) throws {
    let key = try hexDecode(keyHex)
    guard key.count == 16 || key.count == 32 else { throw CryptoError.badKeyLength }
    let iv = try hexDecode(ivHex)
    guard iv.count == 16 else { throw CryptoError.badIVLength }
}

/// Base64 ciphertext -> plaintext bytes with AES-CBC + PKCS#7.
/// Throws on bad input / bad padding.
public func aesDecryptB64(_ payloadB64: String, keyHex: String, ivHex: String) throws -> Data {
    let key = try hexDecode(keyHex)
    let iv = try hexDecode(ivHex)
    guard key.count == 16 || key.count == 32 else { throw CryptoError.badKeyLength }
    guard iv.count == 16 else { throw CryptoError.badIVLength }
    guard let cipherBytes = Data(base64Encoded: payloadB64.trimmingCharacters(in: .whitespacesAndNewlines),
                                 options: [.ignoreUnknownCharacters]) else {
        throw CryptoError.badBase64
    }
    // Data(base64Encoded:options:) is lenient about whitespace; reject
    // payloads that are not strict Base64 by re-encoding and comparing.
    let strict = cipherBytes.base64EncodedString()
    let normalized = payloadB64.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\n", with: "")
        .replacingOccurrences(of: "\r", with: "")
        .replacingOccurrences(of: " ", with: "")
    guard strict == normalized else { throw CryptoError.badBase64 }
    do {
        return try AES.cbcDecrypt(cipherBytes, key: key, iv: iv)
    } catch let error as AESError {
        throw CryptoError.decryptionFailed(String(describing: error))
    }
}

/// Test helper: plaintext -> Base64 ciphertext (AES-CBC + PKCS#7).
public func aesEncryptB64(_ plaintext: Data, keyHex: String, ivHex: String) throws -> String {
    let key = try hexDecode(keyHex)
    let iv = try hexDecode(ivHex)
    return try AES.cbcEncrypt(plaintext, key: key, iv: iv).base64EncodedString()
}

/// Normalize a timestamp to integer milliseconds (DPGCompat.m
/// DPGNormalizeMilliseconds): <= 0 -> 0; < 1e12 is seconds -> *1000;
/// otherwise already milliseconds.
public func normalizeMs(_ value: Any?) -> Int {
    guard let value = value else { return 0 }
    let num: Double
    if let n = value as? NSNumber { num = n.doubleValue }
    else if let s = value as? String, let d = Double(s) { num = d }
    else { return 0 }
    if num <= 0 { return 0 }
    if num < 1e12 { return Int(num * 1000) }
    return Int(num)
}

/// Order amount in cents -> yuan.
public func amountYuan(fromCents value: Any?) -> Double {
    if let n = value as? NSNumber { return n.doubleValue / 100.0 }
    if let s = value as? String, let d = Double(s) { return d / 100.0 }
    return 0.0
}

/// Read the business `code` as an Int (number or numeric string);
/// nil when missing or malformed (PROTOCOL.md §1).
public func parseCode(_ object: Any?) -> Int? {
    guard let dict = object as? [String: Any] else { return nil }
    let code = dict["code"]
    if code is NSNull { return nil }
    if let n = code as? NSNumber {
        // JSONSerialization produces a boolean NSNumber (ObjC type "c")
        // for true/false; reject those so a JSON bool is never read as 0/1.
        if String(cString: n.objCType) == "c" { return nil }
        return n.intValue
    }
    if let s = code as? String {
        let trimmed = s.trimmingCharacters(in: .whitespaces)
        if let i = Int(trimmed) { return i }
        if let d = Double(trimmed), d == d.rounded() { return Int(d) }
    }
    return nil
}
