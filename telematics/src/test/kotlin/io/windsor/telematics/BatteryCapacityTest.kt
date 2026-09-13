package io.windsor.telematics

import io.windsor.telematics.internal.decodeChargeApp
import io.windsor.telematics.internal.decodeV21Frame
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the real captured MG frames say about battery capacity (state of health).
 *
 * The charging frame has a `totalBatteryCapacityKwh` attribute, but in every one of the five
 * captured car responses the presence bit is clear — the vehicle does not transmit it. So SOH
 * cannot be read from the car's charging status; the only battery-health signal available is the
 * energy a full battery holds.
 *
 * As a check that the energy/SOC fields decode at the right bit offsets, the stored-energy against
 * SOC ratio is the same ~37 kWh full-pack figure in every frame, whatever charge level the car
 * was at when the frame was captured.
 */
class BatteryCapacityTest {

    private val capturedFrames = listOf("CHARGING_70", "CHARGING_74", "CHARGING_11A", "CHARGING_17A_SOC45", "IDLE_100")

    @Test
    fun carDoesNotTransmitCapacityField() {
        for (key in capturedFrames) {
            val s = decodeChargeApp(decodeV21Frame(Goldens.get(key)).app) ?: error("$key failed to decode")
            assertNull(s.totalBatteryCapacityKwh, "$key carries a battery capacity it should not")
        }
    }

    @Test
    fun storedEnergyFollowsSocWithStableFullPackEnergy() {
        val impliedFull = capturedFrames.map { key ->
            val s = decodeChargeApp(decodeV21Frame(Goldens.get(key)).app) ?: error("$key failed to decode")
            s.batteryEnergyKwh / (s.soc ?: error("$key has no SOC")) * 100.0
        }
        val mean = impliedFull.sum() / impliedFull.size
        for (value in impliedFull) {
            assertTrue(
                (value - mean).absoluteValue < 0.3,
                "frame implies ${value} kWh full but the pack reads ~${mean} kWh elsewhere"
            )
        }
        assertEquals(37.1, mean, 0.5)
    }
}