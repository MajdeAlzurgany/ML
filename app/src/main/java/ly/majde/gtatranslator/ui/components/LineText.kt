@file:OptIn(ExperimentalTextApi::class)

package ly.majde.gtatranslator.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ly.majde.gtatranslator.data.DisplayMode
import ly.majde.gtatranslator.engine.LineStatus
import ly.majde.gtatranslator.engine.LiveLine

/** Remembers, per line, whether word-by-word mode is revealing the AI text or the instant one. */
private class SourceLock {
    var useDraft: Boolean? = null
}

/** The text that [DisplayTranslation] will eventually show for [line] (used to size it in advance). */
fun finalTextFor(line: LiveLine, mode: DisplayMode): String = when (mode) {
    DisplayMode.STABLE -> line.stableText
    DisplayMode.WORDS -> line.translation.ifBlank { line.shown }
    DisplayMode.INSTANT -> instantText(line).ifBlank { line.bestTranslation }
}

/** Instant mode: the device translation right away, replaced once by the AI's when it is complete. */
fun instantText(line: LiveLine): String = when {
    line.arComplete && line.translation.isNotBlank() -> line.translation
    line.draft.isNotBlank() -> line.draft
    line.status == LineStatus.OFFLINE || line.status == LineStatus.DONE -> line.bestTranslation
    else -> ""
}

/** True when [line] has something to show in [mode] (otherwise it is still "pending"). */
fun hasDisplayText(line: LiveLine, mode: DisplayMode): Boolean = when (mode) {
    DisplayMode.STABLE -> line.stableText.isNotBlank()
    DisplayMode.WORDS -> line.translation.isNotBlank() || line.shown.isNotBlank()
    DisplayMode.INSTANT -> instantText(line).isNotBlank()
}

/**
 * Shows a line's translation according to the reading mode:
 *  - STABLE: nothing moves; a calm placeholder until the complete sentence is ready, then it stays.
 *  - WORDS: words fill in one after another in their final position (no reflow, no rewriting).
 *  - INSTANT: always the latest text (fastest, but it changes).
 */
@Composable
fun DisplayTranslation(
    line: LiveLine,
    mode: DisplayMode,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    minLines: Int = 1,
    animate: Boolean = true,
) {
    when (mode) {
        DisplayMode.STABLE -> {
            val text = line.stableText
            if (text.isBlank()) {
                Placeholder(style, color, modifier, textAlign, minLines)
            } else {
                Text(text, style = style, color = color, textAlign = textAlign, minLines = minLines, modifier = modifier)
            }
        }
        DisplayMode.WORDS -> {
            val lock = remember(line.id, line.rev) { SourceLock() }
            // Decide once per line: animate lines that are being produced live, not old ones.
            val shouldAnimate = remember(line.id, line.rev) { animate && line.status != LineStatus.DONE }
            if (lock.useDraft == null) {
                lock.useDraft = when {
                    line.translation.isNotBlank() -> false
                    line.shown.isNotBlank() || line.status == LineStatus.OFFLINE -> true
                    else -> null
                }
            }
            val target = when (lock.useDraft) {
                true -> line.shown.ifBlank { line.draft }
                false -> line.translation
                null -> ""
            }
            if (target.isBlank()) {
                Placeholder(style, color, modifier, textAlign, minLines)
            } else {
                TypewriterText(
                    target = target,
                    key = line.id to line.rev,
                    style = style,
                    color = color,
                    textAlign = textAlign,
                    minLines = minLines,
                    animate = shouldAnimate,
                    modifier = modifier,
                )
            }
        }
        DisplayMode.INSTANT -> {
            val text = instantText(line)
            if (text.isBlank()) {
                Placeholder(style, color, modifier, textAlign, minLines)
            } else {
                // A single, soft swap from the device translation to the AI one.
                Crossfade(targetState = text, animationSpec = tween(220), label = "instant", modifier = modifier) { value ->
                    Text(value, style = style, color = color, textAlign = textAlign, minLines = minLines)
                }
            }
        }
    }
}

@Composable
private fun Placeholder(style: TextStyle, color: Color, modifier: Modifier, textAlign: TextAlign?, minLines: Int) {
    Text("···", style = style, color = color.copy(alpha = 0.35f), textAlign = textAlign, minLines = minLines, modifier = modifier)
}

/**
 * Reveals words one after another. The whole text is laid out from the start (the hidden part is
 * transparent), so revealed words never move: it reads like subtitles being written in place.
 */
@Composable
fun TypewriterText(
    target: String,
    key: Any,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    minLines: Int = 1,
    animate: Boolean = true,
    wordDelayMs: Long = 75L,
) {
    val latest by rememberUpdatedState(target)
    val words = remember(target) { target.split(' ') }
    var visible by remember(key) { mutableIntStateOf(if (animate) 0 else Int.MAX_VALUE) }

    LaunchedEffect(key, animate) {
        if (!animate) {
            visible = Int.MAX_VALUE
            return@LaunchedEffect
        }
        while (true) {
            val total = latest.split(' ').size
            if (visible < total) {
                visible++
                delay(wordDelayMs)
            } else {
                delay(50)
            }
        }
    }

    val shownCount = visible.coerceAtMost(words.size)
    val annotated: AnnotatedString = buildAnnotatedString {
        words.forEachIndexed { i, w ->
            if (i > 0) append(' ')
            if (i < shownCount) {
                append(w)
            } else {
                withStyle(SpanStyle(color = Color.Transparent)) { append(w) }
            }
        }
    }
    Text(annotated, style = style, color = color, textAlign = textAlign, minLines = minLines, modifier = modifier)
}

/**
 * Picks the largest font size (up to [maxFontSizeSp]) at which [sampleText] fits in the box,
 * so long lines shrink instead of being cut, and short lines are huge and easy to read.
 */
@Composable
fun rememberFitFontSize(
    sampleText: String,
    style: TextStyle,
    maxWidth: Dp,
    maxHeight: Dp,
    maxFontSizeSp: Float,
    minFontSizeSp: Float = 16f,
    lineHeightFactor: Float = 1.35f,
): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sampleText, style, maxWidth, maxHeight, maxFontSizeSp) {
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val text = AnnotatedString(sampleText.ifBlank { "···" })
        fun fits(size: Float): Boolean {
            val result = measurer.measure(
                text = text,
                style = style.copy(fontSize = size.sp, lineHeight = (size * lineHeightFactor).sp),
                constraints = Constraints(maxWidth = widthPx),
            )
            return result.size.height <= heightPx
        }
        if (fits(maxFontSizeSp)) {
            maxFontSizeSp
        } else {
            var lo = minFontSizeSp
            var hi = maxFontSizeSp
            repeat(8) {
                val mid = (lo + hi) / 2f
                if (fits(mid)) lo = mid else hi = mid
            }
            lo
        }
    }
}
