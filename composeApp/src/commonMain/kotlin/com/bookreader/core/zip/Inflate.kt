package com.bookreader.core.zip

/**
 * A pure-Kotlin DEFLATE decompressor (RFC 1951).
 *
 * Android has `java.util.zip.Inflater`, but Kotlin/Native has no equivalent, and
 * reaching zlib from iOS means hand-written cinterop with `z_stream` — code that
 * cannot be exercised anywhere except on a device. Implementing the algorithm in
 * common Kotlin instead means the same tested code path decompresses EPUBs on
 * every platform, and it can be verified on the JVM against archives produced by
 * a real compressor.
 *
 * The decoder follows the structure of Mark Adler's reference `puff.c`:
 * canonical Huffman tables stored as per-length symbol counts, decoded one bit
 * at a time. That is slower than a lookup-table decoder but small and easy to
 * keep correct, and chapter-sized payloads decompress imperceptibly fast.
 */
object Inflate : RawInflater {

    private const val MAX_BITS = 15
    private const val MAX_LIT_CODES = 286
    private const val MAX_DIST_CODES = 30
    private const val MAX_CODES = MAX_LIT_CODES + MAX_DIST_CODES
    private const val FIXED_LIT_CODES = 288

    /** Base lengths for length codes 257..285. */
    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )

    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )

    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577,
    )

    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )

    /** Order in which code-length code lengths are stored in a dynamic block. */
    private val CODE_LENGTH_ORDER = intArrayOf(
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
    )

    override fun inflate(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray =
        Decoder(data, offset, offset + length, expectedSize).run()

    /** Canonical Huffman table: symbol counts per bit length, plus sorted symbols. */
    private class Huffman(val count: IntArray, val symbol: IntArray)

    private class Decoder(
        private val input: ByteArray,
        start: Int,
        private val end: Int,
        expectedSize: Int,
    ) {
        private var pos = start
        private var bitBuffer = 0
        private var bitCount = 0

        private var out = ByteArray(if (expectedSize > 0) expectedSize else 1024)
        private var outLen = 0

        private var fixedLiteral: Huffman? = null
        private var fixedDistance: Huffman? = null

        fun run(): ByteArray {
            var last = false
            while (!last) {
                last = bits(1) == 1
                when (val type = bits(2)) {
                    0 -> stored()
                    1 -> {
                        buildFixedTables()
                        block(fixedLiteral!!, fixedDistance!!)
                    }
                    2 -> dynamicBlock()
                    else -> throw ZipFormatException("Invalid DEFLATE block type $type")
                }
            }
            return if (outLen == out.size) out else out.copyOf(outLen)
        }

        /** Reads [need] bits, least-significant bit first. */
        private fun bits(need: Int): Int {
            var value = bitBuffer
            while (bitCount < need) {
                if (pos >= end) throw ZipFormatException("Unexpected end of DEFLATE stream")
                value = value or ((input[pos++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            bitBuffer = value ushr need
            bitCount -= need
            return value and ((1 shl need) - 1)
        }

        private fun ensureCapacity(extra: Int) {
            if (outLen + extra <= out.size) return
            var newSize = if (out.isEmpty()) 1024 else out.size
            while (newSize < outLen + extra) newSize = newSize shl 1
            out = out.copyOf(newSize)
        }

        private fun emit(byte: Int) {
            ensureCapacity(1)
            out[outLen++] = byte.toByte()
        }

        /** Type 0: raw bytes, byte-aligned, with a length and its complement. */
        private fun stored() {
            bitBuffer = 0
            bitCount = 0
            if (pos + 4 > end) throw ZipFormatException("Truncated stored DEFLATE block")
            val len = (input[pos].toInt() and 0xFF) or ((input[pos + 1].toInt() and 0xFF) shl 8)
            val nlen = (input[pos + 2].toInt() and 0xFF) or ((input[pos + 3].toInt() and 0xFF) shl 8)
            if (len != nlen.inv() and 0xFFFF) {
                throw ZipFormatException("Stored block length check failed")
            }
            pos += 4
            if (pos + len > end) throw ZipFormatException("Truncated stored DEFLATE data")
            ensureCapacity(len)
            input.copyInto(out, outLen, pos, pos + len)
            outLen += len
            pos += len
        }

        private fun buildFixedTables() {
            if (fixedLiteral != null) return
            val litLengths = IntArray(FIXED_LIT_CODES)
            for (i in 0 until 144) litLengths[i] = 8
            for (i in 144 until 256) litLengths[i] = 9
            for (i in 256 until 280) litLengths[i] = 7
            for (i in 280 until FIXED_LIT_CODES) litLengths[i] = 8
            fixedLiteral = buildHuffman(litLengths, FIXED_LIT_CODES)

            val distLengths = IntArray(30) { 5 }
            fixedDistance = buildHuffman(distLengths, 30)
        }

        private fun dynamicBlock() {
            val litCount = bits(5) + 257
            val distCount = bits(5) + 1
            val codeLengthCount = bits(4) + 4
            if (litCount > MAX_LIT_CODES || distCount > MAX_DIST_CODES) {
                throw ZipFormatException("Too many DEFLATE codes")
            }

            val codeLengths = IntArray(19)
            for (i in 0 until codeLengthCount) {
                codeLengths[CODE_LENGTH_ORDER[i]] = bits(3)
            }
            val codeLengthTable = buildHuffman(codeLengths, 19)

            // Code lengths for the literal/length and distance alphabets are
            // themselves Huffman-coded, with three run-length escapes.
            val lengths = IntArray(MAX_CODES)
            var index = 0
            while (index < litCount + distCount) {
                val symbol = decode(codeLengthTable)
                when {
                    symbol < 16 -> lengths[index++] = symbol

                    symbol == 16 -> {
                        if (index == 0) throw ZipFormatException("DEFLATE repeat with no previous length")
                        val prev = lengths[index - 1]
                        repeat(3 + bits(2)) {
                            if (index < lengths.size) lengths[index++] = prev
                        }
                    }

                    symbol == 17 -> repeat(3 + bits(3)) {
                        if (index < lengths.size) lengths[index++] = 0
                    }

                    else -> repeat(11 + bits(7)) {
                        if (index < lengths.size) lengths[index++] = 0
                    }
                }
            }
            if (lengths[256] == 0) throw ZipFormatException("DEFLATE block has no end-of-block code")

            val literal = buildHuffman(lengths.copyOfRange(0, litCount), litCount)
            val distance = buildHuffman(
                lengths.copyOfRange(litCount, litCount + distCount),
                distCount,
            )
            block(literal, distance)
        }

        /** Decodes symbols until the end-of-block code. */
        private fun block(literal: Huffman, distance: Huffman) {
            while (true) {
                val symbol = decode(literal)
                when {
                    symbol < 256 -> emit(symbol)

                    symbol == 256 -> return

                    else -> {
                        val lengthIndex = symbol - 257
                        if (lengthIndex >= LENGTH_BASE.size) {
                            throw ZipFormatException("Invalid DEFLATE length code $symbol")
                        }
                        val length = LENGTH_BASE[lengthIndex] + bits(LENGTH_EXTRA[lengthIndex])

                        val distSymbol = decode(distance)
                        if (distSymbol >= DIST_BASE.size) {
                            throw ZipFormatException("Invalid DEFLATE distance code $distSymbol")
                        }
                        val dist = DIST_BASE[distSymbol] + bits(DIST_EXTRA[distSymbol])
                        if (dist > outLen) {
                            throw ZipFormatException("DEFLATE back-reference before start of output")
                        }

                        // Copy byte by byte: overlapping copies are legal and are
                        // how DEFLATE encodes runs (distance 1, length 100).
                        ensureCapacity(length)
                        var from = outLen - dist
                        repeat(length) {
                            out[outLen++] = out[from++]
                        }
                    }
                }
            }
        }

        private fun buildHuffman(lengths: IntArray, n: Int): Huffman {
            val count = IntArray(MAX_BITS + 1)
            for (i in 0 until n) count[lengths[i]]++
            if (count[0] == n) {
                // No codes at all — legal for an unused distance alphabet.
                return Huffman(count, IntArray(0))
            }

            // Reject over-subscribed code sets; incomplete ones are tolerated
            // because a single-symbol distance tree is valid and appears in the
            // wild.
            var left = 1
            for (len in 1..MAX_BITS) {
                left = left shl 1
                left -= count[len]
                if (left < 0) throw ZipFormatException("Over-subscribed Huffman code")
            }

            val offsets = IntArray(MAX_BITS + 1)
            for (len in 1 until MAX_BITS) {
                offsets[len + 1] = offsets[len] + count[len]
            }
            val symbol = IntArray(n)
            for (i in 0 until n) {
                if (lengths[i] != 0) symbol[offsets[lengths[i]]++] = i
            }
            return Huffman(count, symbol)
        }

        private fun decode(huffman: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..MAX_BITS) {
                code = code or bits(1)
                val count = huffman.count[len]
                if (code - count < first) {
                    return huffman.symbol[index + (code - first)]
                }
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw ZipFormatException("Invalid Huffman code in DEFLATE stream")
        }
    }
}
