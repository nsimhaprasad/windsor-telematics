package io.windsor.telematics

/** Quality of the vehicle's last GPS fix (ASN.1 GPSStatus). */
enum class GpsStatus {
    NO_SIGNAL, TIME_FIX, FIX_2D, FIX_3D;

    val hasFix: Boolean get() = this == FIX_2D || this == FIX_3D
}

/**
 * A decoded RvsPosition in ordinary units. Latitude/longitude are degrees
 * (the protocol carries micro-degrees), altitude metres, speed km/h.
 */
data class GpsPosition(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Int? = null,
    val headingDeg: Int? = null,
    val speedKmh: Double? = null,
    val hdop: Int? = null,
    val satellites: Int? = null,
    val gpsStatus: GpsStatus? = null,
    val positionTime: Int? = null,
) {
    val hasFix: Boolean
        get() = gpsStatus in listOf(GpsStatus.FIX_2D, GpsStatus.FIX_3D) && latitude != null && longitude != null
}

/** A vehicle returned by the MG India gateway /vehicle/userVinList. */
data class Vehicle(
    val vin: String,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val modelYear: String? = null,
    val colorName: String? = null,
    val series: String? = null,
)

/** Consolidated vehicle state from the TAP status poll. */
data class Status(
    val statusTime: Int? = null,
    val locked: Boolean? = null,
    val driverDoorOpen: Boolean? = null,
    val passengerDoorOpen: Boolean? = null,
    val rearLeftDoorOpen: Boolean? = null,
    val rearRightDoorOpen: Boolean? = null,
    val bootOpen: Boolean? = null,
    val bonnetOpen: Boolean? = null,
    val driverWindowOpen: Boolean? = null,
    val passengerWindowOpen: Boolean? = null,
    val rearLeftWindowOpen: Boolean? = null,
    val rearRightWindowOpen: Boolean? = null,
    val sunroofOpen: Boolean? = null,
    val climateRunning: Boolean? = null,
    val interiorTemperature: Int? = null,
    val exteriorTemperature: Int? = null,
    val fuelLevel: Int? = null,
    val rangeKm: Double? = null,
    val odometerKm: Double? = null,
    val auxBatteryVoltage: Double? = null,
    val frontLeftTyrePsi: Double? = null,
    val frontRightTyrePsi: Double? = null,
    val rearLeftTyrePsi: Double? = null,
    val rearRightTyrePsi: Double? = null,
    val tyreMonitorStatus: Int? = null,
    /**
     * The car's own engine/ready state, as the byte it sends.
     *
     * Raw because the enumeration behind it is not established — the protocol carries eight bits
     * and nothing in the captured frames pins down what each value means. Exposed anyway, because
     * a value nobody records cannot be decoded later.
     */
    val engineStatusRaw: Int? = null,
    /** The car's own power mode byte. Raw for the same reason as [engineStatusRaw]. */
    val powerModeRaw: Int? = null,
    /**
     * The car's own identifier for the journey it is on.
     *
     * The most useful thing in this frame that nobody was reading. Everything downstream infers
     * where one drive ends and the next begins from how long the car has been stationary, and
     * every threshold in that inference is a guess about traffic, parking and drop-offs. The car
     * does not guess: it numbers its journeys, and a change in this value is a boundary stated as
     * fact rather than deduced.
     */
    val currentJourneyId: Int? = null,
    /**
     * Distance covered on the current journey, in the units the car sends.
     *
     * Raw because the scale is unconfirmed. The neighbouring mileage fields are tenths of a
     * kilometre and this is very likely the same, but "very likely" is how a distance ends up out
     * by a factor of ten, so the caller is handed the integer and told what it is.
     */
    val currentJourneyDistanceRaw: Int? = null,
    val canBusActive: Boolean? = null,
    val lastCanActivity: Int? = null,
    val handbrake: Boolean? = null,
    val gps: GpsPosition? = null,
    val charge: ChargeStatus? = null,
)

/**
 * Decoded EV charging status (the app-id 511 charging frame).
 *
 * Confirmed scales are applied (SOC/range in tenths, voltage in quarter-volts,
 * current in 0.05 A steps about a 1000 A zero point, energy in tenths of kWh);
 * unconfirmed integer fields keep a `_raw` suffix.
 */
data class ChargeStatus(
    val isCharging: Boolean,
    val isPluggedIn: Boolean,
    val chargingType: Int,
    val chargingElectricityPhase: Int? = null,
    val soc: Double? = null,
    val rangeKm: Double,
    val chargingVoltage: Double,
    val chargingCurrent: Double,
    val batteryEnergyKwh: Double,
    val workingVoltage: Double? = null,
    val workingCurrent: Double? = null,
    val chargeTimeElapsedS: Int? = null,
    val startTime: Int? = null,
    val endTime: Int? = null,
    val chargeTimeRemainingMin: Int? = null,
    val chargingPileId: String? = null,
    val chargingPileSupplier: String? = null,
    val odometerKm: Double,
    val distanceSinceLastChargeKm: Double? = null,
    val powerUsageSinceLastChargeKwh: Double? = null,
    val mileageOfDayRaw: Int? = null,
    val powerUsageOfDayRaw: Int? = null,
    val staticEnergyConsumptionRaw: Int? = null,
    val totalBatteryCapacityKwh: Double? = null,
    val lastChargeEndingPowerKwh: Double? = null,
    val fotaLowestVoltageRaw: Int? = null,
    val statusTime: Int,
    val extendedData1: Int? = null,
    val extendedData2: Int? = null,
    val extendedData3: String? = null,
    val extendedData4: String? = null,
    val gps: GpsPosition? = null,
)