package io.windsor.telematics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One live poll against a real account, run by hand.
 *
 * Exists because some questions cannot be answered from captured frames. The goldens in this
 * repository come from more than one car — some of them from a 38 kW·h Windsor rather than a
 * 52.9 — so a field that is absent, zero or implausible in them may be perfectly healthy on
 * another vehicle. `totalBatteryCapacityKwh` is the case in point: its presence bit is clear in
 * every golden, which could mean the car never sends it or could mean nobody has yet looked while
 * it did. Only a live car answers that.
 *
 * Run it with the owner's own credentials in the environment:
 *
 *     MG_PHONE=... MG_PASSWORD=... ./gradlew :telematics:test --tests '*LiveProbe*' -i
 *
 * Without them it prints a line and returns, so an ordinary build never touches the network.
 *
 * Two rules, and neither is optional. These are somebody else's servers and the account can be
 * blocked for looking like an attack, so this makes one login and at most two frame requests and
 * is never put on a loop. And it prints no VIN and no coordinates — the output of a diagnostic
 * tends to end up pasted somewhere, and a vehicle identity and a home address are exactly the two
 * things in these frames that should not travel.
 *
 * A car with no signal — a basement, a tunnel, a dead modem — refuses both frames and says so.
 * That is not a failure of this tool; there is simply nothing at the other end to ask.
 */
class LiveProbe {

    @Test
    fun probe() {
        val phone = System.getenv("MG_PHONE")
        val password = System.getenv("MG_PASSWORD")
        if (phone.isNullOrBlank() || password.isNullOrBlank()) {
            println("LIVEPROBE: no credentials in the environment; skipping")
            return
        }

        runBlocking {
            val client = TelematicsClient.create(phone, password)
            client.login()
            println("LIVEPROBE login ok")
            // The charge frame is a different app-id on a different path and is sometimes served
            // when the vehicle status poll is not — a parked car with a dormant modem will refuse
            // the second and still answer the first.
            val c = try {
                client.chargeStatus()
            } catch (e: Exception) {
                println("LIVEPROBE chargeStatus failed: ${e.message}")
                null
            }
            val status = try {
                client.status(includeCharge = false)
            } catch (e: Exception) {
                println("LIVEPROBE status failed: ${e.message}")
                null
            }
            if (c == null && status == null) {
                println("LIVEPROBE the car did not answer; it is asleep")
                return@runBlocking
            }

            println("LIVEPROBE ==== the two questions ====")
            println("LIVEPROBE totalBatteryCapacityKwh = ${c?.totalBatteryCapacityKwh}")
            println("LIVEPROBE chargingType            = ${c?.chargingType}")
            println("LIVEPROBE ==== charge frame ====")
            println("LIVEPROBE isCharging   = ${c?.isCharging}")
            println("LIVEPROBE isPluggedIn  = ${c?.isPluggedIn}")
            println("LIVEPROBE soc          = ${c?.soc}")
            println("LIVEPROBE batteryEnergyKwh = ${c?.batteryEnergyKwh}")
            println("LIVEPROBE rangeKm      = ${c?.rangeKm}")
            println("LIVEPROBE ==== the counters in dispute ====")
            println("LIVEPROBE powerUsageSinceLastChargeKwh = ${c?.powerUsageSinceLastChargeKwh}")
            println("LIVEPROBE distanceSinceLastChargeKm    = ${c?.distanceSinceLastChargeKm}")
            println("LIVEPROBE lastChargeEndingPowerKwh     = ${c?.lastChargeEndingPowerKwh}")
            println("LIVEPROBE mileageOfDayRaw              = ${c?.mileageOfDayRaw}")
            println("LIVEPROBE powerUsageOfDayRaw           = ${c?.powerUsageOfDayRaw}")
            println("LIVEPROBE staticEnergyConsumptionRaw   = ${c?.staticEnergyConsumptionRaw}")
            println("LIVEPROBE ==== status frame ====")
            println("LIVEPROBE climateRunning   = ${status?.climateRunning}")
            println("LIVEPROBE interiorTemp     = ${status?.interiorTemperature}")
            println("LIVEPROBE exteriorTemp     = ${status?.exteriorTemperature}")
            println("LIVEPROBE auxBatteryVoltage= ${status?.auxBatteryVoltage}")
            println("LIVEPROBE odometerKm       = ${status?.odometerKm}")
            println("LIVEPROBE canBusActive     = ${status?.canBusActive}")
            println("LIVEPROBE handbrake        = ${status?.handbrake}")
            println("LIVEPROBE gps speedKmh     = ${status?.gps?.speedKmh}")
            println("LIVEPROBE gps satellites   = ${status?.gps?.satellites}")
            assertTrue(true)
        }
    }
}
