package io.windsor.telematics

import io.windsor.telematics.internal.Transport
import io.windsor.telematics.internal.Http
import io.windsor.telematics.internal.buildLoginBody
import io.windsor.telematics.internal.decodeLoginResponse
import io.windsor.telematics.internal.encodeChargeStatusRequest
import io.windsor.telematics.internal.encodeStatusRequest
import io.windsor.telematics.internal.gatewaySignature
import io.windsor.telematics.internal.makeDeviceId
import io.windsor.telematics.internal.normalizePhone
import io.windsor.telematics.internal.pollTappedRequest
import io.windsor.telematics.internal.tapSignature

private const val TAP_LOGIN_URL = "https://iov-tap.mgindia.co.in/TAP.Web/ota.mp"
private const val TAP_STATUS_URL = "https://iov-tap.mgindia.co.in/TAP.Web/ota.mpv21"
private const val GATEWAY_BASE = "https://iov-gateway.mgindia.co.in/api.app/v1"
private const val USER_AGENT = "CER_IKE_01/2.3.0 (iPad; iOS 26.3; Scale/2.00)"

private const val CONTENT_TYPE = "application/json"
private const val LOGIN_ATTEMPTS = 3
private const val LOGIN_DELAY_MS = 1500L
private const val CHARGE_RESULT_UNAVAILABLE = 5

/**
 * A Kotlin client for the MG India iSMART TAP/gateway protocol, ported 1:1 from
 * the community reverse-engineered client and validated byte-for-byte against it.
 *
 * Usage: create with your iSmart India phone + password (and optionally a VIN),
 * then `login()`/`vehicles()`, and poll `status()` / `chargeStatus()`. The client
 * is not thread-safe; guard with your own lock if shared.
 */
class TelematicsClient private constructor(
    private val phone: String,
    private val password: String,
    private val requestedVin: String?,
    private val clock: () -> Long,
    private val transport: Transport,
) {
    private val deviceId: String = makeDeviceId(phone)

    private var uid: String? = null
    private var token: String? = null
    private var event = 1
    private var loggedIn = false

    /** The selected vehicle, populated by [vehicles] (or lazily by a status call). */
    var vehicle: Vehicle? = null
        private set

    companion object {
        /**
         * @param phone iSmart India phone number (10-digit; longer numbers are trimmed to the last 10 digits).
         * @param password the iSmart app password (at most 16 chars are sent, matching the app).
         * @param vin optional specific vehicle VIN; otherwise the first vehicle in the account is used.
         */
        fun create(phone: String, password: String, vin: String? = null): TelematicsClient =
            TelematicsClient(
                phone = normalizePhone(phone),
                password = password,
                requestedVin = vin,
                clock = { System.currentTimeMillis() / 1000 },
                transport = Http(),
            )

        /** Test hook: construct with a fixed clock and injected transport. */
        internal fun forTesting(
            phone: String,
            password: String,
            vin: String? = null,
            clock: () -> Long = { System.currentTimeMillis() / 1000 },
            transport: Transport = Http(),
        ): TelematicsClient = TelematicsClient(normalizePhone(phone), password, vin, clock, transport)
    }

    private fun nextEvent(): Int {
        event = (event + 1) and 0x7FFFFFFF
        return event
    }

    suspend fun login() {
        var lastError: Exception? = null
        for (attempt in 0 until LOGIN_ATTEMPTS) {
            val body = buildLoginBody(phone, password, deviceId, clock())
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Content-Type" to "text/plain",
                "Accept" to "*/*",
                "Accept-Language" to "en-US;q=1",
                "APP-SIGNATURE" to tapSignature(body),
                "SIGNATURE" to "1",
            )
            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                transport.post(TAP_LOGIN_URL, body, headers)
            }
            if (response.status >= 400) {
                lastError = TelematicsException("Login failed: HTTP ${response.status}")
            } else {
                try {
                    val (u, t) = decodeLoginResponse(response.body)
                    uid = u
                    token = t
                    loggedIn = true
                    return
                } catch (e: LoginRejectedException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    lastError = e
                }
            }
            if (attempt < LOGIN_ATTEMPTS - 1) kotlinx.coroutines.delay(LOGIN_DELAY_MS)
        }
        throw TelematicsException("Login failed after retrying transient TAP response", lastError)
    }

    private suspend fun ensureLoggedIn() {
        if (!loggedIn) login()
    }

    private suspend fun ensureVehicle() {
        if (vehicle == null) {
            if (requestedVin != null) {
                vehicle = Vehicle(requestedVin, requestedVin)
            } else {
                vehicles()
            }
        }
    }

    /** Fetch the account's vehicles and select one (per the configured VIN, or the first). */
    suspend fun vehicles(): List<Vehicle> {
        ensureLoggedIn()
        val data = gatewayGet("/vehicle/userVinList")
        val list = asJsonList(data).filter { it is kotlinx.serialization.json.JsonObject }
            .map { parseVehicle(it as kotlinx.serialization.json.JsonObject) }
        if (list.isNotEmpty()) {
            vehicle = if (requestedVin != null) list.firstOrNull { it.vin == requestedVin } ?: list.first()
            else list.first()
        }
        return list
    }

    /**
     * Full vehicle status, optionally with the EV charging frame attached.
     * [Status.charge] is null when no charging frame arrived (routine, not an error).
     */
    suspend fun status(includeCharge: Boolean = false): Status {
        ensureLoggedIn()
        ensureVehicle()
        val statusObj = pollTappedRequest(
            post = { ev -> postStatusFrame(ev) },
            want = { s, _ -> s },
            onInvalidSession = { login() },
        ) as? Status ?: throw TelematicsException("Vehicle status was not ready after polling")
        if (includeCharge) {
            val chargeObj = pollTappedRequest(
                post = { ev -> postChargeFrame(ev) },
                want = { _, c -> c },
                unavailableResults = setOf(CHARGE_RESULT_UNAVAILABLE),
                tolerateFramingErrors = true,
                onInvalidSession = { login() },
            ) as? ChargeStatus
            return statusObj.copy(charge = chargeObj)
        }
        return statusObj
    }

    /** EV charging status; raises if the poll budget expires without a charging frame. */
    suspend fun chargeStatus(): ChargeStatus {
        ensureLoggedIn()
        ensureVehicle()
        val chargeObj = pollTappedRequest(
            post = { ev -> postChargeFrame(ev) },
            want = { _, c -> c },
            unavailableResults = setOf(CHARGE_RESULT_UNAVAILABLE),
            tolerateFramingErrors = true,
            onInvalidSession = { login() },
        ) as? ChargeStatus ?: throw ChargingStatusUnavailableException(
            "Charging status was not available after polling"
        )
        return chargeObj
    }

    private suspend fun postStatusFrame(eventId: Int): String {
        val body = encodeStatusRequest(uid ?: "0".repeat(50), token ?: "0".repeat(40), vin(), eventId, clock())
        return postTapFrame(body, "Status")
    }

    private suspend fun postChargeFrame(eventId: Int): String {
        val body = encodeChargeStatusRequest(uid ?: "0".repeat(50), token ?: "0".repeat(40), vin(), eventId, clock())
        return postTapFrame(body, "Charge status")
    }

    private fun vin(): String = vehicle?.vin ?: requestedVin ?: ""

    private suspend fun postTapFrame(body: String, label: String): String {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Content-Type" to "text/plain",
            "Accept" to "*/*",
            "Accept-Language" to "en-US;q=1",
            "SIGNATURE" to "1",
            "APP-SIGNATURE" to tapSignature(body),
        )
        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            transport.post(TAP_STATUS_URL, body, headers)
        }
        if (response.status >= 400) throw TelematicsException("$label failed: HTTP ${response.status}")
        return response.body
    }

    private suspend fun gatewayGet(
        path: String,
        params: Map<String, String>? = null,
    ): kotlinx.serialization.json.JsonObject {
        if (!loggedIn) login()
        val cleanPath = "/" + path.trimStart('/')
        val query = params?.toList()?.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }.orEmpty()
        val signingPath = cleanPath + (if (query.isNotEmpty()) "?$query" else "")
        val timestamp = (clock() * 1000).toString()
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Content-Type" to CONTENT_TYPE,
            "APP-CONTENT-ENCRYPTED" to "1",
            "APP-LANGUAGE-TYPE" to "en-us",
            "APP-LOGIN-TOKEN" to (token ?: ""),
            "APP-USER-ID" to (uid ?: ""),
            "APP-SEND-DATE" to timestamp,
            "APP-VERIFICATION-STRING" to gatewaySignature(signingPath, timestamp, CONTENT_TYPE),
            "ORIGINAL-CONTENT-TYPE" to CONTENT_TYPE,
        )
        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            transport.get(GATEWAY_BASE + cleanPath, params, headers)
        }
        if (response.status >= 400) throw TelematicsException("Gateway $cleanPath failed: HTTP ${response.status}")
        val sendDate = response.header("app-send-date") ?: timestamp
        val responseContentType = response.header("original-content-type") ?: CONTENT_TYPE
        val decrypted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decrypt(body = response.body, sendDate = sendDate, contentType = responseContentType)
        }
        val parsed = jsonParse(decrypted)
        val code = (parsed["code"] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.content.toIntOrNull() }
        if (code == 7) {
            loggedIn = false
            login()
            return gatewayGet(path, params)
        }
        if (code != null && code != 0) {
            val message = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            throw TelematicsException(message ?: "Gateway error code $code")
        }
        return parsed
    }

    private suspend fun decrypt(body: String, sendDate: String, contentType: String): String =
        io.windsor.telematics.internal.decryptGatewayBody(body, sendDate, contentType)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun jsonParse(text: String): kotlinx.serialization.json.JsonObject =
        json.parseToJsonElement(text).let { if (it is kotlinx.serialization.json.JsonObject) it else kotlinx.serialization.json.buildJsonObject { } }
}

private fun asJsonList(el: kotlinx.serialization.json.JsonElement): List<kotlinx.serialization.json.JsonElement> {
    return when (el) {
        is kotlinx.serialization.json.JsonArray -> el.toList()
        is kotlinx.serialization.json.JsonObject -> {
            for (key in listOf("list", "vehicleList", "vehicles", "vinList", "result")) {
                val v = el[key]
                if (v is kotlinx.serialization.json.JsonArray) return v.toList()
            }
            val nested = el["data"]
            if (nested is kotlinx.serialization.json.JsonObject || nested is kotlinx.serialization.json.JsonArray) return asJsonList(nested)
            emptyList()
        }
        else -> emptyList()
    }
}

private fun parseVehicle(raw: kotlinx.serialization.json.JsonObject): Vehicle {
    fun str(key: String): String? = (raw[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
    val vin = str("vin") ?: str("VIN") ?: str("vinNo") ?: str("vehicleVin")
        ?: throw TelematicsException("Vehicle response did not include a VIN")
    val name = raw.str("series") ?: raw.str("modelName") ?: raw.str("brandName") ?: vin.takeLast(6)
    return Vehicle(
        vin = vin,
        name = name,
        brand = str("brandName"),
        model = raw.str("modelName") ?: raw.str("series"),
        modelYear = str("modelYear"),
        colorName = str("colorName"),
        series = str("series"),
    )
}

private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }