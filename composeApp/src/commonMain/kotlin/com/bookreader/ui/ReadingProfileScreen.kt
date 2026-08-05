package com.bookreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookreader.AppContainer
import com.bookreader.core.vocab.ReaderProfile
import com.bookreader.core.vocab.TextFit
import com.bookreader.core.vocab.WordStat
import com.bookreader.data.BookSampler
import com.bookreader.data.LibraryBook

/**
 * What the app has worked out about the reader's English, and what to read next.
 *
 * Built entirely from what they have already done — the words they stopped on
 * and the cards they keep failing — so it needs no test, no setup and no
 * network. It is a heuristic, and the screen says so: the point is to sort
 * books into "comfortable" and "a stretch", not to issue a grade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingProfileScreen(container: AppContainer, onBack: () -> Unit) {
    var profile by remember { mutableStateOf(ReaderProfile()) }
    var topWords by remember { mutableStateOf<List<WordStat>>(emptyList()) }
    var books by remember { mutableStateOf<List<LibraryBook>>(emptyList()) }
    var fits by remember { mutableStateOf<Map<String, TextFit>>(emptyMap()) }
    var assessing by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        profile = container.vocabulary.profile()
        topWords = container.vocabulary.mostLookedUp()
        books = container.books.all()

        // Each book is sampled and scored one at a time, filling the list in as
        // it goes: opening a PDF can mean a page of OCR, and a screen that
        // waits for the slowest book shows nothing for several seconds.
        for (book in books) {
            val text = BookSampler.sample(container.platformContext, container.fileStorage, book)
            if (text != null) {
                fits = fits + (book.id to container.vocabulary.assess(text))
            }
        }
        assessing = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            LevelCard(profile, topWords.sumOf { it.lookups })

            Spacer(Modifier.height(20.dp))
            Text("Words you look up most", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (topWords.isEmpty()) {
                Text(
                    "Nothing yet. Tap a word while reading and it will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                topWords.forEach { stat ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stat.word, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (stat.lookups == 1) "once" else "${stat.lookups} times",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "What to read next",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (assessing) CircularProgressIndicator(Modifier.height(18.dp))
            }
            Spacer(Modifier.height(8.dp))

            if (books.isEmpty()) {
                Text(
                    "Add books to your library and they will be sorted by how hard " +
                        "they are for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                // Easiest first: the list answers "what can I read now".
                val ordered = books.sortedBy { fits[it.id]?.unknownRatio ?: Float.MAX_VALUE }
                ordered.forEach { book ->
                    BookFitRow(book, fits[book.id], assessing)
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "This is an estimate from the shape of the words — their length, " +
                    "syllables and endings — checked against the words you have " +
                    "actually looked up. It is a guide, not a grade.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun LevelCard(profile: ReaderProfile, totalLookups: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (profile.isEstablished) "Your level is settling in"
                else "Still getting to know you",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (profile.isEstablished) {
                    "$totalLookups look-ups across ${profile.lookedUp.size} words. " +
                        "Words you have stopped on before are marked wherever they " +
                        "turn up again, along with words of similar difficulty."
                } else {
                    "${ReaderProfile.MIN_SAMPLE} look-ups are needed before your " +
                        "level means anything — $totalLookups so far. Until then, " +
                        "only clearly difficult words are marked."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            if (profile.struggling.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "${profile.struggling.size} words on cards you keep getting " +
                        "wrong are always marked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun BookFitRow(book: LibraryBook, fit: TextFit?, stillAssessing: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        fit != null -> {
                            val percent = (fit.unknownRatio * 100).toInt()
                            "about $percent% of words new to you"
                        }
                        stillAssessing -> "checking…"
                        else -> "could not be checked"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            fit?.let {
                Text(
                    when (it.band) {
                        TextFit.Band.COMFORTABLE -> "Comfortable"
                        TextFit.Band.STRETCHING -> "A stretch"
                        TextFit.Band.HARD -> "Hard"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = when (it.band) {
                        TextFit.Band.COMFORTABLE -> MaterialTheme.colorScheme.primary
                        TextFit.Band.STRETCHING -> MaterialTheme.colorScheme.tertiary
                        TextFit.Band.HARD -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}
