@file:OptIn(ExperimentalLayoutApi::class)

package ly.majde.gtatranslator.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.ui.MainViewModel
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
import ly.majde.gtatranslator.data.DisplayMode
import ly.majde.gtatranslator.engine.LineStatus
import ly.majde.gtatranslator.engine.LiveLine
import ly.majde.gtatranslator.engine.Source

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

fun sourceIcon(source: Source): ImageVector = when (source) {
    Source.CAMERA -> Icons.Filled.CameraAlt
    Source.SNAPSHOT -> Icons.Filled.PhotoCamera
    Source.AUDIO -> Icons.Filled.Hearing
    Source.MANUAL -> Icons.Filled.Keyboard
}

/** The big "now playing" card: the line on screen, large Arabic text readable from the couch. */
@Composable
fun HeroLineCard(
    line: LiveLine,
    textScale: Float,
    showOriginal: Boolean,
    displayMode: DisplayMode,
    actions: LineActionsHandler,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    frozen: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val alpha by animateFloatAsState(targetValue = if (dimmed) 0.6f else 1f, animationSpec = tween(400), label = "dim")
    GlowCard(modifier = modifier.fillMaxWidth(), glow = true, color = colors.surfaceContainerHigh) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .alpha(alpha),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LineHeader(line, frozen = frozen, onExpand = onExpand)
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
            DisplayTranslation(
                line = line,
                mode = displayMode,
                style = headline.copy(
                    fontSize = headline.fontSize * textScale,
                    lineHeight = headline.lineHeight * textScale,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = colors.onSurface,
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand),
            )
            // Details appear once, when the line is final, so nothing below keeps jumping.
            val ready = displayMode == DisplayMode.INSTANT || line.status == LineStatus.DONE
            if (ready && line.explanation.isNotBlank()) {
                Text(
                    "💡 " + line.explanation,
                    style = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = it.fontSize * textScale.coerceAtMost(1.3f)) },
                    color = colors.onSurface.copy(alpha = 0.9f),
                )
            }
            if (ready && line.slang.isNotEmpty()) SlangChips(line, actions)
            LineActions(line, actions)
        }
    }
}

/** Callbacks shared by every line card. */
class LineActionsHandler(
    val savedTerms: Set<String>,
    val onAsk: (LiveLine) -> Unit,
    val onSpeak: (LiveLine) -> Unit,
    val onRetry: (LiveLine) -> Unit,
    val onToggleWord: (term: String, meaning: String, line: LiveLine) -> Unit,
    val onToggleLine: (LiveLine) -> Unit,
) {
    fun isSaved(term: String) = term.trim().lowercase() in savedTerms
}

/** Builds the shared card callbacks, with a short confirmation when saving words. */
@Composable
fun rememberLineActions(vm: MainViewModel): LineActionsHandler {
    val context = LocalContext.current
    val words by vm.words.collectAsStateWithLifecycle()
    val saved = remember(words) { words.map { it.term.trim().lowercase() }.toHashSet() }
    val savedMsg = stringResource(R.string.word_saved)
    val removedMsg = stringResource(R.string.word_removed)
    return remember(saved, vm) {
        LineActionsHandler(
            savedTerms = saved,
            onAsk = { vm.ask(it) },
            onSpeak = { vm.speak(it) },
            onRetry = { vm.retryLine(it.id) },
            onToggleWord = { term, meaning, line ->
                val now = vm.toggleWord(term, meaning, line)
                Toast.makeText(context, if (now) savedMsg else removedMsg, Toast.LENGTH_SHORT).show()
            },
            onToggleLine = { line ->
                val now = vm.toggleLine(line)
                Toast.makeText(context, if (now) savedMsg else removedMsg, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

/** Compact row for older lines; expands on tap. */
@Composable
fun CompactLineRow(
    line: LiveLine,
    showOriginal: Boolean,
    actions: LineActionsHandler,
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
                line.shown.ifBlank { line.bestTranslation }.ifBlank { "···" },
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
                if (line.slang.isNotEmpty()) SlangChips(line, actions)
                LineActions(line, actions)
            }
        }
    }
}

@Composable
private fun LineHeader(line: LiveLine, frozen: Boolean, onExpand: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(sourceIcon(line.source), contentDescription = null, tint = colors.secondary, modifier = Modifier.size(18.dp))
        if (line.speaker.isNotBlank()) {
            Pill(line.speaker, color = colors.tertiary.copy(alpha = 0.18f), contentColor = colors.tertiary)
        }
        if (frozen) Pill(stringResource(R.string.reading_frozen_short), icon = Icons.Filled.PushPin, contentColor = colors.primary)
        Spacer(Modifier.weight(1f))
        StatusBadge(line)
        IconButton(onClick = onExpand, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Fullscreen, contentDescription = stringResource(R.string.reading_open), tint = colors.onSurfaceVariant)
        }
    }
}

/** Static (non-blinking) status, so nothing on the card distracts while reading. */
@Composable
private fun StatusBadge(line: LiveLine) {
    val colors = MaterialTheme.colorScheme
    when (line.status) {
        LineStatus.DRAFT, LineStatus.REFINING ->
            Pill(stringResource(R.string.status_instant), icon = Icons.Filled.Bolt, contentColor = colors.secondary)
        LineStatus.DONE -> Pill(
            line.provider.substringAfter("· ").ifBlank { stringResource(R.string.status_ai) },
            icon = Icons.Filled.AutoAwesome,
            contentColor = colors.primary,
        )
        LineStatus.OFFLINE -> Pill(stringResource(R.string.status_offline), icon = Icons.Filled.CloudOff)
    }
}

@Composable
fun SlangChips(line: LiveLine, actions: LineActionsHandler) {
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        line.slang.forEach { term ->
            val saved = actions.isSaved(term.term)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.secondaryContainer.copy(alpha = if (saved) 0.9f else 0.55f),
                modifier = Modifier.clickable { actions.onToggleWord(term.term, term.meaning, line) },
            ) {
                Row(Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(term.term, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.secondary)
                        Text(term.meaning, style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = stringResource(R.string.action_save_word),
                        tint = colors.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LineActions(line: LiveLine, actions: LineActionsHandler) {
    val clipboard = LocalClipboardManager.current
    val lineSaved = actions.isSaved(line.original)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { actions.onAsk(line) }) {
            Icon(Icons.Filled.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_ask))
        }
        TextButton(onClick = { actions.onSpeak(line) }) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.action_speak), modifier = Modifier.size(18.dp))
        }
        TextButton(onClick = { actions.onToggleLine(line) }) {
            Icon(
                if (lineSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = stringResource(R.string.action_save_line),
                modifier = Modifier.size(18.dp),
            )
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(copyText(line))) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(18.dp))
        }
        if (line.status == LineStatus.OFFLINE) {
            TextButton(onClick = { actions.onRetry(line) }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry), modifier = Modifier.size(18.dp))
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
