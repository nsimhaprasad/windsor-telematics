package io.windsor.telematics

/** Base error for the MG India telematics client. */
open class TelematicsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The MG India server rejected the credentials or the account is unauthorized. */
class LoginRejectedException(message: String) : TelematicsException(message)

/** The charge poll budget expired without a charging frame (or it's not available for the account). */
class ChargingStatusUnavailableException(message: String) : TelematicsException(message)