package com.bookreader.core.zip

/**
 * Raw DEFLATE decompression, supplied by the platform.
 *
 * This is an interface rather than an `expect fun` on purpose: it is the only
 * part of ZIP reading that genuinely needs platform code, and injecting it keeps
 * [ZipArchive] — the part with all the offset arithmetic worth testing —
 * compilable and testable off-device.
 */
interface RawInflater {
    /**
     * Inflates [length] bytes of headerless DEFLATE data starting at [offset].
     * [expectedSize] is the uncompressed size recorded in the ZIP entry.
     */
    fun inflate(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray
}

class ZipFormatException(message: String) : Exception(message)

data class ZipEntry(
    val name: String,
    val compressionMethod: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

private const val EOCD_SIGNATURE = 0x06054b50
private const val CENTRAL_FILE_HEADER_SIGNATURE = 0x02014b50
private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
private const val ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50
private const val ZIP64_EOCD_SIGNATURE = 0x06064b50

private const val METHOD_STORED = 0
private const val METHOD_DEFLATED = 8

private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

private fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

private fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)

private fun ByteArray.u64(i: Int): Long = u32(i) or (u32(i + 4) shl 32)

/**
 * A read-only ZIP reader over an in-memory archive.
 *
 * EPUB files are small enough (typically a few MB) that holding the container in
 * memory is simpler and faster than streaming, and it lets chapters be decoded
 * lazily and out of order as the reader jumps around the spine.
 */
class ZipArchive private constructor(
    private val bytes: ByteArray,
    private val inflater: RawInflater,
    val entries: Map<String, ZipEntry>,
) {

    val names: Set<String> get() = entries.keys

    fun contains(name: String): Boolean = entries.containsKey(name)

    /** Returns the decompressed bytes of [name], or null when absent. */
    fun read(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        if (entry.isDirectory) return ByteArray(0)

        val local = entry.localHeaderOffset.toIntChecked("local header offset")
        if (local + 30 > bytes.size) throw ZipFormatException("Truncated local header for ${entry.name}")
        if (bytes.u32(local).toInt() != LOCAL_FILE_HEADER_SIGNATURE) {
            throw ZipFormatException("Bad local header signature for ${entry.name}")
        }

        // The local header repeats the name/extra lengths, and they can differ
        // from the central directory copy — the local values are authoritative
        // for locating the data.
        val nameLen = bytes.u16(local + 26)
        val extraLen = bytes.u16(local + 28)
        val dataStart = local + 30 + nameLen + extraLen

        val compressed = entry.compressedSize.toIntChecked("compressed size")
        val uncompressed = entry.uncompressedSize.toIntChecked("uncompressed size")
        if (dataStart + compressed > bytes.size) {
            throw ZipFormatException("Truncated data for ${entry.name}")
        }

        return when (entry.compressionMethod) {
            METHOD_STORED -> bytes.copyOfRange(dataStart, dataStart + compressed)
            METHOD_DEFLATED -> inflater.inflate(bytes, dataStart, compressed, uncompressed)
            else -> throw ZipFormatException(
                "Unsupported compression method ${entry.compressionMethod} for ${entry.name}",
            )
        }
    }

    /** Reads [name] as UTF-8 text, tolerating a leading byte-order mark. */
    fun readText(name: String): String? {
        val raw = read(name) ?: return null
        return raw.decodeUtf8StrippingBom()
    }

    private fun Long.toIntChecked(what: String): Int {
        if (this < 0 || this > Int.MAX_VALUE) throw ZipFormatException("$what out of range: $this")
        return toInt()
    }

    companion object {
        /**
         * Parses the central directory of [bytes].
         *
         * The central directory is used rather than walking local headers because
         * it is the only reliable way to enumerate entries: local headers may use
         * streaming data descriptors with zeroed sizes.
         */
        fun open(bytes: ByteArray, inflater: RawInflater): ZipArchive {
            val eocd = findEndOfCentralDirectory(bytes)
                ?: throw ZipFormatException("Not a ZIP archive (no end-of-central-directory record)")

            var entryCount = bytes.u16(eocd + 10)
            var centralDirOffset = bytes.u32(eocd + 16)

            // ZIP64: the 32-bit fields saturate and the real values live in the
            // ZIP64 record pointed at by the locator just before the EOCD.
            if (entryCount == 0xFFFF || centralDirOffset == 0xFFFFFFFFL) {
                val locator = eocd - 20
                if (locator >= 0 && bytes.u32(locator).toInt() == ZIP64_EOCD_LOCATOR_SIGNATURE) {
                    val z64 = bytes.u64(locator + 8)
                    val z64Offset = z64.toInt()
                    if (z64Offset >= 0 && z64Offset + 56 <= bytes.size &&
                        bytes.u32(z64Offset).toInt() == ZIP64_EOCD_SIGNATURE
                    ) {
                        entryCount = bytes.u64(z64Offset + 32).toInt()
                        centralDirOffset = bytes.u64(z64Offset + 48)
                    }
                }
            }

            var p = centralDirOffset.toInt()
            if (p < 0 || p > bytes.size) throw ZipFormatException("Central directory offset out of range")

            val entries = LinkedHashMap<String, ZipEntry>(maxOf(entryCount, 8))
            var read = 0
            while (read < entryCount && p + 46 <= bytes.size) {
                if (bytes.u32(p).toInt() != CENTRAL_FILE_HEADER_SIGNATURE) break

                val method = bytes.u16(p + 10)
                var compressedSize = bytes.u32(p + 20)
                var uncompressedSize = bytes.u32(p + 24)
                val nameLen = bytes.u16(p + 28)
                val extraLen = bytes.u16(p + 30)
                val commentLen = bytes.u16(p + 32)
                var localOffset = bytes.u32(p + 42)

                val nameStart = p + 46
                if (nameStart + nameLen > bytes.size) break
                val name = bytes.copyOfRange(nameStart, nameStart + nameLen).decodeToString()

                // Pull oversized values from the ZIP64 extra field when present.
                if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL ||
                    localOffset == 0xFFFFFFFFL
                ) {
                    val extraStart = nameStart + nameLen
                    var e = extraStart
                    val extraEnd = minOf(extraStart + extraLen, bytes.size)
                    while (e + 4 <= extraEnd) {
                        val headerId = bytes.u16(e)
                        val dataSize = bytes.u16(e + 2)
                        if (headerId == 0x0001) {
                            var f = e + 4
                            if (uncompressedSize == 0xFFFFFFFFL && f + 8 <= extraEnd) {
                                uncompressedSize = bytes.u64(f); f += 8
                            }
                            if (compressedSize == 0xFFFFFFFFL && f + 8 <= extraEnd) {
                                compressedSize = bytes.u64(f); f += 8
                            }
                            if (localOffset == 0xFFFFFFFFL && f + 8 <= extraEnd) {
                                localOffset = bytes.u64(f)
                            }
                            break
                        }
                        e += 4 + dataSize
                    }
                }

                entries[name] = ZipEntry(
                    name = name,
                    compressionMethod = method,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localOffset,
                )

                p = nameStart + nameLen + extraLen + commentLen
                read++
            }

            if (entries.isEmpty()) throw ZipFormatException("ZIP central directory contained no entries")
            return ZipArchive(bytes, inflater, entries)
        }

        /** Scans backwards for the EOCD signature, allowing for a trailing comment. */
        private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
            if (bytes.size < 22) return null
            val minStart = maxOf(0, bytes.size - 22 - 0xFFFF)
            var i = bytes.size - 22
            while (i >= minStart) {
                if (bytes.u32(i).toInt() == EOCD_SIGNATURE) return i
                i--
            }
            return null
        }
    }
}

/** Decodes UTF-8, dropping a BOM that would otherwise appear as U+FEFF in the text. */
fun ByteArray.decodeUtf8StrippingBom(): String {
    val hasBom = size >= 3 &&
        u8(0) == 0xEF && u8(1) == 0xBB && u8(2) == 0xBF
    return if (hasBom) copyOfRange(3, size).decodeToString() else decodeToString()
}
