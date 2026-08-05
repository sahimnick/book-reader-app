package com.bookreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpURLConnection rather than a client library: the app makes a handful of
 * small GETs and nothing else, so adding Ktor or OkHttp would cost a dependency
 * on both platforms to save a dozen lines here.
 */
actual suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            // The translate endpoint returns 403 to the default Java agent.
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BookReader/1.0")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
