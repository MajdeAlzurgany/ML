package ly.majde.gtatranslator.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.data.DisplayMode
import ly.majde.gtatranslator.engine.LineStatus
import ly.majde.gtatranslator.engine.LiveLine
import ly.majde.gtatranslator.engine.Source

/** Font sizes (sp) and colors for the three tiers of the caption stack. */
data class CaptionStyle(
    val newestSp: Float,
    val recentSp: Float,
    val olderSp: Float,
    val color: Color,
    val originalColor: Color,
    val centered: Boolean,
)

private val SPEAKER_COLORS = listOf(
    Color(0xFFFF9F43), Color(0xFF2EE6D6), Color(0xFFFF6FB5), Color(0xFF8C9EFF), Color(0xFFB9F6CA), Color(0xFFFFD54F),
)

/** Every character keeps the same color, so fast back-and-forth dialogue is easy to follow. */
fun speakerColor(name: String): Color = SPEAKER_COLORS[abs(name.trim().lowercase().hashCode()) % SPEAKER_COLORS.size]

/**
 * Rolling live captions: the newest line appears at the bottom and older lines slide up,
 * so in fast dialogue every line stays on screen long enough to read.
 *  - lines that arrived in the same burst stay big together;
 *  - a line waiting for its translation shows its English immediately;
 *  - scroll up to read back: auto-follow pauses and a "new lines" button brings you back live;
 *  - tap a line for its explanation, slang and actions.
 */
@Composable
fun CaptionStack(
    lines: List<LiveLine>,
    mode: DisplayMode,
    showOriginal: Boolean,
    style: CaptionStyle,
    actions: LineActionsHandler,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    emptyContent: @Composable () -> Unit = {},
) {
    val newestReadyId = lines.firstOrNull { hasDisplayText(it, mode) }?.id ?: Long.MIN_VALUE
    // Ready lines, plus lines newer than the newest ready one (still being translated, shown at the bottom).
    val visible = remember(lines, mode) {
        lines.asSequence()
            .take(MAX_LINES)
            .filter { hasDisplayText(it, mode) || it.id > newestReadyId }
            .toList()
    }
    if (visible.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { emptyContent() }
        return
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atLive by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 48 } }
    var unseen by remember { mutableIntStateOf(0) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val newestId = visible.first().id

    LaunchedEffect(newestId) {
        if (!paused && (atLive || listState.firstVisibleItemIndex <= 1)) {
            listState.animateScrollToItem(0)
            unseen = 0
        } else {
            unseen++
        }
    }
    LaunchedEffect(atLive) { if (atLive) unseen = 0 }

    // Lines that arrived within a few seconds of the newest one form a "burst" and stay big.
    val newestTime = visible.firstOrNull { hasDisplayText(it, mode) }?.timestamp ?: visible.first().timestamp

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(visible, key = { _, l -> l.id }) { index, line ->
                val tier = when {
                    index == 0 -> 0
                    index < BURST_MAX_LINES && newestTime - line.timestamp <= BURST_WINDOW_MS -> 1
                    index < BURST_MAX_LINES -> 2
                    else -> 3
                }
                CaptionItem(
                    line = line,
                    tier = tier,
                    pending = !hasDisplayText(line, mode),
                    mode = mode,
                    style = style,
                    showOriginal = showOriginal,
                    expanded = expandedId == line.id,
                    onToggle = { expandedId = if (expandedId == line.id) null else line.id },
                    actions = actions,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        AnimatedVisibility(
            visible = !atLive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
                modifier = Modifier.clickable {
                    scope.launch {
                        listState.animateScrollToItem(0)
                        unseen = 0
                    }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Icon(Icons.Filled.KeyboardDoubleArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (unseen > 0) stringResource(R.string.captions_new_lines, unseen) else stringResource(R.string.captions_back_live),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptionItem(
    line: LiveLine,
    tier: Int,
    pending: Boolean,
    mode: DisplayMode,
    style: CaptionStyle,
    showOriginal: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: LineActionsHandler,
    modifier: Modifier = Modifier,
) {
    val targetSize = when (tier) {
        0 -> style.newestSp
        1 -> style.newestSp * 0.92f
        2 -> style.recentSp
        else -> style.olderSp
    }
    val targetAlpha = when (tier) {
        0 -> 1f
        1 -> 0.92f
        2 -> 0.7f
        else -> 0.5f
    }
    val size by animateFloatAsState(targetValue = targetSize, animationSpec = tween(350), label = "size")
    val alpha by animateFloatAsState(targetValue = if (expanded) 1f else targetAlpha, animationSpec = tween(350), label = "alpha")
    val align = if (style.centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (style.centered) TextAlign.Center else TextAlign.Start
    val base = MaterialTheme.typography.headlineSmall

    Column(
        horizontalAlignment = align,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .alpha(alpha),
    ) {
        if (line.speaker.isNotBlank() || line.source == Source.AUDIO) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (line.speaker.isNotBlank()) {
                    Text(
                        line.speaker,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = (size * 0.45f).coerceIn(12f, 20f).sp),
                        fontWeight = FontWeight.Bold,
                        color = speakerColor(line.speaker),
                    )
                }
                if (line.source == Source.AUDIO) {
                    Icon(Icons.Filled.Hearing, contentDescription = null, tint = style.originalColor, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (pending) {
            // While the translation is being written, show the English right away.
            if (line.original.isNotBlank()) {
                Text(
                    line.original,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDirection = TextDirection.Content,
                        fontSize = (size * 0.55f).coerceAtLeast(14f).sp,
                    ),
                    color = style.originalColor,
                    textAlign = textAlign,
                )
            }
            Text("···", style = base.copy(fontSize = (size * 0.7f).sp), color = style.color.copy(alpha = 0.4f), textAlign = textAlign)
        } else {
            DisplayTranslation(
                line = line,
                mode = mode,
                style = base.copy(
                    fontSize = size.sp,
                    lineHeight = (size * 1.35f).sp,
                    fontWeight = if (tier <= 1) FontWeight.ExtraBold else FontWeight.Bold,
                ),
                color = style.color,
                textAlign = textAlign,
                animate = tier == 0,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showOriginal && line.original.isNotBlank() && tier <= 2) {
                Text(
                    line.original,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        textDirection = TextDirection.Content,
                        fontSize = (size * 0.45f).coerceIn(12f, 20f).sp,
                    ),
                    color = style.originalColor,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (expanded && !pending) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (line.explanation.isNotBlank()) {
                    Text(
                        "💡 " + line.explanation,
                        style = MaterialTheme.typography.bodyLarge,
                        color = style.color.copy(alpha = 0.9f),
                        textAlign = textAlign,
                    )
                } else if (line.status != LineStatus.DONE && line.status != LineStatus.OFFLINE) {
                    Text(stringResource(R.string.captions_explaining), style = MaterialTheme.typography.bodySmall, color = style.originalColor)
                }
                if (line.slang.isNotEmpty()) SlangChips(line, actions)
                LineActions(line, actions)
            }
        }
    }
}

private const val MAX_LINES = 60
private const val BURST_MAX_LINES = 3
private const val BURST_WINDOW_MS = 4_000L
