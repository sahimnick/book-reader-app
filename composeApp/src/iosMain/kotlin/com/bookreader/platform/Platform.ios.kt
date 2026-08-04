package com.bookreader.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.bookreader.core.zip.Inflate
import com.bookreader.core.zip.RawInflater
import com.bookreader.db.BookReaderDb
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import org.jetbrains.skia.Image as SkiaImage
import platform.posix.memcpy

actual class PlatformContext

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun randomUuid(): String = NSUUID().UUIDString()

actual fun platformName(): String = "iOS"

/**
 * iOS uses the shared Kotlin implementation.
 *
 * Kotlin/Native has no `java.util.zip`, and binding libz through cinterop would
 * add a code path that only ever runs on a device. The common decoder is
 * covered by tests instead.
 */
actual fun platformInflater(): RawInflater = Inflate

actual class DatabaseDriverFactory actual constructor(context: PlatformContext) {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(BookReaderDb.Schema, "bookreader.db")
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

actual class FileStorage actual constructor(context: PlatformContext) {

    private val fileManager = NSFileManager.defaultManager

    private val booksDirectory: String by lazy {
        val documents = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).first() as String
        val dir = "$documents/books"
        if (!fileManager.fileExistsAtPath(dir)) {
            fileManager.createDirectoryAtPath(dir, true, null, null)
        }
        dir
    }

    actual suspend fun importBook(sourceUri: String, suggestedName: String): String? =
        withContext(Dispatchers.Default) {
            val url = NSURL.URLWithString(sourceUri) ?: NSURL.fileURLWithPath(sourceUri)

            // Files handed over by the document picker live outside the sandbox
            // and are only reachable inside a security-scoped access window.
            val scoped = url.startAccessingSecurityScopedResource()
            try {
                val data = NSData.dataWithContentsOfURL(url) ?: return@withContext null
                val safeName = suggestedName
                    .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
                    .joinToString("")
                    .ifBlank { "book_${currentTimeMillis()}" }
                val target = uniquePath(safeName)
                if (data.writeToFile(target, true)) target else null
            } finally {
                if (scoped) url.stopAccessingSecurityScopedResource()
            }
        }

    private fun uniquePath(name: String): String {
        var candidate = "$booksDirectory/$name"
        var i = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (fileManager.fileExistsAtPath(candidate)) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = "$booksDirectory/$base($i)$suffix"
            i++
        }
        return candidate
    }

    actual suspend fun readFile(path: String): ByteArray? = withContext(Dispatchers.Default) {
        val data = NSData.dataWithContentsOfURL(NSURL.fileURLWithPath(path))
        data?.toByteArray()
    }

    actual suspend fun writeFile(name: String, bytes: ByteArray): String =
        withContext(Dispatchers.Default) {
            val target = "$booksDirectory/$name"
            bytes.toNSData().writeToFile(target, true)
            target
        }

    actual fun exists(path: String): Boolean = fileManager.fileExistsAtPath(path)

    actual fun delete(path: String): Boolean = fileManager.removeItemAtPath(path, null)

    actual fun fileName(path: String): String = path.substringAfterLast('/')
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
