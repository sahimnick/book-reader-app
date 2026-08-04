package com.bookreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookreader.AppContainer
import com.bookreader.data.BookFormat
import com.bookreader.data.LibraryBook
import com.bookreader.reader.ReaderViewModel
import kotlinx.coroutines.launch

/** Top-level destinations. A back stack of one is all this app needs. */
private sealed interface Screen {
    data object Library : Screen
    data object Flashcards : Screen
    data class Reading(val book: LibraryBook) : Screen
}

/**
 * Entry point for both platforms.
 *
 * [onPickBook] is supplied by the host because document picking is the one part
 * of the flow with no shared abstraction worth building: Android needs an
 * activity result contract and iOS a `UIDocumentPickerViewController`.
 */
@Composable
fun App(
    container: AppContainer,
    onPickBook: (onPicked: (uri: String, name: String) -> Unit) -> Unit,
) {
    BookReaderTheme {
        var screen: Screen by remember { mutableStateOf(Screen.Library) }
        var books by remember { mutableStateOf<List<LibraryBook>>(emptyList()) }
        var dueCount by remember { mutableStateOf(0L) }
        var isImporting by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        suspend fun refresh() {
            books = container.books.all()
            dueCount = container.flashcards.dueCount()
        }

        LaunchedEffect(Unit) {
            container.warmUp()
            refresh()
        }

        when (val current = screen) {
            is Screen.Reading -> {
                val viewModel = remember(current.book.id) { ReaderViewModel(container, scope) }
                LaunchedEffect(current.book.id) { viewModel.open(current.book) }
                ReaderScreen(
                    viewModel = viewModel,
                    onBack = {
                        viewModel.close()
                        screen = Screen.Library
                        scope.launch { refresh() }
                    },
                )
            }

            else -> Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = current is Screen.Library,
                            onClick = { screen = Screen.Library },
                            icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                            label = { Text("Library") },
                        )
                        NavigationBarItem(
                            selected = current is Screen.Flashcards,
                            onClick = { screen = Screen.Flashcards },
                            icon = { Icon(Icons.Default.Style, contentDescription = null) },
                            label = { Text(if (dueCount > 0) "Cards ($dueCount)" else "Cards") },
                        )
                    }
                },
                floatingActionButton = {
                    if (current is Screen.Library) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                onPickBook { uri, name ->
                                    scope.launch {
                                        isImporting = true
                                        message = importBook(container, uri, name)
                                        refresh()
                                        isImporting = false
                                    }
                                }
                            },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text("Add book") },
                        )
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (current) {
                        is Screen.Library -> LibraryScreen(
                            books = books,
                            isImporting = isImporting,
                            message = message,
                            onOpen = { screen = Screen.Reading(it) },
                            onDelete = { book ->
                                scope.launch {
                                    container.books.remove(book.id)
                                    container.fileStorage.delete(book.filePath)
                                    refresh()
                                }
                            },
                            onDismissMessage = { message = null },
                        )

                        is Screen.Flashcards -> FlashcardsScreen(
                            container = container,
                            onChanged = { scope.launch { refresh() } },
                        )

                        is Screen.Reading -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Copies a picked file into app storage and reads enough metadata to list it.
 *
 * The title comes from the EPUB package when available rather than the file
 * name, because downloaded books are routinely named things like
 * `9780141036144.epub`.
 */
private suspend fun importBook(
    container: AppContainer,
    uri: String,
    name: String,
): String {
    val format = BookFormat.fromPath(name)
        ?: return "Only EPUB and PDF files are supported"

    val storedPath = container.fileStorage.importBook(uri, name)
        ?: return "Could not copy that file into the library"

    if (container.books.byPath(storedPath) != null) return "That book is already in your library"

    var title = name.substringBeforeLast('.')
    var author: String? = null
    var unitCount = 0

    when (format) {
        BookFormat.EPUB -> {
            val bytes = container.fileStorage.readFile(storedPath)
            if (bytes != null) {
                runCatching {
                    com.bookreader.core.epub.EpubBook.open(
                        bytes,
                        com.bookreader.platform.platformInflater(),
                    )
                }.onSuccess { book ->
                    title = book.metadata.title
                    author = book.metadata.author
                    unitCount = book.chapterCount
                }.onFailure {
                    container.fileStorage.delete(storedPath)
                    return "That EPUB could not be opened"
                }
            }
        }

        BookFormat.PDF -> {
            val document = com.bookreader.platform.openPdfDocument(
                container.platformContext,
                storedPath,
            )
            if (document == null) {
                container.fileStorage.delete(storedPath)
                return "That PDF could not be opened"
            }
            unitCount = document.pageCount
            document.close()
        }
    }

    container.books.add(title, author, format, storedPath, null, unitCount)
    return "Added \"$title\""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    books: List<LibraryBook>,
    isImporting: Boolean,
    message: String?,
    onOpen: (LibraryBook) -> Unit,
    onDelete: (LibraryBook) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("My library") })

        if (isImporting) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text("Importing…")
            }
        }

        message?.let {
            Card(
                Modifier.fillMaxWidth().padding(16.dp, 8.dp).clickable(onClick = onDismissMessage),
            ) {
                Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (books.isEmpty() && !isImporting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No books yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add an EPUB or PDF to start reading",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookRow(book, onOpen = { onOpen(book) }, onDelete = { onDelete(book) })
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: LibraryBook, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp, 60.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    book.format.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.unitCount > 0) {
                    Text(
                        if (book.format == BookFormat.PDF) "${book.unitCount} pages"
                        else "${book.unitCount} chapters",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove from library")
            }
        }
    }
}
