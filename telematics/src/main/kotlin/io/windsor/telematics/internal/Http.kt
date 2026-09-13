package io.windsor.telematics.internal

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

/** Transport abstraction so the client can be wired to an injected HTTP layer in tests. */
internal interface Transport {
    fun post(url: String, body: String, headers: Map<String, String>): HttpResponse
    fun get(url: String, params: Map<String, String>?, headers: Map<String, String>): HttpResponse
}

/**
 * Blocking HTTP transport over OkHttp.
 *
 * The MG gateway sits behind an Azure application gateway that rejects TLS clients it does not
 * recognise (the official iSMART app and mainstream stacks get through; plain JVM/URLConnection
 * clients get 502d). OkHttp on Android uses the platform TLS stack just like the real app, and
 * on the JVM it negotiates the same modern TLS profile, which the gateway accepts.
 */
internal class Http(private val timeoutMs: Long = 30000) : Transport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "text/plain")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toByteArray(Charsets.UTF_8).let { RequestBody.create(null, it) })
            .build()
        return client.newCall(request).execute().toHttpResponse()
    }

    override fun get(url: String, params: Map<String, String>?, headers: Map<String, String>): HttpResponse {
        val suffix = params?.takeIf { it.isNotEmpty() }?.let { p ->
            "?" + p.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
        }.orEmpty()
        val request = Request.Builder()
            .url(url + suffix)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()
        return client.newCall(request).execute().toHttpResponse()
    }

    private fun Response.toHttpResponse(): HttpResponse {
        val bodyText = body?.use { it.string() }.orEmpty()
        val headers = headers.toMultimap()
            .flatMap { (k, v) -> v.map { k.lowercase() to it } }
            .toMap()
        return HttpResponse(code, headers, bodyText)
    }
}

internal data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}