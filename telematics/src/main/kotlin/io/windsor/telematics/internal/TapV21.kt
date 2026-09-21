package io.windsor.telematics.internal

import io.windsor.telematics.ChargeStatus
import io.windsor.telematics.GpsPosition
import io.windsor.telematics.GpsStatus
import io.windsor.telematics.Status

const val TAP_RESERVED_SIZE = 16
const val TAP_PROTOCOL_VERSION = 33
const val PROTOCOL = 513
const val STATUS_APP_ID = "511"
const val CHARGE_STATUS_MESSAGE_ID = 8
const val CHARGE_STATUS_APP_LEN = 63

private const val STATUS_RESULT_PENDING = 0
private const val STATUS_RESULT_SESSION_INVALID = 2
private const val CHARGE_RESULT_UNAVAILABLE = 5
private const val STATUS_ATTEMPTS = 10
private const val STATUS_DELAY_MS = 1500L

/** MPIECC — the parsed header of a TAP v2.1 response. */
internal data class V21Dispatcher(
    val uid: String? = null,
    val token: String? = null,
    val applicationID: String? = null,
    val vin: String? = null,
    val messageID: Int? = null,
    val eventCreationTime: Int? = null,
    val eventID: Int? = null,
    val ulMessageCounter: Int? = null,
    val dlMessageCounter: Int? = null,
    val ackMessageCounter: Int? = null,
    val ackRequired: Boolean? = null,
    val applicationDataLength: Int? = null,
    val applicationDataEncoding: String? = null,
    val applicationDataProtocolVersion: Int? = null,
    val testFlag: Int? = null,
    val result: Int? = null,
    val errorMessage: String? = null,
)

internal data class V21Frame(val dispatcher: V21Dispatcher, val app: ByteArray?)

// ---------------------------------------------------------------------------
// Encoders (requests)
// ---------------------------------------------------------------------------

internal fun encodeDispatcher(
    uid: String,
    token: String,
    vin: String,
    appId: String,
    app: ByteArray,
    eventId: Int,
    msgId: Int,
    eventTimeSec: Long,
): ByteArray {
    val w = BitWriter()
    repeat(13) { w.write(1, 1) }
    w.write(0, 1) // errorMessage absent
    w.writeFixedString7(uid)
    w.writeFixedString7(token)
    w.writeFixedString7(appId)
    w.writeFixedString7(vin)
    w.writeConstrained(msgId.toLong(), 0, 255)
    w.writeConstrained(eventTimeSec, 0, 2147483647L)
    w.writeConstrained(eventId.toLong(), 0, 2147483647L)
    w.writeConstrained(0L, 0, 65535L) // ulMessageCounter
    w.writeConstrained(0L, 0, 65535L) // dlMessageCounter
    w.writeConstrained(0L, 0, 65535L) // ackMessageCounter
    w.write(0, 1)                     // ackRequired false
    w.writeConstrained(app.size.toLong(), 0, 65535L)
    w.write(0, 2) // perUnaligned
    w.writeConstrained(PROTOCOL.toLong(), 0, 65535L)
    w.write(1, 2) // testFlag 2
    w.writeConstrained(0L, 0, 65535L) // result
    return w.toByteArray()
}

private fun frameV21(dispatcher: ByteArray, app: ByteArray): String {
    val dispatcherLength = dispatcher.size + 3
    require(dispatcherLength <= 255) { "TAP dispatcher too large" }
    val payload = ByteArray(3 + TAP_RESERVED_SIZE) {
        when (it) {
            0 -> TAP_PROTOCOL_VERSION.toByte()
            1 -> dispatcherLength.toByte()
            else -> 0
        }
    } + dispatcher + app
    return "1" + "%04X".format(payload.size + 3) + payload.toHexUpper()
}

/** Encode the messageID=1 full-status request (app payload: OTARVMVehicleStatusReq=2). */
internal fun encodeStatusRequest(uid: String, token: String, vin: String, eventId: Int, eventTimeSec: Long): String {
    val app = byteArrayOf(0x02)
    val dispatcher = encodeDispatcher(uid, token, vin, STATUS_APP_ID, app, eventId, 1, eventTimeSec)
    return frameV21(dispatcher, app)
}

/** Encode the messageID=8 charge request (empty app payload). */
internal fun encodeChargeStatusRequest(uid: String, token: String, vin: String, eventId: Int, eventTimeSec: Long): String {
    val dispatcher = encodeDispatcher(uid, token, vin, STATUS_APP_ID, ByteArray(0), eventId, CHARGE_STATUS_MESSAGE_ID, eventTimeSec)
    return frameV21(dispatcher, ByteArray(0))
}

// ---------------------------------------------------------------------------
// Frame / dispatcher decoder
// ---------------------------------------------------------------------------

internal fun decodeV21Frame(raw: String): V21Frame {
    require(raw.length >= 5 && raw[0] == '1') { "unexpected TAP v2.1 response framing" }
    val data = raw.substring(5).hexToBytes()
    require(data.size >= 19) { "short TAP v2.1 response" }
    val dispatcherLength = data[1].toInt() and 0xFF
    val dispatcherEnd = TAP_RESERVED_SIZE + dispatcherLength
    require(dispatcherLength >= 3 && dispatcherEnd <= data.size) { "invalid TAP dispatcher length" }
    val dispatcher = decodeV21Dispatcher(data.copyOfRange(19, dispatcherEnd))
    val appLength = dispatcher.applicationDataLength ?: 0
    if (appLength == 0) return V21Frame(dispatcher, null)
    require(dispatcherEnd + appLength <= data.size) { "truncated TAP app data" }
    return V21Frame(dispatcher, data.copyOfRange(dispatcherEnd, dispatcherEnd + appLength))
}

private fun decodeV21Dispatcher(bytes: ByteArray): V21Dispatcher {
    val r = BitReader(bytes)
    val uidP = r.read(1)
    val tokenP = r.read(1)
    val vinP = r.read(1)
    val eventIdP = r.read(1)
    val ulP = r.read(1)
    val dlP = r.read(1)
    val ackP = r.read(1)
    val ackReqP = r.read(1)
    val appLenP = r.read(1)
    val encodingP = r.read(1)
    val protoP = r.read(1)
    val testFlagP = r.read(1)
    val resultP = r.read(1)
    val errMsgP = r.read(1)

    val uid = if (uidP == 1) r.readString(50, 50) else null
    val token = if (tokenP == 1) r.readString(40, 40) else null
    val appId = r.readString(3, 3)
    val vin = if (vinP == 1) r.readString(17, 17) else null
    val messageID = r.readConstrained(0, 255).toInt()
    val eventCreationTime = r.readConstrained(0, 2147483647L).toInt()
    val eventId = if (eventIdP == 1) r.readConstrained(0, 2147483647L).toInt() else null
    val ul = if (ulP == 1) r.readConstrained(0, 65535L).toInt() else null
    val dl = if (dlP == 1) r.readConstrained(0, 65535L).toInt() else null
    val ack = if (ackP == 1) r.readConstrained(0, 65535L).toInt() else null
    val ackRequired = if (ackReqP == 1) r.read(1) == 1 else null
    val appLen = if (appLenP == 1) r.read(16) else null
    val encoding = if (encodingP == 1) when (r.read(2)) { 0 -> "perUnaligned"; 1 -> "der"; else -> "ber" } else null
    val proto = if (protoP == 1) r.read(16) else null
    val testFlag = if (testFlagP == 1) r.readConstrained(1, 3).toInt() else null
    val result = if (resultP == 1) r.read(16) else null
    val errorMessage = if (errMsgP == 1) r.readOctets(1, 1024).toString(Charsets.UTF_8) else null
    return V21Dispatcher(uid, token, appId, vin, messageID, eventCreationTime, eventId, ul, dl, ack, ackRequired, appLen, encoding, proto, testFlag, result, errorMessage)
}

// ---------------------------------------------------------------------------
// Status payload decoder
// ---------------------------------------------------------------------------

private fun decodeRvsPosition(r: BitReader): GpsPosition {
    val lat = r.readConstrained(-90000000, 90000000) / 1e6
    val lon = r.readConstrained(-180000000, 180000000) / 1e6
    val alt = r.readConstrained(-100L, 8900L).toInt()
    val heading = r.readConstrained(0L, 359L).toInt()
    val speedRaw = r.readConstrained(-1000L, 4500L).toInt()
    val hdop = r.readConstrained(0L, 1000L).toInt()
    val satellites = r.readConstrained(0L, 16L).toInt()
    val seconds = r.readConstrained(0, 2147483647L).toInt()
    val statusRaw = r.read(2)
    val gpsStatus = if (statusRaw in 0..3) GpsStatus.entries[statusRaw] else null
    return GpsPosition(
        latitude = lat,
        longitude = lon,
        altitudeM = alt,
        headingDeg = heading,
        speedKmh = speedRaw.takeIf { it in 0..4500 }?.let { it / 10.0 },
        hdop = hdop,
        satellites = satellites,
        gpsStatus = gpsStatus,
        positionTime = seconds,
    )
}

private fun ByteArray.utf8OrNull(): String? =
    try { toString(Charsets.UTF_8) } catch (e: Exception) { null }

/**
 * Decode the full-status application payload, or null when the frame is not the
 * OTARVMVehicleStatusResp513 shape (e.g. a control result).
 */
internal fun decodeStatusApp(app: ByteArray): Status? = try {
    val r = BitReader(app)
    val extPresence = r.read(1) // extendedVehicleStatus (optional, presence bit first)
    val statusTime = r.readConstrained(0, 2147483647L).toInt()
    val gps = decodeRvsPosition(r)

    // RvsBasicStatus513: 15 optional presence bits, then fields in schema order.
    val dwP = r.read(1); val pwP = r.read(1); val rlwP = r.read(1); val rrwP = r.read(1); val sunP = r.read(1)
    val frP = r.read(1); val flP = r.read(1); val rrP = r.read(1); val rlP = r.read(1); val wtmP = r.read(1)
    val vaP = r.read(1); val flsP = r.read(1); val frsP = r.read(1); val ed1P = r.read(1); val ed2P = r.read(1)

    val driverDoor = r.read(1) == 1
    val passengerDoor = r.read(1) == 1
    val rearLeftDoor = r.read(1) == 1
    val rearRightDoor = r.read(1) == 1
    val boot = r.read(1) == 1
    val bonnet = r.read(1) == 1
    val locked = r.read(1) == 1
    val driverWindow = if (dwP == 1) r.read(1) == 1 else null
    val passengerWindow = if (pwP == 1) r.read(1) == 1 else null
    val rearLeftWindow = if (rlwP == 1) r.read(1) == 1 else null
    val rearRightWindow = if (rrwP == 1) r.read(1) == 1 else null
    val sunroof = if (sunP == 1) r.read(1) == 1 else null
    val frontRightTyre = if (frP == 1) r.read(8) else null
    val frontLeftTyre = if (flP == 1) r.read(8) else null
    val rearRightTyre = if (rrP == 1) r.read(8) else null
    val rearLeftTyre = if (rlP == 1) r.read(8) else null
    val tyreMonitor = if (wtmP == 1) r.read(8) else null
    r.read(1) // sideLightStatus
    r.read(1) // dippedBeamStatus
    r.read(1) // mainBeamStatus
    val vehicleAlarm = if (vaP == 1) r.read(8) else null
    val engineStatus = r.read(8)
    val powerMode = r.read(8)
    r.read(16) // lastKeySeen
    val currentJourneyDistance = r.read(16)
    val currentJourneyId = r.read(31)
    val interiorTemp = r.readConstrained(-128L, 127L).toInt()
    val exteriorTemp = r.readConstrained(-128L, 127L).toInt()
    val fuelLevel = r.read(8)
    val fuelRange = r.read(16)
    val remoteClimate = r.read(8)
    if (flsP == 1) r.read(8) // frontLeftSeatHeatLevel
    if (frsP == 1) r.read(8) // frontRightSeatHeatLevel
    val canBusActive = r.read(1) == 1
    val lastCan = r.readConstrained(0, 2147483647L).toInt()
    r.read(8) // clstrDspdFuelLvlSgmt
    val mileage = r.read(31)
    val batteryVoltage = r.read(16)
    if (ed1P == 1) r.read(31)
    if (ed2P == 1) r.read(31)
    val handbrake = r.read(1) == 1
    // extendedVehicleStatus (vehicleAlerts) — consumes when the leading presence bit said present.
    if (extPresence == 1) {
        val alerts = r.readConstrained(0, 64).toInt()
        repeat(alerts) { r.read(8); r.read(8) }
    }

    val windowFix = (driverWindow == true) && (locked == true) && !listOf(
        driverDoor, passengerDoor, rearLeftDoor, rearRightDoor, boot, bonnet,
        passengerWindow == true, rearLeftWindow == true, rearRightWindow == true,
    ).contains(true)
    val effectiveDriverWindow = if (windowFix) false else driverWindow

    Status(
        statusTime = statusTime,
        locked = locked,
        driverDoorOpen = driverDoor,
        passengerDoorOpen = passengerDoor,
        rearLeftDoorOpen = rearLeftDoor,
        rearRightDoorOpen = rearRightDoor,
        bootOpen = boot,
        bonnetOpen = bonnet,
        driverWindowOpen = effectiveDriverWindow,
        passengerWindowOpen = passengerWindow,
        rearLeftWindowOpen = rearLeftWindow,
        rearRightWindowOpen = rearRightWindow,
        sunroofOpen = sunroof,
        climateRunning = remoteClimate.let { if (it in 2..3) true else if (it in 0..1) false else null },
        interiorTemperature = interiorTemp,
        exteriorTemperature = exteriorTemp,
        fuelLevel = fuelLevel,
        rangeKm = fuelRange * 0.1,
        odometerKm = mileage * 0.1,
        auxBatteryVoltage = batteryVoltage * 0.1,
        frontLeftTyrePsi = frontLeftTyre?.let { psi(it) },
        frontRightTyrePsi = frontRightTyre?.let { psi(it) },
        rearLeftTyrePsi = rearLeftTyre?.let { psi(it) },
        rearRightTyrePsi = rearRightTyre?.let { psi(it) },
        tyreMonitorStatus = tyreMonitor,
        engineStatusRaw = engineStatus,
        powerModeRaw = powerMode,
        currentJourneyId = currentJourneyId,
        currentJourneyDistanceRaw = currentJourneyDistance,
        canBusActive = canBusActive,
        lastCanActivity = lastCan,
        handbrake = handbrake,
        gps = gps,
    )
} catch (e: Exception) {
    null
}

private fun psi(raw: Int): Double? = if (raw in 1..255) (raw * 0.2 * 10).let { Math.round(it) / 10.0 } else null

// ---------------------------------------------------------------------------
// Charging payload decoder
// ---------------------------------------------------------------------------

private const val TIME_LEVEL_NA = 0xFFFF

/**
 * Decode the 63-byte charging application payload, or null when it is not a
 * charging frame.
 */
internal fun decodeChargeApp(app: ByteArray?): ChargeStatus? {
    if (app == null || app.size != CHARGE_STATUS_APP_LEN) return null
    return try {
        val r = BitReader(app)
        val statusTime = r.readConstrained(0, 2147483647L).toInt()
        val gps = decodeRvsPosition(r)

        // RvsChargingStatus: 22 optional presence bits.
        val socP = r.read(1); val ttlP = r.read(1); val startP = r.read(1); val endP = r.read(1)
        val pileP = r.read(1); val supplierP = r.read(1); val wcP = r.read(1); val wvP = r.read(1)
        val msP = r.read(1); val psP = r.read(1); val moP = r.read(1); val poP = r.read(1)
        val seP = r.read(1); val phP = r.read(1); val duP = r.read(1); val leP = r.read(1)
        val capP = r.read(1); val fotaP = r.read(1); val e1P = r.read(1); val e2P = r.read(1)
        val e3P = r.read(1); val e4P = r.read(1)

        val realtimePower = r.read(16)
        val socRaw = if (socP == 1) r.read(16) else null
        val chargingState = r.read(1) == 1
        val chargingGunState = r.read(1) == 1
        val fuelRange = r.read(16)
        val chargingType = r.read(8)
        val timeLevel = if (ttlP == 1) r.read(16) else null
        val startTime = if (startP == 1) r.readConstrained(0, 2147483647L).toInt() else null
        val endTime = if (endP == 1) r.readConstrained(0, 2147483647L).toInt() else null
        val chargingCurrent = r.read(16)
        val chargingVoltage = r.read(16)
        val pileId = if (pileP == 1) r.readString(0, 64) else null
        val supplier = if (supplierP == 1) r.readString(0, 64) else null
        val workingCurrent = if (wcP == 1) r.read(16) else null
        val workingVoltage = if (wvP == 1) r.read(16) else null
        val mileageSinceLastCharge = if (msP == 1) r.read(16) else null
        val powerUsageSinceLastCharge = if (psP == 1) r.read(16) else null
        val mileageOfDay = if (moP == 1) r.read(16) else null
        val powerUsageOfDay = if (poP == 1) r.read(16) else null
        val staticConsumption = if (seP == 1) r.read(16) else null
        val phase = if (phP == 1) r.read(8) else null
        val duration = if (duP == 1) r.readConstrained(0, 2147483647L).toInt() else null
        val lastEnding = if (leP == 1) r.read(16) else null
        val totalCapacity = if (capP == 1) r.read(16) else null
        val fota = if (fotaP == 1) r.read(8) else null
        val mileage = r.read(31)
        val ed1 = if (e1P == 1) r.readConstrained(0, 2147483647L).toInt() else null
        val ed2 = if (e2P == 1) r.readConstrained(0, 2147483647L).toInt() else null
        val ed3 = if (e3P == 1) r.readString(0, 1024) else null
        val ed4 = if (e4P == 1) r.readString(0, 1024) else null

        ChargeStatus(
            isCharging = chargingState,
            isPluggedIn = chargingGunState,
            chargingType = chargingType,
            chargingElectricityPhase = phase,
            soc = socRaw?.times(0.1),
            rangeKm = fuelRange * 0.1,
            chargingVoltage = chargingVoltage * 0.25,
            chargingCurrent = 1000.0 - chargingCurrent * 0.05,
            batteryEnergyKwh = realtimePower * 0.1,
            workingVoltage = workingVoltage?.times(2.5),
            workingCurrent = workingCurrent?.let { 1000.0 - it * 0.05 },
            chargeTimeElapsedS = duration,
            startTime = startTime?.takeIf { it != 0 },
            endTime = endTime?.takeIf { it != 0 },
            chargeTimeRemainingMin = timeLevel?.takeUnless { it == TIME_LEVEL_NA },
            chargingPileId = pileId?.takeIf { it.isNotEmpty() },
            chargingPileSupplier = supplier?.takeIf { it.isNotEmpty() },
            odometerKm = mileage * 0.1,
            distanceSinceLastChargeKm = mileageSinceLastCharge?.times(0.1),
            powerUsageSinceLastChargeKwh = powerUsageSinceLastCharge?.times(0.1),
            mileageOfDayRaw = mileageOfDay,
            powerUsageOfDayRaw = powerUsageOfDay,
            staticEnergyConsumptionRaw = staticConsumption,
            totalBatteryCapacityKwh = totalCapacity?.times(0.1),
            lastChargeEndingPowerKwh = lastEnding?.times(0.1),
            fotaLowestVoltageRaw = fota,
            statusTime = statusTime,
            extendedData1 = ed1,
            extendedData2 = ed2,
            extendedData3 = ed3?.takeIf { it.isNotEmpty() },
            extendedData4 = ed4?.takeIf { it.isNotEmpty() },
            gps = gps,
        )
    } catch (e: Exception) {
        null
    }
}

internal data class TappedStatus(val dispatcher: V21Dispatcher, val status: Status?, val charge: ChargeStatus?)

/**
 * Decode one response frame into its dispatcher plus whichever of the status or
 * charging frames it carries (at most one shape per response).
 */
internal fun decodeStatusAndCharge(raw: String): TappedStatus {
    val frame = decodeV21Frame(raw)
    if (frame.app == null) return TappedStatus(frame.dispatcher, null, null)
    val charge = decodeChargeApp(frame.app)
    if (charge != null) return TappedStatus(frame.dispatcher, null, charge)
    return TappedStatus(frame.dispatcher, decodeStatusApp(frame.app), null)
}

/**
 * Poll logic shared by the status and charge request loops.
 *
 * @param post posts one frame and returns the response text.
 * @param want picks the frame this poll is waiting for from a decoded response, null until it arrives.
 * @param unavailableResults dispatcher results meaning "server will not serve this frame" (returns null, no raise).
 * @param tolerateFramingErrors retry malformed responses within the poll budget.
 */
internal suspend fun pollTappedRequest(
    post: suspend (eventId: Int) -> String,
    want: (status: Status?, charge: ChargeStatus?) -> Any?,
    unavailableResults: Set<Int> = emptySet(),
    tolerateFramingErrors: Boolean = false,
    delayMs: ((Long) -> Unit)? = null,
    onInvalidSession: suspend () -> Unit,
): Any? {
    suspend fun wait(attempts: Int) {
        if (attempts < STATUS_ATTEMPTS) {
            if (delayMs == null) kotlinx.coroutines.delay(STATUS_DELAY_MS) else delayMs(STATUS_DELAY_MS)
        }
    }
    for (loginAttempt in 0 until 2) {
        var eventId = 0
        var attempts = 0
        while (attempts < STATUS_ATTEMPTS) {
            attempts += 1
            val text = post(eventId)
            val decoded = try {
                decodeStatusAndCharge(text)
            } catch (e: IllegalArgumentException) {
                if (!tolerateFramingErrors) throw e
                wait(attempts)
                continue
            }
            val frame = want(decoded.status, decoded.charge)
            if (frame != null) return frame
            val result = decoded.dispatcher.result ?: 0
            if (result == STATUS_RESULT_SESSION_INVALID) {
                if (loginAttempt == 0) {
                    onInvalidSession()
                    break
                }
                throw io.windsor.telematics.TelematicsException("TAP session is invalid")
            }
            if (result in unavailableResults) return null
            if (result !in setOf(STATUS_RESULT_PENDING, 4, 6)) {
                throw io.windsor.telematics.TelematicsException("TAP request failed: result $result")
            }
            eventId = decoded.dispatcher.eventID ?: eventId
            wait(attempts)
        }
    }
    return null
}

internal const val CHARGE_UNAVAILABLE_RESULT = CHARGE_RESULT_UNAVAILABLE