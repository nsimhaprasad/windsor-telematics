package io.windsor.telematics.internal

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun md5Hex(value: String): String = md5Hex(value.toByteArray(Charsets.UTF_8))

internal fun md5Hex(bytes: ByteArray): String = hexLower(MessageDigest.getInstance("MD5").digest(bytes))

internal fun sha256Hex(value: String): String =
    hexLower(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun hexLower(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

/**
 * Normalize an India mobile number to the trailing 10 digits that MG India's
 * app uses as the account key.
 */
internal fun normalizePhone(phone: String): String {
    val digits = (phone ?: "").filter { it.isDigit() }
    val tail = if (digits.length >= 10) digits.takeLast(10) else digits
    require(tail.length == 10) { "Use the 10 digit India mobile number" }
    return tail
}

/**
 * The 103-char device id the login payload carries: the SHA-256 of the phone
 * keyed by the app's fixed prefix, padded to length.
 */
internal fun makeDeviceId(phone: String): String {
    val seed = sha256Hex("mg-ismart-india:${normalizePhone(phone)}")
    val prefixed = "haos-mg-ismart-india-$seed"
    val padded = (prefixed + "0".repeat(120))
    return padded.take(103)
}

/**
 * APP-SIGNATURE header for TAP frames: HMAC-SHA256 over the body, keyed by the
 * MD5 of the body's middle half.
 */
internal fun tapSignature(body: String): String {
    val key = md5Hex(body.substring(1, body.length / 2))
    return hexLower(hmacSha256(key.toByteArray(Charsets.UTF_8), body.toByteArray(Charsets.UTF_8)))
}

/**
 * APP-VERIFICATION-STRING header for the HTTPS gateway: a nested-MD5 key from
 * (path, timestamp, "1", content-type) over the same string.
 */
internal fun gatewaySignature(path: String, timestamp: String, contentType: String): String {
    val part1 = md5Hex(path)
    val part2 = md5Hex(part1 + timestamp + "1" + contentType)
    val key = md5Hex(part2 + timestamp)
    return hexLower(hmacSha256(key.toByteArray(Charsets.UTF_8), (path + timestamp + "1" + contentType).toByteArray(Charsets.UTF_8)))
}

/**
 * Decrypt an encrypted gateway body. The key and IV are both hex ASCII strings
 * derived from the response's APP-SEND-DATE / ORIGINAL-CONTENT-TYPE headers.
 */
internal fun decryptGatewayBody(encryptedHex: String, timestamp: String, contentType: String): String {
    val keyHex = md5Hex(timestamp + "1" + contentType)
    val ivHex = md5Hex(timestamp)
    val cipherText = encryptedHex.hexToBytes()
    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        javax.crypto.Cipher.DECRYPT_MODE,
        SecretKeySpec(keyHex.hexToBytes(), "AES"),
        javax.crypto.spec.IvParameterSpec(ivHex.hexToBytes()),
    )
    return String(cipher.doFinal(cipherText), Charsets.UTF_8)
}