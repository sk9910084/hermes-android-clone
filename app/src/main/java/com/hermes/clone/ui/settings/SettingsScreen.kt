package com.hermes.clone.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.clone.BuildConfig
import com.hermes.clone.data.Provider
import com.hermes.clone.ui.graphViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = graphViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val fetchState by vm.fetchState.collectAsStateWithLifecycle()
    val clearConfirm by vm.clearConfirmVisible.collectAsStateWithLifecycle()

    var keyVisible by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }

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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Provider ──────────────────────────────────────────────
            SectionHeader("Provider")

            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = it },
            ) {
                OutlinedTextField(
                    value = settings.provider.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false },
                ) {
                    Provider.values().forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = {
                                providerExpanded = false
                                vm.selectProvider(p)
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { vm.setBaseUrl(it) },
                label = { Text("Base URL") },
                placeholder = { Text(settings.provider.defaultBaseUrl) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            if (settings.provider.helperText.isNotBlank()) {
                Text(
                    settings.provider.helperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Model ──────────────────────────────────────────────────
            SectionHeader("Model")

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.model,
                    onValueChange = { vm.setModel(it) },
                    label = { Text("Model ID") },
                    placeholder = { Text(settings.provider.suggestedModel.ifBlank { "model-id" }) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { vm.fetchModels(); modelPickerOpen = true },
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) { Text("Fetch") }
            }

            // ── API Key ───────────────────────────────────────────────
            SectionHeader("API key")

            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { vm.setApiKey(it) },
                label = { Text("API key") },
                placeholder = { Text("Paste your key here — it stays on this phone") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (keyVisible) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.clearApiKey() },
                    enabled = settings.hasApiKey,
                ) { Text("Clear key") }
                OutlinedButton(
                    onClick = { vm.testConnection(); vm.resetTestState() },
                ) { Text("Test connection") }
            }

            when (val ts = testState) {
                TestState.Idle -> {}
                TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(0.dp))
                    Text("Testing…", style = MaterialTheme.typography.bodySmall)
                }
                is TestState.Success -> Text(
                    "✓ ${ts.info}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                is TestState.Failure -> Text(
                    "✗ ${ts.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ── Agent ─────────────────────────────────────────────────
            HorizontalDivider()
            SectionHeader("Agent")

            Text(
                "Max iterations: ${settings.maxIterations}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.maxIterations.toFloat(),
                onValueChange = { vm.setMaxIterations(it.roundToInt()) },
                valueRange = 1f..100f,
                steps = 98,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Streaming", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Show tokens as they arrive (preferred).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.streaming,
                    onCheckedChange = { vm.setStreaming(it) },
                )
            }

            Text(
                "Context limit: ${settings.contextLimitTokens / 1_000}k tokens",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = (settings.contextLimitTokens / 1_000).toFloat(),
                onValueChange = { vm.setContextLimit(it.roundToInt() * 1_000) },
                valueRange = 4f..200f,
                steps = 195,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Data ──────────────────────────────────────────────────
            HorizontalDivider()
            SectionHeader("Data")

            Button(
                onClick = { vm.showClearConfirm() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear all data") }

            Text(
                "Deletes all chats, memories, to-dos, skill settings, and your API key from this device. Irreversible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── About ─────────────────────────────────────────────────
            HorizontalDivider()
            SectionHeader("About")

            Text(
                "Hermes Clone v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "An unofficial Android clone of the Hermes Agent by Nous Research. " +
                    "Not affiliated with or endorsed by Nous Research. " +
                    "Source: github.com/NousResearch/hermes-agent (MIT licence).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Model picker dialog ───────────────────────────────────────────
    if (modelPickerOpen) {
        AlertDialog(
            onDismissRequest = { modelPickerOpen = false; vm.dismissFetchModels() },
            title = { Text("Pick a model") },
            text = {
                when (val fs = fetchState) {
                    FetchModelsState.Idle, FetchModelsState.Running -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Fetching models…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is FetchModelsState.Error -> Text(fs.message, color = MaterialTheme.colorScheme.error)
                    is FetchModelsState.Done -> {
                        if (fs.models.isEmpty()) {
                            Text("No models returned by this endpoint.")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                fs.models.take(60).forEach { modelId ->
                                    TextButton(
                                        onClick = { vm.pickModel(modelId); modelPickerOpen = false },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(modelId, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { modelPickerOpen = false; vm.dismissFetchModels() }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Clear all data dialog ─────────────────────────────────────────
    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { vm.hideClearConfirm() },
            title = { Text("Clear all data?") },
            text = {
                Text(
                    "All chats, memories, to-dos, skill settings, and your API key will be " +
                        "permanently deleted from this device. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.clearAllData() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { vm.hideClearConfirm() }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}
