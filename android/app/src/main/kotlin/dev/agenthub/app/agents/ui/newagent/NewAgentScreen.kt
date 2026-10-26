package dev.agenthub.app.agents.ui.newagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.agenthub.app.R
import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.core.ui.components.AgentCard

/** Exactly the three v1 templates. There is deliberately no Custom or Other option (CLAUDE.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAgentScreen(onBack: () -> Unit, onPick: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_agent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.new_agent_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TemplateCatalog.all.forEach { template ->
                AgentCard(
                    title = template.displayName,
                    subtitle = template.tagline,
                    onClick = { onPick(template.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
