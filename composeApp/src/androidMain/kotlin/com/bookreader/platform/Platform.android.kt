package com.bookreader.platform

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.bookreader.core.zip.RawInflater
import com.bookreader.db.BookReaderDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.Inflater

actual typealias PlatformContext = Context

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun platformName(): String = "Android"

actual fun platformInflater(): RawInflater = AndroidInflater

private object AndroidInflater : RawInflater {
    override fun inflate(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray {
        // `true` selects raw DEFLATE: ZIP entries carry no zlib header.
        val inflater = Inflater(true)
        try {
            inflater.setInput(data, offset, length)
            val out = ByteArrayOutputStream(if (expectedSize > 0) expectedSize else 8192)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

actual class DatabaseDriverFactory actual constructor(private val context: PlatformContext) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(BookReaderDb.Schema, context, "bookreader.db")
}

actual class FileStorage actual constructor(private val context: PlatformContext) {

    private val booksDir: File
        get() = File(context.filesDir, "books").apply { if (!exists()) mkdirs() }

    actual suspend fun importBook(sourceUri: String, suggestedName: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(sourceUri)
                val safeName = suggestedName.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
                    .ifBlank { "book_${System.currentTimeMillis()}" }
                val target = uniqueFile(safeName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                target.absolutePath
            }.getOrNull()
        }

    private fun uniqueFile(name: String): File {
        var candidate = File(booksDir, name)
        var i = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(booksDir, "$base($i)$suffix")
            i++
        }
        return candidate
    }

    actual suspend fun readFile(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(path).readBytes() }.getOrNull()
    }

    actual suspend fun writeFile(name: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val target = File(booksDir, name)
            target.writeBytes(bytes)
            target.absolutePath
        }

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    actual fun fileName(path: String): String = File(path).name
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
