package io.windsor.telematics

import io.windsor.telematics.internal.buildLoginBody
import io.windsor.telematics.internal.decodeLoginResponse
import io.windsor.telematics.internal.encodeLoginApp
import io.windsor.telematics.internal.makeDeviceId
import io.windsor.telematics.internal.toHexUpper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TapV11Test {

    private val deviceId = makeDeviceId("9876543210")

    @Test
    fun loginApp_matchesGolden() {
        assertEquals(
            Goldens.get("encode_login_app"),
            encodeLoginApp("secret123", deviceId).toHexUpper(),
        )
    }

    @Test
    fun loginBody_matchesGolden() {
        // Fixed clock 1750000000 makes the timestamped dispatcher deterministic.
        assertEquals(
            Goldens.get("login_body"),
            buildLoginBody("9876543210", "secret123", deviceId, nowSec = 1750000000),
        )
    }

    @Test
    fun deviceId_isTheGoldenOne() {
        assertEquals(Goldens.get("device_id"), deviceId)
    }

    @Test
    fun successResponse_yieldsToken() {
        val (uid, token) = decodeLoginResponse(Goldens.get("login_success_response"))
        assertEquals("T".repeat(40), token)
        assertTrue(uid.isNotEmpty())
    }

    @Test
    fun errorResponse_surfacesServerMessage() {
        val e = assertFailsWith<LoginRejectedException> {
            decodeLoginResponse(Goldens.get("login_error_response"))
        }
        assertTrue(e.message!!.contains("Incorrect password"))
    }
}