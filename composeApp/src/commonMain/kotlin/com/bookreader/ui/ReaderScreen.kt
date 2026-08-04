package com.bookreader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.core.model.BlockStyle
import com.bookreader.core.model.ContentBlock
import com.bookreader.data.BookFormat
import com.bookreader.platform.decodeImage
import com.bookreader.reader.PlaybackState
import com.bookreader.reader.ReaderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var showToc by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Follow the read-aloud position so the highlighted sentence stays visible.
    LaunchedEffect(state.currentBlock) {
        if (state.currentBlock >= 0 && state.playback == PlaybackState.SPEAKING) {
            runCatching { listState.animateScrollToItem(state.currentBlock) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.chapterTitle ?: state.book?.title.orEmpty(),
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (viewModel.tableOfContents.isNotEmpty()) {
                        IconButton(onClick = { showToc = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents")
                        }
                    }
                },
            )
        },
        bottomBar = {
            PlaybackBar(
                playback = state.playback,
                unitIndex = state.unitIndex,
                unitCount = state.unitCount,
                onToggle = viewModel::togglePlayback,
                onStop = viewModel::stopSpeaking,
                onPrevious = viewModel::skipToPreviousSentence,
                onNext = viewModel::skipToNextSentence,
                onPreviousUnit = viewModel::previousUnit,
                onNextUnit = viewModel::nextUnit,
                onSpeed = { showSpeedSheet = true },
                rate = state.speechRate,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error!!, textAlign = TextAlign.Center)
                        TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                    }
                }

                state.book?.format == BookFormat.PDF -> PdfPageView(viewModel)

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(state.document.blocks) { index, block ->
                        BlockView(
                            block = block,
                            isCurrent = index == state.currentBlock,
                            sentenceRange = sentenceRangeFor(block, viewModel, state.currentSentence),
                            wordRange = state.wordHighlight?.let { r ->
                                localRange(block, r.first, r.last + 1)
                            },
                            imageBytes = block.imageHref?.let { viewModel.epubResource(it) },
                            onWordTap = { offset -> viewModel.lookupWord(block.text, offset) },
                            onLongPress = { viewModel.playFromBlock(index) },
                        )
                    }
                }
            }
        }
    }

    state.lookup?.let { result ->
        LookupSheet(
            result = result,
            isLoading = state.isLookingUp,
            onSave = viewModel::saveCurrentLookup,
            onSpeak = { viewModel.lookup(result.entry.queried, result.context) },
            onDismiss = viewModel::dismissLookup,
        )
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                itemsIndexed(viewModel.tableOfContents) { _, entry ->
                    TextButton(
                        onClick = {
                            entry.spineIndex?.let { viewModel.showUnit(it) }
                            showToc = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            entry.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (entry.level * 16).dp),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            Column(Modifier.padding(24.dp)) {
                Text("Reading speed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("${(state.speechRate * 100).toInt()}%")
                Slider(
                    value = state.speechRate,
                    onValueChange = viewModel::setSpeechRate,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** The current sentence's range expressed in [block]'s own coordinates. */
private fun sentenceRangeFor(
    block: ContentBlock,
    viewModel: ReaderViewModel,
    currentSentence: Int,
): IntRange? {
    if (currentSentence < 0) return null
    val sentence = viewModel.sentenceAt(currentSentence) ?: return null
    return localRange(block, sentence.start, sentence.endExclusive)
}

/** Clips an absolute range in the flattened chapter text to one block. */
private fun localRange(block: ContentBlock, start: Int, endExclusive: Int): IntRange? {
    val blockStart = block.charOffset
    val blockEnd = blockStart + block.text.length
    if (endExclusive <= blockStart || start >= blockEnd) return null
    val from = (start - blockStart).coerceIn(0, block.text.length)
    val to = (endExclusive - blockStart).coerceIn(from, block.text.length)
    if (to <= from) return null
    return from until to
}

@Composable
private fun BlockView(
    block: ContentBlock,
    isCurrent: Boolean,
    sentenceRange: IntRange?,
    wordRange: IntRange?,
    imageBytes: ByteArray?,
    onWordTap: (Int) -> Unit,
    onLongPress: () -> Unit,
) {
    if (block.style == BlockStyle.IMAGE) {
        val bitmap = imageBytes?.let { decodeImage(it) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = block.text.takeIf { it.isNotBlank() },
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
        return
    }

    if (block.style == BlockStyle.SEPARATOR) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        return
    }

    val style = textStyleFor(block.style)
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val wordHighlight = MaterialTheme.colorScheme.secondaryContainer

    val annotated: AnnotatedString = remember(block.text, sentenceRange, wordRange, highlight) {
        buildAnnotatedString {
            append(block.text)
            sentenceRange?.let {
                addStyle(SpanStyle(background = highlight), it.first, it.last + 1)
            }
            wordRange?.let {
                addStyle(
                    SpanStyle(background = wordHighlight, fontWeight = FontWeight.Bold),
                    it.first,
                    it.last + 1,
                )
            }
        }
    }

    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    Text(
        text = annotated,
        style = style,
        textAlign = if (isRtlText(block.text)) TextAlign.Right else TextAlign.Start,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                else Color.Transparent,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .pointerInput(block.text) {
                detectTapGestures(
                    onTap = { position ->
                        // Map the tap to a character so the exact word can be
                        // resolved by the shared tokenizer.
                        layout?.let { result ->
                            onWordTap(result.getOffsetForPosition(position))
                        }
                    },
                    onLongPress = { onLongPress() },
                )
            },
    )
}

@Composable
private fun textStyleFor(style: BlockStyle): TextStyle = when (style) {
    BlockStyle.TITLE, BlockStyle.HEADING_1 -> MaterialTheme.typography.headlineMedium
    BlockStyle.HEADING_2 -> MaterialTheme.typography.headlineSmall
    BlockStyle.HEADING_3 -> MaterialTheme.typography.titleLarge
    BlockStyle.QUOTE -> MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
    BlockStyle.LIST_ITEM -> MaterialTheme.typography.bodyLarge
    BlockStyle.PREFORMATTED -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    BlockStyle.CAPTION -> MaterialTheme.typography.bodySmall
    else -> MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp)
}

/**
 * Renders the current PDF page and hit-tests taps against the extracted word
 * boxes, so looking a word up works the same on a PDF as in an EPUB.
 */
@Composable
private fun PdfPageView(viewModel: ReaderViewModel) {
    val state by viewModel.state.collectAsState()
    var bitmap by remember(state.unitIndex) { mutableStateOf<ImageBitmap?>(null) }
    var widthPx by remember { mutableStateOf(0) }

    LaunchedEffect(state.unitIndex, widthPx) {
        if (widthPx > 0) bitmap = viewModel.renderPdfPage(widthPx)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width },
        contentAlignment = Alignment.TopCenter,
    ) {
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator(Modifier.padding(32.dp))
        } else {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(state.unitIndex, state.pdfWords) {
                        detectTapGestures { offset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectTapGestures
                            val nx = offset.x / w
                            val ny = offset.y / h
                            val hit = state.pdfWords.firstOrNull { it.contains(nx, ny) }
                            if (hit != null) {
                                val context = state.document.flattenedText
                                viewModel.lookup(hit.text, context.take(300))
                            }
                        }
                    },
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun PlaybackBar(
    playback: PlaybackState,
    unitIndex: Int,
    unitCount: Int,
    rate: Float,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreviousUnit: () -> Unit,
    onNextUnit: () -> Unit,
    onSpeed: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Previous sentence")
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playback == PlaybackState.SPEAKING) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (playback == PlaybackState.SPEAKING) "Pause" else "Read aloud",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.FastForward, contentDescription = "Next sentence")
                }
                TextButton(onClick = onSpeed) { Text("${(rate * 100).toInt()}%") }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPreviousUnit, enabled = unitIndex > 0) { Text("Previous") }
                Text(
                    if (unitCount > 0) "${unitIndex + 1} / $unitCount" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = onNextUnit,
                    enabled = unitCount == 0 || unitIndex < unitCount - 1,
                ) { Text("Next") }
            }
        }
    }
}
