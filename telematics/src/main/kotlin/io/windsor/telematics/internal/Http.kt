package io.windsor.telematics.internal

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Transport abstraction so the client can be wired to an injected HTTP layer in tests. */
internal interface Transport {
    fun post(url: String, body: String, headers: Map<String, String>): HttpResponse
    fun get(url: String, params: Map<String, String>?, headers: Map<String, String>): HttpResponse
}

/** Blocking HTTP transport over java.net.HttpURLConnection (JVM + Android). */
internal class Http(private val timeoutMs: Int = 30000) : Transport {

    override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    override fun get(url: String, params: Map<String, String>?, headers: Map<String, String>): HttpResponse {
        val suffix = params?.takeIf { it.isNotEmpty() }?.let { p ->
            "?" + p.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
        }.orEmpty()
        val conn = (URL(url + suffix).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): HttpResponse {
        val status = conn.responseCode
        val headers = mutableMapOf<String, String>()
        var i = 0
        while (true) {
            val name = conn.getHeaderFieldKey(i) ?: break
            val value = conn.getHeaderField(i) ?: ""
            headers[name.lowercase()] = value
            i += 1
        }
        val stream: java.io.InputStream = if (status in 200..299) conn.inputStream else conn.errorStream ?: java.io.ByteArrayInputStream(byteArrayOf())
        val body = ByteArrayOutputStream().use { out -> stream.use { it.copyTo(out) }; out.toString(Charsets.UTF_8) }
        return HttpResponse(status, headers, body)
    }
}

internal data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}