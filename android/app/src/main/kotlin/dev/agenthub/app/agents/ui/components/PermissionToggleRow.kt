package dev.agenthub.app.agents.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.agenthub.app.R
import dev.agenthub.app.agents.catalog.ToggleDef
import dev.agenthub.app.core.model.ApprovalMode
import dev.agenthub.app.core.model.RiskLevel
import dev.agenthub.app.core.ui.components.RiskBadge

/**
 * One permission toggle. Write toggles offer "Ask each time" or "Allow"; Dangerous toggles show
 * "Always asks" with no Allow option; Read toggles show no approval control.
 */
@Composable
fun PermissionToggleRow(
    toggle: ToggleDef,
    enabled: Boolean,
    mode: ApprovalMode,
    onToggle: (Boolean) -> Unit,
    onModeChange: (ApprovalMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(toggle.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    RiskBadge(toggle.risk)
                }
                Text(
                    toggle.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The Row above owns the click; the Switch is display only so the target is one 48dp+ area.
            Switch(checked = enabled, onCheckedChange = null)
        }
        if (enabled) {
            when (toggle.risk) {
                RiskLevel.Read -> Unit
                RiskLevel.Dangerous -> Text(
                    stringResource(R.string.mode_always_ask),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                RiskLevel.Write -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == ApprovalMode.Ask,
                        onClick = { onModeChange(ApprovalMode.Ask) },
                        label = { Text(stringResource(R.string.mode_ask)) },
                    )
                    FilterChip(
                        selected = mode == ApprovalMode.Allow,
                        onClick = { onModeChange(ApprovalMode.Allow) },
                        label = { Text(stringResource(R.string.mode_allow)) },
                    )
                }
            }
        }
    }
}
