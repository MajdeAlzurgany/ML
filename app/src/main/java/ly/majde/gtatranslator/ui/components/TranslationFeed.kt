package ly.majde.gtatranslator.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.engine.Source
import ly.majde.gtatranslator.engine.TranslationEntry

@Composable
fun TranslationFeed(
    entries: List<TranslationEntry>,
    emptyHint: String,
    onExplainMore: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    val newestId = entries.first().id
    LaunchedEffect(newestId) { listState.animateScrollToItem(0) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
            TranslationCard(entry = entry, highlighted = index == 0, onExplainMore = { onExplainMore(entry.id) })
        }
    }
}

private fun sourceIcon(source: Source): ImageVector = when (source) {
    Source.CAMERA -> Icons.Filled.CameraAlt
    Source.SNAPSHOT -> Icons.Filled.PhotoCamera
    Source.AUDIO -> Icons.Filled.Hearing
    Source.MANUAL -> Icons.Filled.Keyboard
}

@Composable
fun TranslationCard(entry: TranslationEntry, highlighted: Boolean, onExplainMore: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) colors.surfaceContainerHigh else colors.surfaceContainer,
        ),
        border = if (highlighted) BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f)) else null,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sourceIcon(entry.source), contentDescription = null, tint = colors.secondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                if (entry.speaker.isNotBlank()) {
                    Text(entry.speaker, style = MaterialTheme.typography.labelLarge, color = colors.tertiary)
                    Spacer(Modifier.width(6.dp))
                }
                if (entry.kind == "objective") {
                    Text(stringResource(R.string.kind_objective), style = MaterialTheme.typography.labelMedium, color = colors.secondary)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    timeFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (entry.original.isNotBlank()) {
                Text(
                    entry.original,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Left,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                entry.translation,
                style = if (highlighted) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            if (entry.explanation.isNotBlank()) {
                Text(
                    "💡 " + entry.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface.copy(alpha = 0.88f),
                )
            }
            if (entry.slang.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    entry.slang.forEach { term ->
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = colors.secondary, fontWeight = FontWeight.Bold)) { append(term.term) }
                                append(" — ")
                                append(term.meaning)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            entry.deepExplanation?.let { deep ->
                Surface(color = colors.primaryContainer.copy(alpha = 0.35f), shape = RoundedCornerShape(10.dp)) {
                    Text(deep, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.deepExplanation == null) {
                    TextButton(onClick = onExplainMore, enabled = !entry.deepLoading) {
                        if (entry.deepLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.explain_more))
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(copyText(entry))) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy))
                }
            }
        }
    }
}

private fun copyText(e: TranslationEntry): String = buildString {
    if (e.original.isNotBlank()) appendLine(e.original)
    appendLine(e.translation)
    if (e.explanation.isNotBlank()) appendLine(e.explanation)
    e.slang.forEach { appendLine("• ${it.term} — ${it.meaning}") }
    e.deepExplanation?.let { appendLine(it) }
}.trim()
