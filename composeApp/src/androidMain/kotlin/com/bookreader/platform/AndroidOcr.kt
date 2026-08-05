package com.bookreader.platform

import android.graphics.Bitmap
import com.bookreader.core.pdf.OcrLayout
import com.bookreader.core.pdf.OcrPage
import com.bookreader.core.pdf.RecognizedWord
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR for scanned PDFs.
 *
 * A scanned page has no text layer, so without this the reader can only show it
 * as a picture: no read-aloud, no tap-to-look-up, no selection. ML Kit's Latin
 * recogniser runs entirely on the device — no key, no network, no per-page cost
 * — which keeps the offline-first behaviour the rest of the app relies on.
 *
 * Boxes come back in pixels; they are normalised to 0..1 here so the reader's
 * existing hit-testing works unchanged, at any zoom.
 */
internal object AndroidOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap, pageId: String): OcrPage {
        val width = bitmap.width.toFloat().takeIf { it > 0f } ?: return empty(pageId)
        val height = bitmap.height.toFloat().takeIf { it > 0f } ?: return empty(pageId)

        val recognised = suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val words = ArrayList<RecognizedWord>()
                    for (block in text.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                val box = element.boundingBox ?: continue
                                words.add(
                                    RecognizedWord(
                                        text = element.text,
                                        left = box.left / width,
                                        top = box.top / height,
                                        right = box.right / width,
                                        bottom = box.bottom / height,
                                    ),
                                )
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(words)
                }
                .addOnFailureListener {
                    // A failed page is a page without text, not a crash.
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }

        // Reading order, paragraph grouping and offsets are shared, tested logic.
        return OcrLayout.layout(recognised, pageId)
    }

    private fun empty(pageId: String) = OcrLayout.layout(emptyList(), pageId)
}
