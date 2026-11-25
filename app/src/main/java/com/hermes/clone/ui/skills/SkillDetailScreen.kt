package com.hermes.clone.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.clone.data.Skill
import com.hermes.clone.ui.chat.MarkdownText
import com.hermes.clone.ui.graphViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(
    name: String,
    onBack: () -> Unit,
) {
    val vm = graphViewModel(key = "skill:$name") { SkillsViewModel(it) }
    val enabled by vm.enabled.collectAsStateWithLifecycle()

    var skill by remember { mutableStateOf<Skill?>(null) }
    var notFound by remember { mutableStateOf(false) }

    LaunchedEffect(name) {
        val found = vm.findSkill(name)
        if (found == null) notFound = true else skill = found
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(skill?.name ?: name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            notFound -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text("Skill not found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            skill == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    // Loading — skill not yet read from asset cache
                }
            }
            else -> {
                val s = skill!!
                val isEnabled = s.name in enabled
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (isEnabled) "Enabled" else "Disabled",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { vm.toggle(s.name, it) },
                            )
                        }
                        if (s.description.isNotBlank()) {
                            Text(
                                s.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.category.isNotBlank()) AssistChip(onClick = {}, label = { Text(s.category) })
                            if (s.version.isNotBlank()) AssistChip(onClick = {}, label = { Text("v${s.version}") })
                            if (s.license.isNotBlank()) AssistChip(onClick = {}, label = { Text(s.license) })
                        }
                        if (s.author.isNotBlank()) {
                            Text(
                                "By ${s.author}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    MarkdownText(
                        text = s.body,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
