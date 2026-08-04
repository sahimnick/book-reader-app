package com.bookreader.verify

import com.bookreader.core.zip.RawInflater
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Mirrors the Android implementation; used to exercise ZipArchive off-device. */
object JvmInflater : RawInflater {
    override fun inflate(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray {
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

/** Builds a real ZIP so the tests exercise actual archive bytes, not a stub. */
fun buildZip(entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet()): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zos ->
        for ((name, data) in entries) {
            val entry = ZipEntry(name)
            if (name in stored) {
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                entry.crc = CRC32().apply { update(data) }.value
            } else {
                entry.method = ZipEntry.DEFLATED
            }
            zos.putNextEntry(entry)
            zos.write(data)
            zos.closeEntry()
        }
    }
    return bos.toByteArray()
}

fun buildZip(vararg entries: Pair<String, String>): ByteArray =
    buildZip(entries.map { it.first to it.second.toByteArray(Charsets.UTF_8) })
