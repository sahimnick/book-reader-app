package com.bookreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bookreader.AppContainer
import com.bookreader.data.AiSettings
import kotlinx.coroutines.launch

/**
 * Where the reader supplies their own AI key.
 *
 * The key cannot be shipped inside the app: anything compiled into an APK can
 * be extracted by whoever downloads it, and it would be the developer's key
 * paying for every user's lookups. So the arrangement is that the key is the
 * reader's, entered here, stored in app-private storage, and used only for the
 * words the offline and free dictionaries could not answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenProfile: () -> Unit,
    onBack: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(AiSettings.DEFAULT_MODEL) }
    var revealKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        apiKey = container.aiSettings.apiKey().orEmpty()
        model = container.aiSettings.model()
        enabled = container.aiSettings.isEnabled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Card(Modifier.fillMaxWidth().clickable(onClick = onOpenProfile)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Reading profile", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your level, the words you look up most, and which books in " +
                            "your library are at that level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text("Smart lookup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "With a key, tapping a word gives the meaning it has in that " +
                    "sentence rather than every possible meaning. Without one the " +
                    "app keeps using the built-in and free dictionaries.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation =
                    if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    // Pasted keys are easy to get wrong and impossible to check
                    // behind dots, so allow revealing before saving.
                    OutlinedButton(onClick = { revealKey = !revealKey }) {
                        Text(if (revealKey) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saved = false },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Row {
                Button(
                    onClick = {
                        scope.launch {
                            container.aiSettings.setApiKey(apiKey)
                            container.aiSettings.setModel(model)
                            enabled = container.aiSettings.isEnabled()
                            saved = true
                        }
                    },
                ) { Text("Save") }

                Spacer(Modifier.fillMaxWidth(0.04f))

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            container.clearAiCache()
                            saved = false
                        }
                    },
                ) { Text("Clear cached lookups") }
            }

            Spacer(Modifier.height(14.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (enabled) "Smart lookup is on" else "Smart lookup is off",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (enabled) {
                            "Answers are cached, so a word you have already looked " +
                                "up in the same sentence costs nothing to see again."
                        } else {
                            "Add a key above to turn it on."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (saved) {
                        Spacer(Modifier.height(6.dp))
                        Text("Saved.", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Your key is stored only on this device, in the app's private " +
                    "storage, and is sent only to the model provider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
