package io.windsor.telematics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads goldens.json — byte-exact vectors generated with the Python reference
 * client (fixed clock 1750000000, UID="1"*50, TOKEN="2"*40, VIN="3"*17) plus
 * five real captured MG Windsor EV charging frames.
 */
object Goldens {
    private val json: Map<String, String> by lazy {
        val text = object {}.javaClass.getResourceAsStream("/goldens.json")!!.readBytes()
            .toString(Charsets.UTF_8)
        Json.parseToJsonElement(text).jsonObject.entries.associate { (k, v) ->
            k to if (v is kotlinx.serialization.json.JsonPrimitive) v.content else v.toString()
        }
    }

    fun get(key: String): String = json.getValue(key)
}