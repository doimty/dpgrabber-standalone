// DPGrabber iOS - Pure-Swift AES (FIPS-197).
//
// One implementation is used on every platform, so the code that ships on
// iOS is exactly the code the Linux unit tests exercise (CommonCrypto is
// not available on Linux, and a second implementation would leave the
// shipped path untested here). Correctness is pinned by NIST known-answer
// tests in the test target (AES-128 / AES-256, ECB and CBC vectors).
// Only AES-128 and AES-256 are needed (PROTOCOL.md §4.1).

import Foundation

/// Errors raised by the AES layer.
public enum AESError: Error, Equatable {
    /// Key length is not 16 or 32 bytes.
    case badKeyLength
    /// IV length is not 16 bytes.
    case badIVLength
    /// Input is empty or not a multiple of the 16-byte block size.
    case badInputLength
    /// PKCS#7 padding is malformed on decrypt.
    case badPadding
}

/// AES block cipher (128-bit block, 128- or 256-bit key), CBC mode.
public enum AES {

    // MARK: - Tables (FIPS-197 §5.1.1 / §5.3.1)

    private static let sbox: [UInt8] = [
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
    ]

    private static let invSbox: [UInt8] = [
        0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
        0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
        0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
        0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
        0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
        0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
        0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
        0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
        0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
        0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
        0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
        0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
        0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
        0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
        0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
        0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d,
    ]

    private static let rcon: [UInt8] = [
        0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36, 0x6c, 0xd8, 0xab, 0x4d,
    ]

    public static let blockSize = 16

    // MARK: - GF(2^8) helpers

    @inline(__always)
    private static func xtime(_ x: UInt8) -> UInt8 {
        (x << 1) ^ ((x & 0x80) != 0 ? 0x1b : 0x00)
    }

    @inline(__always)
    private static func gmul(_ x: UInt8, _ y: UInt8) -> UInt8 {
        var result: UInt8 = 0
        var a = x
        var b = y
        while b != 0 {
            if (b & 1) != 0 { result ^= a }
            a = xtime(a)
            b >>= 1
        }
        return result
    }

    // MARK: - Key expansion (FIPS-197 §5.2)

    /// Expands a 16- or 32-byte key into round keys. Each round key is 16 bytes.
    private static func expandKey(_ key: [UInt8]) throws -> [[UInt8]] {
        let nk = key.count / 4
        guard nk == 4 || nk == 8 else { throw AESError.badKeyLength }
        let nr = nk + 6
        var w = [[UInt8]](repeating: [UInt8](repeating: 0, count: 4), count: 4 * (nr + 1))
        for i in 0..<nk {
            w[i] = Array(key[(4 * i)..<(4 * i + 4)])
        }
        for i in nk..<(4 * (nr + 1)) {
            var temp = w[i - 1]
            if i % nk == 0 {
                temp = [sbox[Int(temp[1])] ^ rcon[i / nk - 1], sbox[Int(temp[2])], sbox[Int(temp[3])], sbox[Int(temp[0])]]
            } else if nk > 6 && i % nk == 4 {
                temp = temp.map { sbox[Int($0)] }
            }
            w[i] = zip(w[i - nk], temp).map(^)
        }
        var roundKeys = [[UInt8]]()
        for round in 0...nr {
            var rk = [UInt8]()
            for c in 0..<4 { rk.append(contentsOf: w[4 * round + c]) }
            roundKeys.append(rk)
        }
        return roundKeys
    }

    // MARK: - Core transforms (state is column-major: state[r + 4c])

    private static func addRoundKey(_ state: inout [UInt8], _ roundKey: [UInt8]) {
        for i in 0..<16 { state[i] ^= roundKey[i] }
    }

    private static func subBytes(_ state: inout [UInt8]) {
        for i in 0..<16 { state[i] = sbox[Int(state[i])] }
    }

    private static func invSubBytes(_ state: inout [UInt8]) {
        for i in 0..<16 { state[i] = invSbox[Int(state[i])] }
    }

    private static func shiftRows(_ state: inout [UInt8]) {
        var out = state
        for r in 0..<4 {
            for c in 0..<4 {
                out[r + 4 * c] = state[r + 4 * ((c + r) % 4)]
            }
        }
        state = out
    }

    private static func invShiftRows(_ state: inout [UInt8]) {
        var out = state
        for r in 0..<4 {
            for c in 0..<4 {
                out[r + 4 * c] = state[r + 4 * ((c + 4 - r) % 4)]
            }
        }
        state = out
    }

    private static func mixColumns(_ state: inout [UInt8]) {
        for c in 0..<4 {
            let i = 4 * c
            let a0 = state[i], a1 = state[i + 1], a2 = state[i + 2], a3 = state[i + 3]
            state[i]     = gmul(a0, 2) ^ gmul(a1, 3) ^ a2 ^ a3
            state[i + 1] = a0 ^ gmul(a1, 2) ^ gmul(a2, 3) ^ a3
            state[i + 2] = a0 ^ a1 ^ gmul(a2, 2) ^ gmul(a3, 3)
            state[i + 3] = gmul(a0, 3) ^ a1 ^ a2 ^ gmul(a3, 2)
        }
    }

    private static func invMixColumns(_ state: inout [UInt8]) {
        for c in 0..<4 {
            let i = 4 * c
            let a0 = state[i], a1 = state[i + 1], a2 = state[i + 2], a3 = state[i + 3]
            state[i]     = gmul(a0, 14) ^ gmul(a1, 11) ^ gmul(a2, 13) ^ gmul(a3, 9)
            state[i + 1] = gmul(a0, 9) ^ gmul(a1, 14) ^ gmul(a2, 11) ^ gmul(a3, 13)
            state[i + 2] = gmul(a0, 13) ^ gmul(a1, 9) ^ gmul(a2, 14) ^ gmul(a3, 11)
            state[i + 3] = gmul(a0, 11) ^ gmul(a1, 13) ^ gmul(a2, 9) ^ gmul(a3, 14)
        }
    }

    // MARK: - Single-block cipher

    /// Encrypt one 16-byte block. `key` must be 16 or 32 bytes.
    public static func encryptBlock(_ block: [UInt8], key: [UInt8]) throws -> [UInt8] {
        precondition(block.count == 16)
        let roundKeys = try expandKey(key)
        let nr = roundKeys.count - 1
        var state = block
        addRoundKey(&state, roundKeys[0])
        for round in 1..<nr {
            subBytes(&state)
            shiftRows(&state)
            mixColumns(&state)
            addRoundKey(&state, roundKeys[round])
        }
        subBytes(&state)
        shiftRows(&state)
        addRoundKey(&state, roundKeys[nr])
        return state
    }

    /// Decrypt one 16-byte block. `key` must be 16 or 32 bytes.
    public static func decryptBlock(_ block: [UInt8], key: [UInt8]) throws -> [UInt8] {
        precondition(block.count == 16)
        let roundKeys = try expandKey(key)
        let nr = roundKeys.count - 1
        var state = block
        addRoundKey(&state, roundKeys[nr])
        for round in stride(from: nr - 1, through: 1, by: -1) {
            invShiftRows(&state)
            invSubBytes(&state)
            addRoundKey(&state, roundKeys[round])
            invMixColumns(&state)
        }
        invShiftRows(&state)
        invSubBytes(&state)
        addRoundKey(&state, roundKeys[0])
        return state
    }

    // MARK: - CBC mode with PKCS#7

    /// AES-CBC encrypt with PKCS#7 padding. `key` 16 or 32 bytes, `iv` 16 bytes.
    public static func cbcEncrypt(_ plaintext: Data, key: [UInt8], iv: [UInt8]) throws -> Data {
        guard key.count == 16 || key.count == 32 else { throw AESError.badKeyLength }
        guard iv.count == 16 else { throw AESError.badIVLength }
        let pad = blockSize - (plaintext.count % blockSize)
        var padded = [UInt8](plaintext)
        padded.append(contentsOf: [UInt8](repeating: UInt8(pad), count: pad))
        var out = [UInt8]()
        out.reserveCapacity(padded.count)
        var previous = iv
        for offset in stride(from: 0, to: padded.count, by: blockSize) {
            let block = zip(Array(padded[offset..<(offset + blockSize)]), previous).map(^)
            let encrypted = try encryptBlock(block, key: key)
            out.append(contentsOf: encrypted)
            previous = encrypted
        }
        return Data(out)
    }

    /// AES-CBC decrypt with PKCS#7 unpadding. `key` 16 or 32 bytes, `iv` 16 bytes.
    public static func cbcDecrypt(_ ciphertext: Data, key: [UInt8], iv: [UInt8]) throws -> Data {
        guard key.count == 16 || key.count == 32 else { throw AESError.badKeyLength }
        guard iv.count == 16 else { throw AESError.badIVLength }
        let bytes = [UInt8](ciphertext)
        guard !bytes.isEmpty && bytes.count % blockSize == 0 else { throw AESError.badInputLength }
        var out = [UInt8]()
        out.reserveCapacity(bytes.count)
        var previous = iv
        for offset in stride(from: 0, to: bytes.count, by: blockSize) {
            let block = Array(bytes[offset..<(offset + blockSize)])
            let decrypted = try decryptBlock(block, key: key)
            out.append(contentsOf: zip(decrypted, previous).map(^))
            previous = block
        }
        guard let last = out.last, last >= 1, last <= UInt8(blockSize), out.count >= Int(last) else {
            throw AESError.badPadding
        }
        let padCount = Int(last)
        guard out.suffix(padCount).allSatisfy({ $0 == last }) else { throw AESError.badPadding }
        return Data(out.dropLast(padCount))
    }
}
