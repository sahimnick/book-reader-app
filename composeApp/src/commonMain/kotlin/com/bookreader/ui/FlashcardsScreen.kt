package com.bookreader.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bookreader.AppContainer
import com.bookreader.core.srs.ReviewGrade
import com.bookreader.core.srs.SpacedRepetition
import com.bookreader.core.srs.StudySession
import com.bookreader.data.Flashcard
import kotlinx.coroutines.launch

/**
 * Flashcard list and study session.
 *
 * The study flow is the standard two-phase one: the word alone, then the answer
 * with four grade buttons. Grades feed straight into SM-2, and each button shows
 * the interval it would produce so the choice is informed rather than arbitrary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashcardsScreen(container: AppContainer, onChanged: () -> Unit) {
    var cards by remember { mutableStateOf<List<Flashcard>>(emptyList()) }
    var session by remember { mutableStateOf<StudySession<Flashcard>?>(null) }
    var currentCard by remember { mutableStateOf<Flashcard?>(null) }
    var dueCards by remember { mutableStateOf<List<Flashcard>>(emptyList()) }
    var studying by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        cards = container.flashcards.all()
        dueCards = container.flashcards.due()
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (studying) "Study" else "Flashcards") },
            actions = {
                if (studying) {
                    session?.let {
                        Text(
                            "${it.completed} / ${it.total}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    TextButton(onClick = { studying = false; revealed = false }) { Text("Done") }
                }
            },
        )

        when {
            studying -> {
                val card = currentCard
                if (card == null) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("All caught up", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "No cards are due right now.",
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { studying = false }) { Text("Back") }
                        }
                    }
                } else {
                    StudyCard(
                        card = card,
                        revealed = revealed,
                        onReveal = { revealed = true },
                        onGrade = { grade ->
                            scope.launch {
                                // SM-2 sets the next *day*; the session queue
                                // decides whether the card returns before the
                                // sitting ends. A failed card comes back.
                                container.flashcards.grade(card, grade)
                                revealed = false
                                currentCard = session?.grade(grade)
                                cards = container.flashcards.all()
                                onChanged()
                            }
                        },
                    )
                }
            }

            cards.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text("No flashcards yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap any word while reading to look it up, then add it here to study.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${cards.size} cards · ${dueCards.size} due",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            val fresh = StudySession(dueCards) { it.id }
                            session = fresh
                            currentCard = fresh.current()
                            studying = true
                            revealed = false
                        },
                        enabled = dueCards.isNotEmpty(),
                    ) { Text("Study now") }
                }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        CardRow(
                            card = card,
                            onDelete = {
                                scope.launch {
                                    container.flashcards.delete(card.id)
                                    reload()
                                    onChanged()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardRow(card: Flashcard, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    card.word,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (card.persian.isNotBlank()) {
                    Text(
                        card.persian,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (card.english.isNotBlank()) {
                    Text(
                        card.english,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    scheduleLabel(card),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete card")
            }
        }
    }
}

private fun scheduleLabel(card: Flashcard): String = when {
    card.review.isNew -> "New"
    card.review.intervalDays <= 1 -> "Review: tomorrow"
    else -> "Review: every ${card.review.intervalDays} days"
}

@Composable
private fun StudyCard(
    card: Flashcard,
    revealed: Boolean,
    onReveal: () -> Unit,
    onGrade: (ReviewGrade) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !revealed, onClick = onReveal),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    card.word,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                card.pronunciation?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }

                if (!revealed) {
                    // The cloze belongs on the front: it is the sentence the
                    // word was met in with the word removed, so it prompts
                    // recall without giving the answer away.
                    if (card.cloze.isNotBlank()) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            card.cloze,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Tap to reveal",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Spacer(Modifier.height(18.dp))
                    if (card.persian.isNotBlank()) {
                        Text(
                            card.persian,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (card.english.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            card.english,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (card.example.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            card.example,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (card.mnemonic.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            card.mnemonic,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (card.sourceContext.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "“${card.sourceContext}”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                        )
                        card.sourceTitle?.let {
                            Text(
                                "— $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        if (revealed) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GradeButton("Again", card, ReviewGrade.AGAIN, Modifier.weight(1f), onGrade)
                GradeButton("Hard", card, ReviewGrade.HARD, Modifier.weight(1f), onGrade)
                GradeButton("Good", card, ReviewGrade.GOOD, Modifier.weight(1f), onGrade)
                GradeButton("Easy", card, ReviewGrade.EASY, Modifier.weight(1f), onGrade)
            }
        } else {
            Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) { Text("Show answer") }
        }
    }
}

@Composable
private fun GradeButton(
    label: String,
    card: Flashcard,
    grade: ReviewGrade,
    modifier: Modifier,
    onGrade: (ReviewGrade) -> Unit,
) {
    val days = SpacedRepetition.previewInterval(card.review, grade)
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                if (days <= 1) "1d" else "${days}d",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    if (grade == ReviewGrade.AGAIN) {
        OutlinedButton(onClick = { onGrade(grade) }, modifier = modifier) { content() }
    } else {
        FilledTonalButton(onClick = { onGrade(grade) }, modifier = modifier) { content() }
    }
}
