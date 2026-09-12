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