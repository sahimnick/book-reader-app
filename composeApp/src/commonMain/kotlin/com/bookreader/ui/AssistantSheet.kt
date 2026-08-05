package com.bookreader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bookreader.reader.AssistantPanel
import com.bookreader.reader.AssistantTask

/**
 * Where the reading assistant answers: recaps, questions, and translation.
 *
 * One sheet for all three because they differ only in what produced the text.
 * The passage each answer is about is shown alongside it — a summary you cannot
 * check against the book is a summary you have to take on trust, and these
 * answers are generated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(
    panel: AssistantPanel,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (panel.task) {
                        AssistantTask.RECAP -> "What happened so far"
                        AssistantTask.QUESTION -> "Ask about this passage"
                        AssistantTask.TRANSLATION -> "This paragraph in Persian"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (panel.isLoading) CircularProgressIndicator(Modifier.height(20.dp))
            }

            if (panel.task == AssistantTask.QUESTION) {
                Spacer(Modifier.height(12.dp))
                QuestionBox(
                    initial = panel.question,
                    enabled = !panel.isLoading,
                    onAsk = onAsk,
                )
            }

            // The source text: for a translation it is what was translated, for
            // a question it is what the answer was drawn from.
            if (panel.task == AssistantTask.TRANSLATION && panel.source.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    panel.source,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
            }

            panel.error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            panel.english?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }

            panel.persian?.let {
                Spacer(Modifier.height(if (panel.english == null) 16.dp else 12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (panel.isLoading && panel.english == null && panel.persian == null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Reading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (!panel.isLoading && (panel.english != null || panel.persian != null)) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Generated from the text on screen. Check it against the book.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun QuestionBox(initial: String, enabled: Boolean, onAsk: (String) -> Unit) {
    var question by remember(initial) { mutableStateOf(initial) }

    OutlinedTextField(
        value = question,
        onValueChange = { question = it },
        label = { Text("Your question") },
        placeholder = { Text("Who is speaking here?") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    )
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { onAsk(question) },
        enabled = enabled && question.isNotBlank(),
    ) { Text("Ask") }
}
