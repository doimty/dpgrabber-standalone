package com.doimty.dpgrabber.core

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CBC + PKCS#7 crypto layer (PROTOCOL.md section 4.5).
 *
 * Callers only pass hex strings (key/IV) and Base64 strings (payload);
 * the hex -> bytes and Base64 -> bytes conversions happen inside this
 * layer, exactly like DPGrabber's DPGHexDecode / decryptBase64Response
 * and the PC tool's binascii.unhexlify / a2b_base64.
 *
 * Key length selects the variant: 16 bytes -> AES-128, 32 bytes -> AES-256.
 * (javax.crypto calls the padding PKCS5Padding; for AES block size 16 it
 * is identical to PKCS#7.)
 */
object Crypto {

    /** Hex string -> bytes. Throws IllegalArgumentException on bad input. */
    fun hexDecode(text: String): ByteArray {
        val clean = text.trim().lowercase()
        require(clean.isNotEmpty() && clean.length % 2 == 0) { "bad hex string" }
        require(clean.all { it in '0'..'9' || it in 'a'..'f' }) { "bad hex string" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /** Validate a key/IV pair the way the config layer does. */
    fun validateKeyIv(keyHex: String, ivHex: String) {
        val key = hexDecode(keyHex)
        require(key.size == 16 || key.size == 32) {
            "AES key must decode to 16 bytes (AES-128) or 32 bytes (AES-256)"
        }
        val iv = hexDecode(ivHex)
        require(iv.size == 16) { "AES IV must decode to 16 bytes" }
    }

    /**
     * Base64 ciphertext -> plaintext bytes with AES-CBC + PKCS#7.
     * Throws IllegalArgumentException on bad input / bad padding.
     */
    fun aesDecryptB64(payloadB64: String, keyHex: String, ivHex: String): ByteArray {
        val key = hexDecode(keyHex)
        val iv = hexDecode(ivHex)
        require(key.size == 16 || key.size == 32) { "AES key must be 16 or 32 bytes" }
        require(iv.size == 16) { "AES IV must be 16 bytes" }
        val cipherBytes = try {
            Base64.getDecoder().decode(payloadB64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("payload is not valid Base64: ${e.message}")
        }
        require(cipherBytes.isNotEmpty() && cipherBytes.size % 16 == 0) {
            "ciphertext length is not a multiple of 16"
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherBytes)
    }

    /** Test helper: plaintext -> Base64 ciphertext (AES-CBC + PKCS#7). */
    fun aesEncryptB64(plaintext: ByteArray, keyHex: String, ivHex: String): String {
        val key = hexDecode(keyHex)
        val iv = hexDecode(ivHex)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
    }
}
