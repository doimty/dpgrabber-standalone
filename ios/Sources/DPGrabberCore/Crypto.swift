// DPGrabber iOS - Crypto layer (PROTOCOL.md §4.5).
//
// Callers only pass hex strings (key/IV) and Base64 strings (payload);
// the hex -> bytes and Base64 -> bytes conversions happen inside this
// layer, exactly like DPGrabber's DPGHexDecode / decryptBase64Response
// and the PC tool's binascii.unhexlify / a2b_base64.
//
// Key length selects the variant: 16 bytes -> AES-128, 32 bytes -> AES-256.

import Foundation
import CoreFoundation

public enum CryptoError: Error, Equatable {
    case badHexString
    case badBase64
    case badKeyLength
    case badIVLength
    case decryptionFailed(String)
}

/// 登录协议使用 MD5，不用于本地密码存储；已有摘要保持原值，避免重复摘要。
public func loginPasswordDigest(_ password: String) -> String {
    if password.count == 32, password.allSatisfy({ $0.isASCII && $0.isHexDigit }) { return password }
    var bytes = Array(password.utf8)
    let bitLength = UInt64(bytes.count) * 8
    bytes.append(0x80)
    while bytes.count % 64 != 56 { bytes.append(0) }
    for i in 0..<8 { bytes.append(UInt8(truncatingIfNeeded: bitLength >> (i * 8))) }
    let shifts: [UInt32] = [7,12,17,22, 5,9,14,20, 4,11,16,23, 6,10,15,21]
    let constants = (0..<64).map { UInt32(abs(sin(Double($0 + 1))) * 4294967296.0) }
    var state: [UInt32] = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476]
    for offset in stride(from: 0, to: bytes.count, by: 64) {
        let words: [UInt32] = (0..<16).map { i in
            let j = offset + i * 4
            return UInt32(bytes[j]) | UInt32(bytes[j+1]) << 8 | UInt32(bytes[j+2]) << 16 | UInt32(bytes[j+3]) << 24
        }
        var a = state[0], b = state[1], c = state[2], d = state[3]
        for i in 0..<64 {
            let f: UInt32
            let g: Int
            switch i {
            case 0..<16: f = (b & c) | (~b & d); g = i
            case 16..<32: f = (d & b) | (~d & c); g = (5 * i + 1) % 16
            case 32..<48: f = b ^ c ^ d; g = (3 * i + 5) % 16
            default: f = c ^ (b | ~d); g = (7 * i) % 16
            }
            let value = a &+ f &+ constants[i] &+ words[g]
            let shift = shifts[(i / 16) * 4 + i % 4]
            a = d; d = c; c = b
            b = b &+ ((value << shift) | (value >> (32 - shift)))
        }
        state[0] &+= a; state[1] &+= b; state[2] &+= c; state[3] &+= d
    }
    return state.flatMap { word in (0..<4).map { UInt8(truncatingIfNeeded: word >> ($0 * 8)) } }
        .map { String(format: "%02x", $0) }.joined()
}

/// 类型筛选使用独立 SHA-256 账号键；不改变登录协议或已有历史记录的 MD5 键。
/// 纯 Swift 实现使 iOS/macOS 与无额外依赖的 Linux SwiftPM 测试保持一致。
public func accountKeyForItemSwitch(_ phone: String) -> String {
    let clean = phone.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return "" }
    var bytes = Array(clean.utf8)
    let bitCount = UInt64(bytes.count) * 8
    bytes.append(0x80)
    while bytes.count % 64 != 56 { bytes.append(0) }
    for shift in stride(from: 56, through: 0, by: -8) {
        bytes.append(UInt8(truncatingIfNeeded: bitCount >> shift))
    }
    let constants: [UInt32] = [
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    ]
    var state: [UInt32] = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]
    func rotate(_ value: UInt32, _ count: UInt32) -> UInt32 {
        (value >> count) | (value << (32 - count))
    }
    for offset in stride(from: 0, to: bytes.count, by: 64) {
        var words = [UInt32](repeating: 0, count: 64)
        for i in 0..<16 {
            let j = offset + i * 4
            words[i] = (UInt32(bytes[j]) << 24) | (UInt32(bytes[j + 1]) << 16)
                | (UInt32(bytes[j + 2]) << 8) | UInt32(bytes[j + 3])
        }
        for i in 16..<64 {
            let a = words[i - 15], b = words[i - 2]
            let s0 = rotate(a, 7) ^ rotate(a, 18) ^ (a >> 3)
            let s1 = rotate(b, 17) ^ rotate(b, 19) ^ (b >> 10)
            words[i] = words[i - 16] &+ s0 &+ words[i - 7] &+ s1
        }
        var a = state[0], b = state[1], c = state[2], d = state[3]
        var e = state[4], f = state[5], g = state[6], h = state[7]
        for i in 0..<64 {
            let sum1 = rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)
            let choice = (e & f) ^ (~e & g)
            let temp1 = h &+ sum1 &+ choice &+ constants[i] &+ words[i]
            let sum0 = rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)
            let majority = (a & b) ^ (a & c) ^ (b & c)
            let temp2 = sum0 &+ majority
            h = g; g = f; f = e; e = d &+ temp1
            d = c; c = b; b = a; a = temp1 &+ temp2
        }
        state[0] &+= a; state[1] &+= b; state[2] &+= c; state[3] &+= d
        state[4] &+= e; state[5] &+= f; state[6] &+= g; state[7] &+= h
    }
    return state.map { String(format: "%08x", $0) }.joined()
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
    guard num.isFinite, num > 0 else { return 0 }
    let milliseconds = num < 1e12 ? num * 1000 : num
    guard milliseconds < Double(Int.max) else { return 0 }
    return Int(milliseconds)
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
        // CoreFoundation identity distinguishes JSON bool from valid signed-char numeric values.
        if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
        let value = n.doubleValue
        guard value.isFinite, value.rounded() == value, value >= Double(Int.min), value < Double(Int.max) else { return nil }
        return n.intValue
    }
    if let s = code as? String {
        let trimmed = s.trimmingCharacters(in: .whitespaces)
        if let i = Int(trimmed) { return i }
        if let d = Double(trimmed), d.isFinite, d == d.rounded(), d >= Double(Int.min), d < Double(Int.max) { return Int(d) }
    }
    return nil
}
