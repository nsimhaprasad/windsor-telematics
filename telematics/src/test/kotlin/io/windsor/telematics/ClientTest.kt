package io.windsor.telematics

import io.windsor.telematics.internal.HttpResponse
import io.windsor.telematics.internal.Transport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted transport for end-to-end client tests against golden fixtures. */
private class FakeTransport : Transport {
    val statusBodies = mutableListOf<String>()
    val chargeBodies = mutableListOf<String>()
    val requests = mutableListOf<Pair<String, String>>()
    private var loginCalls = 0

    override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        requests.add(url to body)
        return when {
            url.contains("ota.mpv21") && statusBodies.isNotEmpty() ->
                HttpResponse(200, emptyMap(), statusBodies.removeAt(0))
            url.contains("ota.mpv21") && chargeBodies.isNotEmpty() ->
                HttpResponse(200, emptyMap(), chargeBodies.removeAt(0))
            url.contains("ota.mp") -> {
                loginCalls += 1
                HttpResponse(200, emptyMap(), Goldens.get("login_success_response"))
            }
            else -> HttpResponse(404, emptyMap(), "")
        }
    }

    override fun get(url: String, params: Map<String, String>?, headers: Map<String, String>): HttpResponse {
        throw UnsupportedOperationException("not expected in these tests")
    }
}

class ClientTest {

    @Test
    fun status_decodesEndToEnd() = runTest {
        val fake = FakeTransport().apply {
            statusBodies.add(Goldens.get("status_resp_frame"))
        }
        val client = TelematicsClient.forTesting(
            phone = "9876543210",
            password = "secret123",
            vin = "TESTVIN00000000001",
            clock = { 1750000000L },
            transport = fake,
        )
        val status = client.status()
        assertNull(status.charge)
        assertEquals(12.971599, status.gps?.latitude)
        assertEquals(23344.5, status.odometerKm)
        assertTrue(fake.requests.any { it.first.contains("ota.mp") }) // login happened
        assertTrue(fake.requests.any { it.first.contains("ota.mpv21") }) // status polled
    }

    @Test
    fun status_withCharge_attachesChargingFrame() = runTest {
        val fake = FakeTransport().apply {
            statusBodies.add(Goldens.get("status_resp_frame"))
            chargeBodies.add(Goldens.get("CHARGING_70"))
        }
        val client = TelematicsClient.forTesting(
            phone = "9876543210",
            password = "secret123",
            vin = "TESTVIN00000000001",
            clock = { 1750000000L },
            transport = fake,
        )
        val status = client.status(includeCharge = true)
        val charge = assertNotNull(status.charge)
        assertEquals(70.0, charge.soc)
        assertEquals(223.0, charge.rangeKm)
    }

    @Test
    fun chargeStatus_decodesOnItsOwnPoll() = runTest {
        val fake = FakeTransport().apply {
            chargeBodies.add(Goldens.get("CHARGING_70"))
        }
        val client = TelematicsClient.forTesting(
            phone = "9876543210",
            password = "secret123",
            vin = "TESTVIN00000000001",
            clock = { 1750000000L },
            transport = fake,
        )
        val charge = client.chargeStatus()
        assertEquals(true, charge.isCharging)
        assertEquals(1786729051, charge.statusTime)
    }

    @Test
    fun chargeStatus_raisesWhenNoFrameArrives() = runTest {
        val fake = FakeTransport()
        val client = TelematicsClient.forTesting(
            phone = "9876543210",
            password = "secret123",
            vin = "TESTVIN00000000001",
            clock = { 1750000000L },
            transport = fake,
        )
        try {
            client.chargeStatus()
            assertTrue(false, "expected ChargingStatusUnavailableException")
        } catch (e: ChargingStatusUnavailableException) {
            // expected
        }
    }
}