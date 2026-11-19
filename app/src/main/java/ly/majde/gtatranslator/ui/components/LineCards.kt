@file:OptIn(ExperimentalLayoutApi::class)

package ly.majde.gtatranslator.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.engine.LineStatus
import ly.majde.gtatranslator.engine.LiveLine
import ly.majde.gtatranslator.engine.SlangTerm
import ly.majde.gtatranslator.engine.Source

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

fun sourceIcon(source: Source): ImageVector = when (source) {
    Source.CAMERA -> Icons.Filled.CameraAlt
    Source.SNAPSHOT -> Icons.Filled.PhotoCamera
    Source.AUDIO -> Icons.Filled.Hearing
    Source.MANUAL -> Icons.Filled.Keyboard
}

/** The big "now playing" card: newest line, large Arabic text readable from the couch. */
@Composable
fun HeroLineCard(
    line: LiveLine,
    textScale: Float,
    showOriginal: Boolean,
    onAsk: () -> Unit,
    onSpeak: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    GlowCard(modifier = modifier.fillMaxWidth(), glow = true, color = colors.surfaceContainerHigh) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LineHeader(line)
            if (showOriginal && line.original.isNotBlank()) {
                Text(
                    line.original,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Left,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val headline = MaterialTheme.typography.headlineSmall
            AnimatedContent(
                targetState = line.bestTranslation,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "translation",
            ) { text ->
                Text(
                    text.ifBlank { "…" },
                    style = headline.copy(fontSize = headline.fontSize * textScale, lineHeight = headline.lineHeight * textScale),
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.onSurface,
                )
            }
            AnimatedVisibility(visible = line.explanation.isNotBlank()) {
                Text(
                    "💡 " + line.explanation,
                    style = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = it.fontSize * textScale.coerceAtMost(1.3f)) },
                    color = colors.onSurface.copy(alpha = 0.9f),
                )
            }
            if (line.slang.isNotEmpty()) SlangChips(line.slang)
            LineActions(line, onAsk = onAsk, onSpeak = onSpeak, onRetry = onRetry)
        }
    }
}

/** Compact row for older lines; expands on tap. */
@Composable
fun CompactLineRow(
    line: LiveLine,
    showOriginal: Boolean,
    onAsk: () -> Unit,
    onSpeak: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(line.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    GlowCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sourceIcon(line.source), contentDescription = null, tint = colors.secondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                if (line.speaker.isNotBlank()) {
                    Text(line.speaker, style = MaterialTheme.typography.labelMedium, color = colors.tertiary, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(timeFormat.format(Date(line.timestamp)), style = MaterialTheme.typography.labelSmall, color = colors.outline)
            }
            Text(
                line.bestTranslation.ifBlank { "…" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showOriginal && line.original.isNotBlank()) {
                Text(
                    line.original,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = colors.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Left,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (expanded) {
                if (line.explanation.isNotBlank()) {
                    Text("💡 " + line.explanation, style = MaterialTheme.typography.bodyMedium)
                }
                if (line.slang.isNotEmpty()) SlangChips(line.slang)
                LineActions(line, onAsk = onAsk, onSpeak = onSpeak, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun LineHeader(line: LiveLine) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(sourceIcon(line.source), contentDescription = null, tint = colors.secondary, modifier = Modifier.size(18.dp))
        if (line.speaker.isNotBlank()) {
            Pill(line.speaker, color = colors.tertiary.copy(alpha = 0.18f), contentColor = colors.tertiary)
        }
        Spacer(Modifier.weight(1f))
        StatusBadge(line)
    }
}

@Composable
private fun StatusBadge(line: LiveLine) {
    val colors = MaterialTheme.colorScheme
    when (line.status) {
        LineStatus.DRAFT, LineStatus.REFINING -> {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val a by pulse.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                label = "alpha",
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .size(7.dp)
                        .alpha(a)
                        .background(colors.secondary, CircleShape)
                )
                Pill(stringResource(R.string.status_instant), icon = Icons.Filled.Bolt, contentColor = colors.secondary)
            }
        }
        LineStatus.DONE -> Pill(
            line.provider.ifBlank { stringResource(R.string.status_ai) },
            icon = Icons.Filled.AutoAwesome,
            contentColor = colors.primary,
        )
        LineStatus.OFFLINE -> Pill(stringResource(R.string.status_offline), icon = Icons.Filled.CloudOff)
    }
}

@Composable
fun SlangChips(slang: List<SlangTerm>) {
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        slang.forEach { term ->
            Surface(shape = RoundedCornerShape(12.dp), color = colors.secondaryContainer.copy(alpha = 0.55f)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(term.term, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.secondary)
                    Text(term.meaning, style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun LineActions(line: LiveLine, onAsk: () -> Unit, onSpeak: () -> Unit, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onAsk) {
            Icon(Icons.Filled.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_ask))
        }
        TextButton(onClick = onSpeak) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.action_speak), modifier = Modifier.size(18.dp))
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(copyText(line))) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(18.dp))
        }
        if (line.status == LineStatus.OFFLINE) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

private fun copyText(l: LiveLine): String = buildString {
    if (l.original.isNotBlank()) appendLine(l.original)
    appendLine(l.bestTranslation)
    if (l.explanation.isNotBlank()) appendLine(l.explanation)
    l.slang.forEach { appendLine("• ${it.term} — ${it.meaning}") }
}.trim()
