package dev.agenthub.app.core.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.agenthub.app.R
import dev.agenthub.app.core.model.RiskLevel

@Composable
fun RiskBadge(level: RiskLevel, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (level) {
        RiskLevel.Read -> scheme.surfaceVariant to scheme.onSurfaceVariant
        RiskLevel.Write -> scheme.primaryContainer to scheme.onPrimaryContainer
        RiskLevel.Dangerous -> scheme.error to scheme.onError
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text = stringResource(level.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun RiskLevel.labelRes(): Int = when (this) {
    RiskLevel.Read -> R.string.risk_read
    RiskLevel.Write -> R.string.risk_write
    RiskLevel.Dangerous -> R.string.risk_dangerous
}
