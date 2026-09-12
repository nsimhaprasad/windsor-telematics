package io.windsor.telematics.internal

const val V11_PROTOCOL_VERSION = 17
const val PASSWORD_MAX_LEN = 16

const val LOGIN_DISPATCHER_TEMPLATE_HEX =
    "11005600882c60c183060c183060c183060c183060c183060c183060c183060c183060c183" +
        "060c183060c183060c183060c1ab06200000000020200468acf134468acf1342468acf134" +
        "2468acf1342000000000100a0"

internal data class V11Dispatcher(
    val uid: String? = null,
    val token: String? = null,
    val applicationID: String? = null,
    val vin: String? = null,
    val messageID: Int? = null,
    val result: Int? = null,
    val errorMessage: String? = null,
)

internal fun encodeLoginApp(password: String, deviceId: String): ByteArray {
    val w = BitWriter()
    w.write(1, 1)
    w.writeString(password.take(PASSWORD_MAX_LEN), 6, 30)
    w.writeString(deviceId, 1, 200)
    return w.toByteArray()
}

internal fun buildLoginBody(phone: String, password: String, deviceId: String, nowSec: Long): String {
    val dispatcher = LOGIN_DISPATCHER_TEMPLATE_HEX.hexToBytes()
    val app = encodeLoginApp(password, deviceId)
    setFixed7bit(dispatcher, 48, phone.padStart(50, '0'))
    setMsbBits(dispatcher, 419, 32, nowSec.toInt())
    val n = dispatcher.size
    val appDouble = app.size * 2
    dispatcher[n - 7] = (appDouble ushr 24).toByte()
    dispatcher[n - 6] = (appDouble ushr 16).toByte()
    dispatcher[n - 5] = (appDouble ushr 8).toByte()
    dispatcher[n - 4] = appDouble.toByte()
    dispatcher[n - 3] = 1
    dispatcher[n - 2] = (160 ushr 8).toByte()
    dispatcher[n - 1] = 160.toByte()
    val payload = dispatcher + app
    val rawWithoutLength = "1" + payload.toHexUpper()
    return "%04X".format(rawWithoutLength.length + 4) + rawWithoutLength
}

internal fun decodeLoginResponse(raw: String): Pair<String, String> {
    require(raw.length >= 5 && raw[4] == '1') { "Unexpected TAP login response framing" }
    val payload = raw.substring(5).hexToBytes()
    require(payload.size >= 4) { "TAP login response is too short" }
    loginErrorFromResponse(payload)?.let { throw it }
    val dispatcherLen = (payload[2].toInt() and 0xFF) + ((payload[3].toInt() and 0xFF) shl 8)
    require(dispatcherLen <= payload.size) { "TAP login response is too short" }
    val dispatcher = payload.copyOfRange(0, dispatcherLen)
    val app = payload.copyOfRange(dispatcherLen, payload.size)
    require(dispatcher.size >= 50) { "Login rejected by MG India server (check phone/password, or the account may not be authorized for this app)" }
    require(app.size >= 11) { "Login rejected by MG India server (check phone/password, or the account may not be authorized for this app)" }
    val uid = readFixed7bit(dispatcher, 300, 14).padStart(50, '0')
    val reader = BitReader(app)
    reader.read(6)
    val token = reader.readString(40, 40)
    val refresh = reader.readString(40, 40)
    require(token == refresh) { "Login token and refresh token differ" }
    return uid to token
}

/**
 * Decode a V11 dispatcher body (login payload starts after the 4-byte header).
 */
internal fun decodeV11Dispatcher(bytes: ByteArray, startOffsetBits: Int = 4 * 8): V11Dispatcher {
    val r = BitReader(bytes, startOffsetBits)
    val uidP = r.read(1); val tokenP = r.read(1); val vinP = r.read(1); val eventIdP = r.read(1)
    val counterP = r.read(1); val ackP = r.read(1); val statelessP = r.read(1); val crqmP = r.read(1)
    val posP = r.read(1); val netP = r.read(1); val simP = r.read(1); val langP = r.read(1)
    val encP = r.read(1); val testP = r.read(1)
    val resultP = r.read(1); val errP = r.read(1)

    val uid = if (uidP == 1) r.readString(50, 50) else null
    val token = if (tokenP == 1) r.readString(40, 40) else null
    val appId = r.readString(3, 3)
    val vin = if (vinP == 1) r.readString(17, 17) else null
    r.readLong(32) // eventCreationTime
    if (eventIdP == 1) r.readLong(48) // eventID
    val messageID = r.read(8)
    if (counterP == 1) { r.read(8); r.read(8) } // messageCounter
    if (ackP == 1) r.read(1)
    if (statelessP == 1) r.read(1)
    if (crqmP == 1) r.read(1)
    if (posP == 1) { r.read(28); r.read(29) } // basicPosition
    if (netP == 1) { repeat(4) { r.readNumericString(3) }; r.readConstrained(0, 99) } // networkInfo
    if (simP == 1) r.readNumericString(19)
    if (langP == 1) r.read(3)
    r.readNumericString(20) // iccID
    r.readLong(32) // applicationDataLength (required in V11)
    if (encP == 1) r.read(2)
    r.read(16) // applicationDataProtocolVersion (required in V11)
    if (testP == 1) r.readConstrained(1, 3)
    val result = if (resultP == 1) r.read(16) else null
    val errorMessage = if (errP == 1) r.readOctets(1, 1024).toString(Charsets.UTF_8).trim() else null

    return V11Dispatcher(
        uid = uid,
        token = token,
        applicationID = appId,
        vin = vin,
        messageID = messageID,
        result = result,
        errorMessage = errorMessage,
    )
}

/** Surface the server's login rejection, if any. */
private fun loginErrorFromResponse(payload: ByteArray): io.windsor.telematics.LoginRejectedException? {
    val dispatcher = try {
        decodeV11Dispatcher(payload, 4 * 8)
    } catch (e: Exception) {
        return null
    }
    val result = dispatcher.result
    if (result == null || result == 0) return null
    val detail = dispatcher.errorMessage ?: "result $result"
    return io.windsor.telematics.LoginRejectedException("MG India login rejected: $detail")
}