// DPGrabber iOS - Crypto unit tests.
//
// AES vectors are NIST known-answer tests (FIPS-197 Appendix B/C and
// SP 800-38A F.2.1) plus ciphertexts produced by Python's PyCryptodome
// with the two protocol key sets, so the Swift implementation is pinned
// against an independent implementation.

import XCTest
@testable import DPGrabberCore

final class CryptoTests: XCTestCase {

    private func bytes(_ hex: String) -> [UInt8] {
        var out = [UInt8]()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            out.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return out
    }

    // MARK: - AES single block (NIST FIPS-197)

    func testAES128ECBVector() throws {
        let key = bytes("000102030405060708090a0b0c0d0e0f")
        let plain = bytes("00112233445566778899aabbccddeeff")
        let cipher = try AES.encryptBlock(plain, key: key)
        XCTAssertEqual(cipher.map { String(format: "%02x", $0) }.joined(), "69c4e0d86a7b0430d8cdb78070b4c55a")
        XCTAssertEqual(try AES.decryptBlock(cipher, key: key), plain)
    }

    func testAES256ECBVector() throws {
        let key = bytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        let plain = bytes("00112233445566778899aabbccddeeff")
        let cipher = try AES.encryptBlock(plain, key: key)
        XCTAssertEqual(cipher.map { String(format: "%02x", $0) }.joined(), "8ea2b7ca516745bfeafc49904b496089")
        XCTAssertEqual(try AES.decryptBlock(cipher, key: key), plain)
    }

    // MARK: - AES-CBC (NIST SP 800-38A F.2.1 + protocol keys)

    func testAES128CBCVector() throws {
        let key = bytes("2b7e151628aed2a6abf7158809cf4f3c")
        let iv = bytes("000102030405060708090a0b0c0d0e0f")
        let plain = Data(bytes("6bc1bee22e409f96e93d7e117393172aae2d8a571e03fb9c2350b72312d5f34230c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"))
        // cbcEncrypt adds one PKCS#7 padding block (input is already a
        // multiple of 16), so only the first 4 blocks match the NIST
        // vector; the full output must round-trip.
        let cipher = try AES.cbcEncrypt(plain, key: key, iv: iv)
        XCTAssertEqual(
            cipher.prefix(64).map { String(format: "%02x", $0) }.joined(),
            "7649abac8119b246cee98e9b12e9197d216a4d142267243767aaa6609b7d5a1972e73e2683c4865e0b9ed5610a11b8e09375261c37bdc10acc64e329498e889a"
        )
        XCTAssertEqual(cipher.count, 80)
        XCTAssertEqual(try AES.cbcDecrypt(cipher, key: key, iv: iv), plain)
    }

    func testAES256CBCWithPrimaryKey() throws {
        let key = try hexDecode(defaultAESKeyHex)
        let iv = try hexDecode(defaultAESIVHex)
        let plain = Data(bytes("6bc1bee22e409f96e93d7e117393172aae2d8a571e03fb9c2350b72312d5f34230c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"))
        // First 4 blocks match the PyCryptodome reference (the 5th block
        // is the PKCS#7 padding block added by cbcEncrypt).
        let cipher = try AES.cbcEncrypt(plain, key: key, iv: iv)
        XCTAssertEqual(
            cipher.prefix(64).map { String(format: "%02x", $0) }.joined(),
            "862053c4ad6f63773e91b6ce321f3f93bdfab2c791b42105ffa29c2cd27ea5a79893c00d1911a4f507692239b5b7af6126fa19f03e0bcda1b0af0d6ddf7c8047"
        )
        XCTAssertEqual(cipher.count, 80)
        XCTAssertEqual(try AES.cbcDecrypt(cipher, key: key, iv: iv), plain)
    }

    func testAES128CBCWithAltKey() throws {
        let key = try hexDecode(altAESKeyHex)
        let iv = try hexDecode(altAESIVHex)
        let plain = Data(bytes("6bc1bee22e409f96e93d7e117393172aae2d8a571e03fb9c2350b72312d5f34230c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"))
        // First 4 blocks match the PyCryptodome reference (the 5th block
        // is the PKCS#7 padding block added by cbcEncrypt).
        let cipher = try AES.cbcEncrypt(plain, key: key, iv: iv)
        XCTAssertEqual(
            cipher.prefix(64).map { String(format: "%02x", $0) }.joined(),
            "1c70173280c0fe2300a11da515bd456cdfb41fc04bed88f6d2ba4d59ee42fe20e3fd2ccc31eb666939183b893d22e4303bb5ed9110d39f1706dbd81a493f39c1"
        )
        XCTAssertEqual(cipher.count, 80)
        XCTAssertEqual(try AES.cbcDecrypt(cipher, key: key, iv: iv), plain)
    }

    // MARK: - Cross-implementation: Python-encrypted payloads

    func testDecryptPythonEncryptedPrimary() throws {
        // Ciphertext produced by Python PyCryptodome, AES-256-CBC, PKCS#7,
        // plaintext: {"code": 0, "list": [{"id": "123", "totalAmount": 2500}]}
        let b64 = "/5rX0xhO4wmxMiOXo7uRo7hYGN6QI2dUhg8Bzy2wA+V0XlNIxlGUexAA6Pmg/nU+1Z7w1yBjLPV6FOJju2WoBQ=="
        let plain = try aesDecryptB64(b64, keyHex: defaultAESKeyHex, ivHex: defaultAESIVHex)
        let decoded = try JSONSerialization.jsonObject(with: plain) as? [String: Any]
        XCTAssertEqual(decoded?["code"] as? NSNumber, 0)
        let list = decoded?["list"] as? [[String: Any]]
        XCTAssertEqual(list?.first?["id"] as? String, "123")
    }

    func testDecryptPythonEncryptedAlt() throws {
        let b64 = "cBOAtMFEqw3grAuI1t5qmbTuq/x7HlWWttRdX8lJjJTKjy7YkP4TNFlZsSHtDv17l2XTmVQOh5kzS++c/RewmQ=="
        let plain = try aesDecryptB64(b64, keyHex: altAESKeyHex, ivHex: altAESIVHex)
        let decoded = try JSONSerialization.jsonObject(with: plain) as? [String: Any]
        XCTAssertEqual(decoded?["code"] as? NSNumber, 0)
    }

    func testEncryptDecryptRoundTripBothKeySets() throws {
        let plain = Data("{\"code\": 0, \"msg\": \"ok\"}".utf8)
        for (keyHex, ivHex) in [(defaultAESKeyHex, defaultAESIVHex), (altAESKeyHex, altAESIVHex)] {
            let b64 = try aesEncryptB64(plain, keyHex: keyHex, ivHex: ivHex)
            XCTAssertEqual(try aesDecryptB64(b64, keyHex: keyHex, ivHex: ivHex), plain)
        }
        // Wrong key must fail (bad padding), not silently return garbage.
        let b64 = try aesEncryptB64(plain, keyHex: defaultAESKeyHex, ivHex: defaultAESIVHex)
        XCTAssertThrowsError(try aesDecryptB64(b64, keyHex: altAESKeyHex, ivHex: altAESIVHex))
    }

    // MARK: - Input validation

    func testHexDecodeErrors() {
        XCTAssertThrowsError(try hexDecode(""))
        XCTAssertThrowsError(try hexDecode("abc")) // odd length
        XCTAssertThrowsError(try hexDecode("zz"))
        XCTAssertNoThrow(try hexDecode("00FF"))
        XCTAssertEqual(try hexDecode("00FF"), [0x00, 0xff])
    }

    func testBadKeyAndIVLengths() {
        XCTAssertThrowsError(try AES.cbcEncrypt(Data("x".utf8), key: [UInt8](repeating: 0, count: 24), iv: [UInt8](repeating: 0, count: 16)))
        XCTAssertThrowsError(try AES.cbcEncrypt(Data("x".utf8), key: [UInt8](repeating: 0, count: 16), iv: [UInt8](repeating: 0, count: 8)))
        XCTAssertThrowsError(try AES.cbcDecrypt(Data([0, 1, 2]), key: [UInt8](repeating: 0, count: 16), iv: [UInt8](repeating: 0, count: 16)))
        XCTAssertThrowsError(try aesDecryptB64("!!!not-base64!!!", keyHex: defaultAESKeyHex, ivHex: defaultAESIVHex))
    }

    func testValidateKeyIV() {
        XCTAssertNoThrow(try validateKeyIV(keyHex: defaultAESKeyHex, ivHex: defaultAESIVHex))
        XCTAssertNoThrow(try validateKeyIV(keyHex: altAESKeyHex, ivHex: altAESIVHex))
        XCTAssertThrowsError(try validateKeyIV(keyHex: "00", ivHex: defaultAESIVHex))
        XCTAssertThrowsError(try validateKeyIV(keyHex: defaultAESKeyHex, ivHex: "00"))
    }

    // MARK: - Normalization helpers

    func testNormalizeMs() {
        XCTAssertEqual(normalizeMs(0), 0)
        XCTAssertEqual(normalizeMs(-5), 0)
        XCTAssertEqual(normalizeMs(1_700_000_000), 1_700_000_000_000) // seconds -> ms
        XCTAssertEqual(normalizeMs(1_700_000_000_000), 1_700_000_000_000) // already ms
        XCTAssertEqual(normalizeMs("1700000000"), 1_700_000_000_000)
        XCTAssertEqual(normalizeMs(nil), 0)
        XCTAssertEqual(normalizeMs("nonsense"), 0)
    }

    func testAmountYuan() {
        XCTAssertEqual(amountYuan(fromCents: 2500), 25.0)
        XCTAssertEqual(amountYuan(fromCents: "1050"), 10.5)
        XCTAssertEqual(amountYuan(fromCents: nil), 0.0)
    }

    func testParseCode() {
        XCTAssertEqual(parseCode(["code": 0]), 0)
        XCTAssertEqual(parseCode(["code": "0"]), 0)
        XCTAssertEqual(parseCode(["code": "403"]), 403)
        XCTAssertEqual(parseCode(["code": 401.0]), 401)
        XCTAssertNil(parseCode(["code": true]))
        XCTAssertNil(parseCode([:]))
        XCTAssertNil(parseCode(["code": "abc"]))
        XCTAssertNil(parseCode(nil))
        XCTAssertNil(parseCode("not a dict"))
    }
}
