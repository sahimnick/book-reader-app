package com.bookreader.platform

import com.bookreader.core.pdf.OcrLayout
import com.bookreader.core.pdf.OcrPage
import com.bookreader.core.pdf.RecognizedWord
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIImage
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate

/**
 * On-device OCR for scanned PDFs, via Vision.
 *
 * Mirrors the Android path: a scanned page carries no text layer, so without
 * recognition it cannot be read aloud, tapped for a definition, or selected.
 * Vision runs entirely on the device, so the offline-first behaviour holds.
 *
 * **Word boxes here are approximate.** Vision reports geometry per recognised
 * *line*, and asking for a sub-range means `boundingBoxForRange`, whose binding
 * shape varies between Kotlin/Native releases. Rather than depend on that, each
 * line's box is divided across its words in proportion to their length. For
 * proportional type that is accurate to roughly a character width — good enough
 * to tap a word, occasionally off by one at a line end. Android, using ML Kit,
 * gets exact per-word boxes. If this becomes a problem in practice, the fix is
 * to adopt `boundingBoxForRange` and verify it on a device.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosOcr {

    fun recognize(image: UIImage, pageId: String): OcrPage {
        val cgImage = image.CGImage ?: return OcrLayout.layout(emptyList(), pageId)

        val request = VNRecognizeTextRequest(completionHandler = null).apply {
            recognitionLevel = VNRequestTextRecognitionLevelAccurate
            usesLanguageCorrection = true
        }

        val handler = VNImageRequestHandler(cGImage = cgImage, options = emptyMap<Any?, Any?>())
        val ok = runCatching { handler.performRequests(listOf(request), null) }.getOrDefault(false)
        if (!ok) return OcrLayout.layout(emptyList(), pageId)

        val words = ArrayList<RecognizedWord>()
        val observations = request.results
            ?.filterIsInstance<VNRecognizedTextObservation>()
            .orEmpty()

        for (observation in observations) {
            val candidate = observation.topCandidates(1u).firstOrNull() ?: continue
            val line = (candidate as? platform.Vision.VNRecognizedText)?.string ?: continue
            if (line.isBlank()) continue

            var left = 0.0
            var bottom = 0.0
            var width = 0.0
            var height = 0.0
            observation.boundingBox.useContents {
                left = origin.x
                // Vision's origin is bottom-left; the reader works top-left.
                bottom = origin.y
                width = size.width
                height = size.height
            }
            if (width <= 0.0 || height <= 0.0) continue

            val top = 1.0 - (bottom + height)
            words += splitLine(line, left, top, width, height)
        }

        return OcrLayout.layout(words, pageId)
    }

    /**
     * Divides a line's box across its words by character count.
     *
     * Spaces are counted so the gaps between words are preserved; otherwise
     * every word would be shifted left of where it actually sits.
     */
    private fun splitLine(
        line: String,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
    ): List<RecognizedWord> {
        val total = line.length.toDouble()
        if (total <= 0.0) return emptyList()

        val out = ArrayList<RecognizedWord>()
        var index = 0
        while (index < line.length) {
            if (line[index].isWhitespace()) {
                index++
                continue
            }
            val start = index
            while (index < line.length && !line[index].isWhitespace()) index++
            val text = line.substring(start, index)
            if (text.isBlank()) continue

            val wordLeft = left + width * (start / total)
            val wordRight = left + width * (index / total)
            out += RecognizedWord(
                text = text,
                left = wordLeft.toFloat().coerceIn(0f, 1f),
                top = top.toFloat().coerceIn(0f, 1f),
                right = wordRight.toFloat().coerceIn(0f, 1f),
                bottom = (top + height).toFloat().coerceIn(0f, 1f),
            )
        }
        return out
    }
}
