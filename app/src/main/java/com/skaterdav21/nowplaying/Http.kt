package com.skaterdav21.nowplaying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

object Http {
    private fun open(url: String, headers: Map<String, String>, timeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun HttpURLConnection.readBody(): ByteArray {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw IOException("HTTP $code ${String(bytes).take(120)}")
        return bytes
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 6000): String {
        val c = open(url, headers, timeout)
        try { return String(c.readBody()) } finally { c.disconnect() }
    }

    fun bytes(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 6000): ByteArray {
        val c = open(url, headers, timeout)
        try { return c.readBody() } finally { c.disconnect() }
    }

    fun post(url: String, headers: Map<String, String>, json: String, timeout: Int = 6000): String {
        val c = open(url, headers + ("Content-Type" to "application/json"), timeout)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(json.toByteArray()) }
            return String(c.readBody())
        } finally { c.disconnect() }
    }
}

/** null for missing, JSON null, or blank. */
fun JSONObject.str(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() && it != "null" } else null

object ArtCache {
    private val cache = LruCache<String, Bitmap>(32)
    private val failed = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun get(url: String?, bearer: String?): Bitmap? {
        if (url == null) return null
        cache.get(url)?.let { return it }
        if (url in failed) return null
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val headers = if (bearer.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $bearer")
                decodeScaled(Http.bytes(url, headers), 320)
            }.getOrNull()
        }
        if (bmp != null) cache.put(url, bmp) else failed.add(url)
        return bmp
    }

    private fun decodeScaled(data: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
