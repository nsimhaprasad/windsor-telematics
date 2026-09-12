package io.windsor.telematics

import io.windsor.telematics.internal.decryptGatewayBody
import io.windsor.telematics.internal.gatewaySignature
import io.windsor.telematics.internal.hexToBytes
import io.windsor.telematics.internal.makeDeviceId
import io.windsor.telematics.internal.md5Hex
import io.windsor.telematics.internal.normalizePhone
import io.windsor.telematics.internal.tapSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CryptoTest {

    @Test
    fun normalizePhone_keepsTrailing10Digits() {
        assertEquals("9876543210", normalizePhone("+91 98765 43210"))
        assertEquals("9876543210", normalizePhone("9876543210"))
    }

    @Test
    fun normalizePhone_rejectsNonTenDigit() {
        assertFailsWith<IllegalArgumentException> { normalizePhone("12345") }
    }

    @Test
    fun deviceId_matchesGolden() {
        assertEquals(Goldens.get("device_id"), makeDeviceId("9876543210"))
    }

    @Test
    fun md5Hex_matchesGolden() {
        assertEquals(Goldens.get("md5_hello"), md5Hex("hello"))
    }

    @Test
    fun tapSignature_matchesGolden() {
        assertEquals(Goldens.get("tap_signature_short"), tapSignature("0123456789ABCDEF"))
        assertEquals(Goldens.get("tap_signature_login_body"), tapSignature(Goldens.get("login_body")))
    }

    @Test
    fun gatewaySignature_matchesGolden() {
        assertEquals(
            Goldens.get("gateway_signature"),
            gatewaySignature("/vehicle/userVinList", "1700000000000", "application/json"),
        )
    }

    @Test
    fun gatewayBody_roundTrips() {
        val plaintext = "{\"code\":0,\"data\":{\"vin\":\"ABC123\"}}"
        val timestamp = "1700000000000"
        val contentType = "application/json"
        val key = md5Hex(timestamp + "1" + contentType).hexToBytes()
        val iv = md5Hex(timestamp).hexToBytes()
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv),
        )
        val encryptedHex = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(plaintext, decryptGatewayBody(encryptedHex, timestamp, contentType))
    }
}