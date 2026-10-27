package dev.agenthub.app.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agenthub.app.R

/** Shimmering placeholder block. The animated value is read in the draw phase only. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    val shift = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonShift",
    )
    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .drawBehind {
                val x = size.width * shift.value
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x + size.width, size.height),
                    ),
                )
            },
    )
}

/** List-shaped loading placeholder, announced once to screen readers. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 4) {
    val description = stringResource(R.string.loading_content_description)
    Column(
        modifier = modifier.padding(16.dp).semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) { SkeletonBox(Modifier.fillMaxWidth().height(72.dp)) }
    }
}
