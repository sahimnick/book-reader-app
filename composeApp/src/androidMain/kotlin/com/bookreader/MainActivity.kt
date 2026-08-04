package com.bookreader

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.bookreader.ui.App

class BookReaderApplication : Application() {
    /** One container for the process; the reader and library share its state. */
    val container: AppContainer by lazy { AppContainer(this) }
}

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as BookReaderApplication).container

    /** Set just before launching the picker and consumed by its callback. */
    private var onDocumentPicked: ((String, String) -> Unit)? = null

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val callback = onDocumentPicked
        onDocumentPicked = null
        if (uri != null && callback != null) {
            // Persist the grant so a later import retry can still read the file.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            callback(uri.toString(), displayName(uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            App(
                container = container,
                onPickBook = { onPicked ->
                    onDocumentPicked = onPicked
                    openDocument.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            // Some providers report EPUBs with a generic type.
                            "application/octet-stream",
                        ),
                    )
                },
            )
        }
    }

    /**
     * Resolves the user-visible file name behind a content URI. The last path
     * segment is usually an opaque document id, which would leave the library
     * showing rows named things like `msf:1043`.
     */
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "book.epub"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) container.dispose()
    }
}
