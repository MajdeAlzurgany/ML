package ly.majde.gtatranslator.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.data.LiveMode
import ly.majde.gtatranslator.engine.LineStatus
import ly.majde.gtatranslator.engine.Source
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.components.DisplayTranslation
import ly.majde.gtatranslator.ui.components.KeepScreenOn
import ly.majde.gtatranslator.ui.components.finalTextFor
import ly.majde.gtatranslator.ui.components.rememberFitFontSize
import ly.majde.gtatranslator.ui.theme.OceanTeal
import ly.majde.gtatranslator.ui.theme.SunsetOrange

private val READING_COLORS = listOf(Color(0xFFF7F4FB), Color(0xFFFFE066), OceanTeal)

/**
 * Full-screen reading mode: black background, huge auto-fitting Arabic text, the previous line
 * above for context. The camera / mic keep running underneath.
 * Tap: show/hide controls · Double tap: explanation · Long press: hold this line · Pinch: text size.
 */
@Composable
fun ReadingOverlay(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hero by vm.hero.collectAsStateWithLifecycle()
    val onScreen by vm.subtitleOnScreen.collectAsStateWithLifecycle()

    BackHandler { vm.exitReading() }
    KeepScreenOn()
    ImmersiveMode()

    var scale by remember { mutableFloatStateOf(settings.readingScale) }
    LaunchedEffect(scale) {
        delay(700)
        vm.updateSettings { it.copy(readingScale = scale) }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(interaction, controlsVisible) {
        if (controlsVisible) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val textColor = READING_COLORS[settings.readingColor.coerceIn(0, READING_COLORS.lastIndex)]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) scale = (scale * zoom).coerceIn(0.5f, 3f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        interaction++
                    },
                    onDoubleTap = { showDetails = !showDetails },
                    onLongPress = { vm.toggleFreeze() },
                )
            },
    ) {
        val line = hero.line
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 36.dp)
        ) {
            if (line == null) {
                Text(
                    stringResource(R.string.reading_waiting),
                    style = MaterialTheme.typography.titleLarge,
                    color = textColor.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val dim by animateFloatAsState(
                    targetValue = if (settings.liveMode != LiveMode.MIC && line.source == Source.CAMERA && !onScreen && !hero.frozen) 0.55f else 1f,
                    animationSpec = tween(500),
                    label = "dim",
                )
                val headline = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                // Size the text for its final length (or the instant translation), so it never shrinks mid-sentence.
                val sample = listOf(finalTextFor(line, settings.displayMode), line.translation, line.draft).maxByOrNull { it.length }.orEmpty()
                val fontSize = rememberFitFontSize(
                    sampleText = sample,
                    style = headline,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight * (if (showDetails) 0.42f else 0.6f),
                    maxFontSizeSp = 40f * scale,
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val previous = hero.previous
                    if (settings.readingShowPrevious && previous != null) {
                        Text(
                            previous.shown.ifBlank { previous.bestTranslation },
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = (16f * scale.coerceIn(0.8f, 1.6f)).sp),
                            color = textColor.copy(alpha = 0.32f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(28.dp))
                    }
                    if (line.speaker.isNotBlank()) {
                        Text(line.speaker, style = MaterialTheme.typography.titleMedium, color = SunsetOrange, modifier = Modifier.alpha(dim))
                        Spacer(Modifier.height(8.dp))
                    }
                    DisplayTranslation(
                        line = line,
                        mode = settings.displayMode,
                        style = headline.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.35f).sp),
                        color = textColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(dim),
                    )
                    if (settings.showOriginal && line.original.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            line.original,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                textDirection = TextDirection.Content,
                                fontSize = (16f * scale.coerceIn(0.8f, 1.5f)).sp,
                            ),
                            color = Color.White.copy(alpha = 0.45f * dim),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (showDetails && line.status == LineStatus.DONE) {
                        Spacer(Modifier.height(20.dp))
                        if (line.explanation.isNotBlank()) {
                            Text(
                                "💡 " + line.explanation,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = (17f * scale.coerceIn(0.8f, 1.4f)).sp),
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                            )
                        }
                        line.slang.take(4).forEach { term ->
                            Text(
                                "${term.term} — ${term.meaning}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OceanTeal,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        if (hero.frozen) {
            Surface(
                color = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.reading_frozen),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.10f),
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = vm::exitReading) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.reading_close)) }
                    IconButton(onClick = {
                        vm.toggleFreeze()
                        interaction++
                    }) {
                        Icon(
                            if (hero.frozen) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.reading_hold),
                        )
                    }
                    IconButton(onClick = {
                        showDetails = !showDetails
                        interaction++
                    }) { Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.reading_details)) }
                    IconButton(onClick = {
                        scale = (scale - 0.15f).coerceAtLeast(0.5f)
                        interaction++
                    }) { Icon(Icons.Filled.ZoomOut, contentDescription = stringResource(R.string.reading_smaller)) }
                    IconButton(onClick = {
                        scale = (scale + 0.15f).coerceAtMost(3f)
                        interaction++
                    }) { Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.reading_bigger)) }
                    IconButton(onClick = {
                        vm.updateSettings { it.copy(readingColor = (it.readingColor + 1) % READING_COLORS.size) }
                        interaction++
                    }) { Icon(Icons.Filled.FormatColorText, contentDescription = stringResource(R.string.reading_color), tint = textColor) }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Text(
                stringResource(R.string.reading_gestures),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = if (hero.frozen) 64.dp else 18.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

private const val CONTROLS_HIDE_MS = 3_500L

/** Hides the status and navigation bars while reading (swipe from the edge to peek). */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
