package com.bookreader

import androidx.compose.ui.window.ComposeUIViewController
import com.bookreader.platform.PlatformContext
import com.bookreader.ui.App
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIModalPresentationFormSheet
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject

/**
 * The Compose entry point the Swift app hosts.
 *
 * One container is created here and lives for the process, matching the Android
 * side, so the library, reader and flashcards all see the same database.
 */
private val container: AppContainer by lazy { AppContainer(PlatformContext()) }

fun MainViewController(): UIViewController = ComposeUIViewController {
    App(
        container = container,
        onPickBook = { onPicked -> presentDocumentPicker(onPicked) },
    )
}

/**
 * Retains the picker delegate for the lifetime of the presentation.
 *
 * `UIDocumentPickerViewController.delegate` is a weak reference; without a
 * strong reference held here the delegate is collected as soon as this function
 * returns and the callback never fires.
 */
private var pickerDelegate: DocumentPickerDelegate? = null

private fun presentDocumentPicker(onPicked: (uri: String, name: String) -> Unit) {
    val types = listOfNotNull(
        UTType.typeWithIdentifier("org.idpf.epub-container"),
        UTType.typeWithIdentifier("com.adobe.pdf"),
    )

    val picker = UIDocumentPickerViewController(forOpeningContentTypes = types)
    val delegate = DocumentPickerDelegate { url ->
        pickerDelegate = null
        val name = url.lastPathComponent ?: "book.epub"
        onPicked(url.absoluteString ?: "", name)
    }
    pickerDelegate = delegate
    picker.delegate = delegate
    picker.modalPresentationStyle = UIModalPresentationFormSheet

    val root = UIApplication.sharedApplication.keyWindow?.rootViewController
    root?.presentViewController(picker, animated = true, completion = null)
}

private class DocumentPickerDelegate(
    private val onPicked: (NSURL) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.let(onPicked)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        pickerDelegate = null
    }
}
