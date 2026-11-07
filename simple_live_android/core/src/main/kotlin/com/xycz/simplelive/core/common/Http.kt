package com.xycz.simplelive.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpResult(val code: Int, val headers: Headers, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 不跟随重定向，用于解析短链 */
    private val noRedirectClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun buildUrl(url: String, query: Map<String, Any?>): String {
        if (query.isEmpty()) return url
        val builder = url.toHttpUrl().newBuilder()
        for ((k, v) in query) {
            builder.addQueryParameter(k, v?.toString() ?: "")
        }
        return builder.build().toString()
    }

    suspend fun request(
        url: String,
        method: String = "GET",
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        followRedirects: Boolean = true,
        allowErrorStatus: Boolean = false,
    ): HttpResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val builder = Request.Builder().url(buildUrl(url, query))
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        builder.method(method, body)
        val c = if (followRedirects) client else noRedirectClient
        try {
            c.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                CoreLog.d("HTTP $method ${resp.code} ${System.currentTimeMillis() - start}ms $url")
                if (!resp.isSuccessful && !allowErrorStatus && resp.code !in 300..399) {
                    throw CoreError("请求失败：HTTP ${resp.code}", resp.code)
                }
                HttpResult(resp.code, resp.headers, bytes)
            }
        } catch (e: CoreError) {
            throw e
        } catch (e: Exception) {
            CoreLog.e("HTTP $method 失败 $url", e)
            throw CoreError("网络请求失败：${e.message}", cause = e)
        }
    }

    suspend fun getText(
        url: String,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): String = request(url, query = query, headers = headers).text

    suspend fun getJson(
        url: String,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = parseJson(getText(url, query, headers))

    suspend fun postForm(
        url: String,
        form: String,
        headers: Map<String, String> = emptyMap(),
    ): String = request(
        url,
        method = "POST",
        headers = headers,
        body = form.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
    ).text

    suspend fun postBytes(
        url: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray = request(
        url,
        method = "POST",
        headers = headers,
        body = bytes.toRequestBody(contentType.toMediaType()),
    ).body

    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): HttpResult = request(
        url,
        method = "HEAD",
        headers = headers,
        followRedirects = followRedirects,
        allowErrorStatus = true,
    )
}

/** 从 Set-Cookie 里取出指定名字的 cookie，拼成 "a=1;b=2;" */
fun Headers.pickCookies(vararg names: String): String = buildString {
    for (value in values("set-cookie")) {
        val pair = value.substringBefore(";")
        if (names.any { pair.startsWith("$it=") }) append(pair).append(';')
    }
}
