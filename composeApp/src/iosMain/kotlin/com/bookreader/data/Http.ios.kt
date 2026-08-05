package com.bookreader.data

import com.bookreader.platform.toByteArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.dataWithBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.setTimeoutInterval
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
actual suspend fun httpGet(url: String): String? = suspendCancellableCoroutine { continuation ->
    val nsUrl = NSURL.URLWithString(url)
    if (nsUrl == null) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
    }

    val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
        setHTTPMethod("GET")
        setTimeoutInterval(8.0)
        setValue("Mozilla/5.0 (iOS) BookReader/1.0", forHTTPHeaderField = "User-Agent")
        setValue("application/json", forHTTPHeaderField = "Accept")
    }

    val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, _ ->
        val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        val body = if (status in 200..299) (data as? NSData)?.toByteArray()?.decodeToString() else null
        if (continuation.isActive) continuation.resume(body)
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun httpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
): String? = suspendCancellableCoroutine { continuation ->
    val nsUrl = NSURL.URLWithString(url)
    if (nsUrl == null) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
    }

    val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
        setHTTPMethod("POST")
        setTimeoutInterval(30.0)
        headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
        setHTTPBody(body.encodeToByteArray().toNSData())
    }

    val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, _ ->
        val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        val text = if (status in 200..299) (data as? NSData)?.toByteArray()?.decodeToString() else null
        if (continuation.isActive) continuation.resume(text)
    }
    continuation.invokeOnCancellation { task.cancel() }
    task.resume()
}

/** Bytes to NSData, needed for a request body. */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }
}
