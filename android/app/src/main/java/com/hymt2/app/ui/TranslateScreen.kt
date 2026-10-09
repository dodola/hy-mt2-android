package com.hymt2.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hymt2.app.engine.GenerationStats
import com.hymt2.app.translate.Language
import com.hymt2.app.translate.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(vm: TranslateViewModel) {
    val s by vm.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importModel)
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hy-MT2 Translate", style = MaterialTheme.typography.headlineSmall)
            Text(s.deviceLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(
                    label = s.selected?.let { "${it.spec.label} (${it.sizeMb} MB)" } ?: "No model",
                    items = s.models, text = { "${it.spec.label} (${it.sizeMb} MB)" },
                    onPick = vm::selectModel, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import") }
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Picker(
                    label = s.source?.nameEn ?: "Auto-detect",
                    items = listOf<Language?>(null) + Languages.all, text = { it?.nameEn ?: "Auto-detect" },
                    onPick = vm::setSource, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::swapLanguages, enabled = s.source != null) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Swap")
                }
                Picker(
                    label = s.target.nameEn, items = Languages.all, text = { it.nameEn },
                    onPick = vm::setTarget, modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = s.input, onValueChange = vm::setInput, label = { Text("Text") },
                modifier = Modifier.fillMaxWidth(), minLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::translate, enabled = !s.running && !s.loading && s.loadedModelId != null) { Text("Translate") }
                OutlinedButton(onClick = vm::stop, enabled = s.running) { Text("Stop") }
            }

            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        s.output.ifEmpty { "Translation appears here" },
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            s.stats?.let { StatsLine(it) }
            Text(s.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun StatsLine(st: GenerationStats) {
    Text(
        "TTFT ${"%.0f".format(st.ttftMs)} ms · prefill ${"%.1f".format(st.prefillTps)} tok/s (${st.promptTokens}) · " +
            "decode ${"%.1f".format(st.decodeTps)} tok/s (${st.generatedTokens})",
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun <T> Picker(label: String, items: List<T>, text: (T) -> String, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(text(item)) }, onClick = { open = false; onPick(item) })
            }
        }
    }
}
