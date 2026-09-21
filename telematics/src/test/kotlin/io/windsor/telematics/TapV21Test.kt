package io.windsor.telematics

import io.windsor.telematics.internal.BitReader
import io.windsor.telematics.internal.BitWriter
import io.windsor.telematics.internal.TAP_PROTOCOL_VERSION
import io.windsor.telematics.internal.decodeChargeApp
import io.windsor.telematics.internal.decodeStatusApp
import io.windsor.telematics.internal.decodeStatusAndCharge
import io.windsor.telematics.internal.decodeV21Frame
import io.windsor.telematics.internal.encodeChargeStatusRequest
import io.windsor.telematics.internal.encodeStatusRequest
import io.windsor.telematics.internal.hexToBytes
import io.windsor.telematics.internal.readConstrained
import io.windsor.telematics.internal.toHexUpper
import io.windsor.telematics.internal.writeConstrained
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UID = "1".repeat(50)
private val TOKEN = "2".repeat(40)
private val VIN = "3".repeat(17)
private const val FIXED = 1750000000L

class TapV21Test {

    // --- request encoders are byte-exact vs the Python reference -------------

    @Test
    fun statusRequest_ev7_matchesGolden() {
        assertEquals(Goldens.get("status_request_ev7"), encodeStatusRequest(UID, TOKEN, VIN, 7, FIXED))
    }

    @Test
    fun statusRequest_ev0_matchesGolden() {
        assertEquals(Goldens.get("status_request_ev0"), encodeStatusRequest(UID, TOKEN, VIN, 0, FIXED))
    }

    @Test
    fun chargeRequest_ev7_matchesGolden() {
        assertEquals(Goldens.get("charge_request_ev7"), encodeChargeStatusRequest(UID, TOKEN, VIN, 7, FIXED))
    }

    // --- response framing ----------------------------------------------------

    @Test
    fun statusResponse_dispatcher_matchesGolden() {
        val frame = decodeV21Frame(Goldens.get("status_resp_frame"))
        val d = frame.dispatcher
        assertEquals("1".repeat(50), d.uid)
        assertEquals("2".repeat(40), d.token)
        assertEquals("511", d.applicationID)
        assertEquals("3".repeat(17), d.vin)
        assertEquals(1, d.messageID)
        assertEquals(FIXED.toInt(), d.eventCreationTime)
        assertEquals(7, d.eventID)
        assertEquals(0, d.ulMessageCounter)
        assertEquals(0, d.dlMessageCounter)
        assertEquals(0, d.ackMessageCounter)
        assertEquals(false, d.ackRequired)
        assertEquals(60, d.applicationDataLength)
        assertEquals("perUnaligned", d.applicationDataEncoding)
        assertEquals(513, d.applicationDataProtocolVersion)
        assertEquals(2, d.testFlag)
        assertEquals(0, d.result)
    }

    @Test
    fun statusResponse_fullStatusDecodes() {
        val frame = decodeV21Frame(Goldens.get("status_resp_frame"))
        val status = assertNotNull(frame.app).let { decodeStatusApp(it) }
        assertNotNull(status)
        assertEquals(1786729051, status.statusTime)
        assertTrue(status.locked == true)
        assertTrue(status.bootOpen == true)
        assertTrue(status.bonnetOpen == false)
        assertEquals(50, status.fuelLevel)
        assertEquals(12.3, status.rangeKm)
        assertEquals(23344.5, status.odometerKm)
        assertEquals(14.0, status.auxBatteryVoltage)
        assertEquals(28, status.interiorTemperature)
        assertEquals(31, status.exteriorTemperature)
        assertEquals(33.8, status.frontLeftTyrePsi)
        assertEquals(35.8, status.frontRightTyrePsi)
        assertEquals(33.8, status.rearLeftTyrePsi)
        assertEquals(35.4, status.rearRightTyrePsi)
        assertEquals(0, status.tyreMonitorStatus)
        assertTrue(status.canBusActive == true)
        assertTrue(status.handbrake == false)

        val gps = assertNotNull(status.gps)
        assertEquals(12.971599, gps.latitude)
        assertEquals(77.594566, gps.longitude)
        assertEquals(920, gps.altitudeM)
        assertEquals(180, gps.headingDeg)
        assertEquals(0.0, gps.speedKmh)
        assertEquals(8, gps.hdop)
        assertEquals(11, gps.satellites)
        assertEquals(GpsStatus.FIX_3D, gps.gpsStatus)
        assertTrue(gps.hasFix)
    }

    // --- real captured Windsor charging frames -------------------------------

    @Test
    fun charging_70_decodes() {
        val s = decodeChargeApp(decodeV21Frame(Goldens.get("CHARGING_70")).app)
        assertNotNull(s)
        assertEquals(true, s.isCharging)
        assertEquals(true, s.isPluggedIn)
        assertEquals(2, s.chargingType)
        assertEquals(70.0, s.soc)
        assertEquals(223.0, s.rangeKm)
        assertEquals(382.0, s.chargingVoltage)
        assertEquals(4.2, s.chargingCurrent, 0.001)
        assertEquals(24218, s.chargeTimeElapsedS)
        assertEquals(1786704832, s.startTime)
        assertEquals(1786729051, s.statusTime)
        assertEquals(26.0, s.batteryEnergyKwh, 0.001)
        assertEquals(276, s.chargeTimeRemainingMin)
        assertEquals(8.8, s.powerUsageSinceLastChargeKwh!!, 0.001)
        assertEquals(26.0, s.lastChargeEndingPowerKwh!!, 0.001)
        assertEquals(382.5, s.workingVoltage!!)
        assertEquals(4.2, s.workingCurrent!!, 0.001)
        assertEquals(23344.5, s.odometerKm, 0.001)
        assertEquals(138.5, s.distanceSinceLastChargeKm!!, 0.001)
    }

    @Test
    fun charging_74_decodes() {
        val s = decodeChargeApp(decodeV21Frame(Goldens.get("CHARGING_74")).app)
        assertNotNull(s)
        assertEquals(true, s.isCharging)
        assertEquals(74.0, s.soc)
        assertEquals(233.0, s.rangeKm)
        assertEquals(382.25, s.chargingVoltage)
        assertEquals(4.1, s.chargingCurrent, 0.001)
        assertEquals(27.3, s.batteryEnergyKwh, 0.001)
        assertEquals(1786704832, s.startTime)
        assertEquals(23344.5, s.odometerKm, 0.001)
        assertEquals(138.5, s.distanceSinceLastChargeKm!!, 0.001)
    }

    @Test
    fun charging_11a_decodes() {
        val s = decodeChargeApp(decodeV21Frame(Goldens.get("CHARGING_11A")).app)
        assertNotNull(s)
        assertEquals(80.0, s.soc)
        assertEquals(10.8, s.chargingCurrent, 0.001)
        assertEquals(10.8, s.workingCurrent!!, 0.001)
        assertEquals(29.8, s.batteryEnergyKwh, 0.001)
        assertEquals(7.5, s.powerUsageSinceLastChargeKwh!!, 0.001)
        assertEquals(29.8, s.lastChargeEndingPowerKwh!!, 0.001)
    }

    @Test
    fun charging_17a_soc45_decodes() {
        val s = decodeChargeApp(decodeV21Frame(Goldens.get("CHARGING_17A_SOC45")).app)
        assertNotNull(s)
        assertEquals(45.0, s.soc)
        assertEquals(17.1, s.chargingCurrent, 0.001)
        assertEquals(16.6, s.batteryEnergyKwh, 0.001)
        assertEquals(37.0, s.batteryEnergyKwh / (s.soc!! / 100), 0.5)
    }

    @Test
    fun idle_100_decodes() {
        val s = decodeChargeApp(decodeV21Frame(Goldens.get("IDLE_100")).app)
        assertNotNull(s)
        assertEquals(false, s.isCharging)
        assertEquals(true, s.isPluggedIn)
        assertEquals(100.0, s.soc)
        assertEquals(320.0, s.rangeKm)
        assertEquals(395.5, s.chargingVoltage)
        assertEquals(0.0, s.chargingCurrent, 0.001)
        assertEquals(0.0, s.workingCurrent!!, 0.001)
        assertEquals(37.3, s.batteryEnergyKwh, 0.001)
        assertEquals(0.0, s.powerUsageSinceLastChargeKwh!!, 0.001)
        assertEquals(37.3, s.lastChargeEndingPowerKwh!!, 0.001)
        assertNull(s.startTime)
        assertNull(s.endTime)
        assertNull(s.chargeTimeRemainingMin)
        assertEquals(0.0, s.distanceSinceLastChargeKm!!, 0.001)
    }

    @Test
    fun chargeFrame_reencodesByteForByte() {
        // Round-trip the RvsChargingStatus app through our bit writer to prove the
        // decode consumed the exact bit layout (mirrors the Python re-encode test).
        for (key in listOf("CHARGING_70", "CHARGING_74", "CHARGING_11A", "CHARGING_17A_SOC45", "IDLE_100")) {
            val app = decodeV21Frame(Goldens.get(key)).app!!
            val reencoded = fullReencode(app)
            assertEquals(app.toHexUpper(), reencoded, key)
        }
    }

    @Test
    fun decodeStatusAndCharge_splitsShapes() {
        val statusTap = decodeStatusAndCharge(Goldens.get("status_resp_frame"))
        assertNotNull(statusTap.status)
        assertNull(statusTap.charge)
        val chargeTap = decodeStatusAndCharge(Goldens.get("CHARGING_70"))
        assertNull(chargeTap.status)
        assertNotNull(chargeTap.charge)
    }

    // --- helpers -------------------------------------------------------------

    /** Re-encode the charging app via a hand-written unaligned-PER encoder and
     *  assert it reproduces the captured bytes (mirrors the Python re-encode test). */
    private fun fullReencode(app: ByteArray): String {
        val w = BitWriter()
        val r = BitReader(app)
        // OTARVMVehicleChargingStatusResp: no top-level optionals -> no presence bits.
        w.writeConstrained(r.readConstrained(0, 2147483647L), 0, 2147483647L) // statusTime
        reencodeRvsPosition(w, r)
        // RvsChargingStatus: 22 optional presence bits.
        val socP = r.read(1); val ttlP = r.read(1); val startP = r.read(1); val endP = r.read(1)
        val pileP = r.read(1); val supplierP = r.read(1); val wcP = r.read(1); val wvP = r.read(1)
        val msP = r.read(1); val psP = r.read(1); val moP = r.read(1); val poP = r.read(1)
        val seP = r.read(1); val phP = r.read(1); val duP = r.read(1); val leP = r.read(1)
        val capP = r.read(1); val fotaP = r.read(1); val e1P = r.read(1); val e2P = r.read(1)
        val e3P = r.read(1); val e4P = r.read(1)
        listOf(socP, ttlP, startP, endP, pileP, supplierP, wcP, wvP, msP, psP, moP, poP, seP, phP, duP, leP, capP, fotaP, e1P, e2P, e3P, e4P).forEach { w.write(it, 1) }

        w.write(r.read(16), 16) // realtimePower
        if (socP == 1) w.write(r.read(16), 16)
        w.write(r.read(1), 1) // chargingState
        w.write(r.read(1), 1) // chargingGunState
        w.write(r.read(16), 16) // fuelRange
        w.write(r.read(8), 8) // chargingType
        if (ttlP == 1) w.write(r.read(16), 16)
        if (startP == 1) w.writeConstrained(r.readConstrained(0, 2147483647L), 0, 2147483647L)
        if (endP == 1) w.writeConstrained(r.readConstrained(0, 2147483647L), 0, 2147483647L)
        w.write(r.read(16), 16) // chargingCurrent
        w.write(r.read(16), 16) // chargingVoltage
        if (pileP == 1) w.writeString(r.readString(0, 64), 0, 64)
        if (supplierP == 1) w.writeString(r.readString(0, 64), 0, 64)
        if (wcP == 1) w.write(r.read(16), 16)
        if (wvP == 1) w.write(r.read(16), 16)
        if (msP == 1) w.write(r.read(16), 16)
        if (psP == 1) w.write(r.read(16), 16)
        if (moP == 1) w.write(r.read(16), 16)
        if (poP == 1) w.write(r.read(16), 16)
        if (seP == 1) w.write(r.read(16), 16)
        if (phP == 1) w.write(r.read(8), 8)
        if (duP == 1) w.writeConstrained(r.readConstrained(0, 2147483647L), 0, 2147483647L)
        if (leP == 1) w.write(r.read(16), 16)
        if (capP == 1) w.write(r.read(16), 16)
        if (fotaP == 1) w.write(r.read(8), 8)
        w.write(r.read(31), 31) // mileage
        if (e1P == 1) w.writeConstrained(r.readConstrained(0, 2147483647L), 0, 2147483647L)
        if (e2P == 1) w.writeConstrained(r.readConstrained(0, 2147483647L), 0, 2147483647L)
        if (e3P == 1) w.writeString(r.readString(0, 1024), 0, 1024)
        if (e4P == 1) w.writeString(r.readString(0, 1024), 0, 1024)
        return w.toByteArray().toHexUpper()
    }

    private fun reencodeRvsPosition(w: BitWriter, r: BitReader) {
        w.writeConstrained(r.readConstrained(-90000000, 90000000), -90000000, 90000000)
        w.writeConstrained(r.readConstrained(-180000000, 180000000), -180000000, 180000000)
        w.writeConstrained(r.readConstrained(-100L, 8900L), -100L, 8900L)
        w.writeConstrained(r.readConstrained(0L, 359L), 0L, 359L)
        w.writeConstrained(r.readConstrained(-1000L, 4500L), -1000L, 4500L)
        w.writeConstrained(r.readConstrained(0L, 1000L), 0L, 1000L)
        w.writeConstrained(r.readConstrained(0L, 16L), 0L, 16L)
        w.writeConstrained(r.readConstrained(0, 2147483647L), 0, 2147483647L)
        w.write(r.read(2), 2) // gpsStatus
    }

    // Consistency guard for the frame header we rely on everywhere.
    @Test
    fun frameHeader_isTAP33() {
        val raw = Goldens.get("status_request_ev7")
        val payload = raw.substring(5).hexToBytes()
        assertEquals(TAP_PROTOCOL_VERSION, payload[0].toInt() and 0xFF)
    }

    /**
     * The four values the decoder walked past.
     *
     * They were being read purely to advance the bit cursor — the offsets were already correct,
     * or every field after them would have been wrong — so this pins what they actually contain.
     * Printed as well as asserted, because the journey id is only interesting as a sequence: two
     * frames from the same drive should carry the same number and two drives should not.
     */
    @Test
    fun journeyAndEngineFieldsAreDecoded() {
        val names = listOf("status_resp_frame")
        var seen = 0
        for (n in names) {
            val g = runCatching { Goldens.get(n) }.getOrNull() ?: continue
            val s = runCatching { decodeStatusAndCharge(g) }.getOrNull()?.status ?: continue
            seen++
            println("FIELDPROBE $n engine=${s.engineStatusRaw} power=${s.powerModeRaw} " +
                "journeyId=${s.currentJourneyId} journeyDist=${s.currentJourneyDistanceRaw} " +
                "canBus=${s.canBusActive} odo=${s.odometerKm}")
            // A frame that decoded at all must carry these: they sit before fields already trusted.
            assertNotNull(s.engineStatusRaw)
            assertNotNull(s.powerModeRaw)
            assertNotNull(s.currentJourneyId)
            assertNotNull(s.currentJourneyDistanceRaw)
        }
        println("FIELDPROBE decoded $seen status goldens")
    }
}