package com.bookreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bookreader.core.dictionary.Sense
import com.bookreader.reader.LookupResult

/**
 * The tap-to-look-up panel: English definition, Persian meaning, spelling, and
 * the button that turns the word into a flashcard.
 *
 * Each sense gets its own "add" action rather than a single one for the whole
 * entry, because a card carrying every meaning of a polysemous word is not a
 * card anyone can study.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookupSheet(
    result: LookupResult,
    isLoading: Boolean,
    onSave: (Sense?) -> Unit,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entry = result.entry

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.queried,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    // Show the dictionary form when it differs, so the reader can
                    // see that "running" was answered as "run".
                    if (!entry.headword.equals(entry.queried, ignoreCase = true)) {
                        Text(
                            "from “${entry.headword}”",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                if (isLoading) CircularProgressIndicator(Modifier.height(20.dp))
            }

            entry.pronunciation?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text("Spelling", style = MaterialTheme.typography.labelMedium)
            Text(entry.spelling, style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(16.dp))

            if (entry.isEmpty) {
                Text(
                    "No dictionary entry found for this word.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSave(null) }, enabled = !result.isSaved) {
                    Icon(
                        if (result.isSaved) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = null,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(if (result.isSaved) "  Saved" else "  Save anyway")
                }
            } else {
                entry.senses.forEachIndexed { index, sense ->
                    if (index > 0) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                    }
                    SenseView(
                        sense = sense,
                        isSaved = result.isSaved,
                        onSave = { onSave(sense) },
                    )
                }
            }

            if (result.context.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text("In this book", style = MaterialTheme.typography.labelMedium)
                Text(
                    result.context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun SenseView(sense: Sense, isSaved: Boolean, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AssistChip(onClick = {}, label = { Text(sense.partOfSpeech.label) })

        if (sense.english.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(sense.english, style = MaterialTheme.typography.bodyLarge)
        }

        if (sense.persian.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // Persian is rendered right-aligned so the script reads naturally
            // next to the left-aligned English above it.
            Text(
                sense.persian.joinToString("، "),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (sense.examples.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            sense.examples.take(3).forEach {
                Text(
                    "• $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = if (isRtlText(it)) TextAlign.Right else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (sense.synonyms.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Synonyms: ${sense.synonyms.joinToString(", ")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !isSaved) {
                Icon(
                    if (isSaved) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = null,
                )
                Text(if (isSaved) "  In your cards" else "  Add to flashcards")
            }
        }
    }
}
