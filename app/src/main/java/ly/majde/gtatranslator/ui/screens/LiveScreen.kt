@file:OptIn(ExperimentalMaterial3Api::class)

package ly.majde.gtatranslator.ui.screens

import android.Manifest
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.data.AppSettings
import ly.majde.gtatranslator.data.LiveMode
import ly.majde.gtatranslator.engine.LiveLine
import ly.majde.gtatranslator.translate.OfflineTranslator
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.components.CameraHandle
import ly.majde.gtatranslator.ui.components.CompactLineRow
import ly.majde.gtatranslator.ui.components.GradientIcon
import ly.majde.gtatranslator.ui.components.HeroLineCard
import ly.majde.gtatranslator.ui.components.KeepScreenOn
import ly.majde.gtatranslator.ui.components.ListeningOrb
import ly.majde.gtatranslator.ui.components.NoticeBar
import ly.majde.gtatranslator.ui.components.PermissionGate
import ly.majde.gtatranslator.ui.components.Pill
import ly.majde.gtatranslator.ui.components.RoiOverlay
import ly.majde.gtatranslator.ui.components.SubtitleCameraView
import ly.majde.gtatranslator.ui.components.rememberPermission

@Composable
fun LiveScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val offlineState by vm.offlineState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        LiveHeader(settings.liveMode, busy = status.busy > 0, offlineState = offlineState, onMode = vm::setLiveMode)
        NoticeBar(status.notice, status.detail, onDismiss = vm::dismissNotice)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val landscape = maxWidth > maxHeight
            val sourceHeight = maxHeight * 0.42f
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    SourcePane(vm, settings, Modifier.weight(0.5f).fillMaxHeight(), expandedHeight = null)
                    Feed(vm, settings, lines, Modifier.weight(0.5f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    SourcePane(vm, settings, Modifier.fillMaxWidth(), expandedHeight = sourceHeight)
                    Feed(vm, settings, lines, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun LiveHeader(mode: LiveMode, busy: Boolean, offlineState: OfflineTranslator.State, onMode: (LiveMode) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.live_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.width(10.dp))
            if (busy) Pill(stringResource(R.string.live_translating), contentColor = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.weight(1f))
            when (offlineState) {
                OfflineTranslator.State.DOWNLOADING -> Pill(stringResource(R.string.offline_downloading))
                OfflineTranslator.State.FAILED -> Pill(stringResource(R.string.offline_failed_short), contentColor = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
        Spacer(Modifier.height(10.dp))
        val modes = listOf(LiveMode.CAMERA, LiveMode.MIC)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { onMode(item) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    icon = {
                        Icon(
                            if (item == LiveMode.CAMERA) Icons.Filled.CameraAlt else Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text(stringResource(if (item == LiveMode.CAMERA) R.string.mode_camera else R.string.mode_mic))
                }
            }
        }
    }
}

@Composable
private fun SourcePane(vm: MainViewModel, settings: AppSettings, modifier: Modifier, expandedHeight: Dp?) {
    when (settings.liveMode) {
        LiveMode.CAMERA -> CameraSource(vm, settings, modifier, expandedHeight)
        LiveMode.MIC -> MicSource(vm, settings, modifier)
    }
}

@Composable
private fun CameraSource(vm: MainViewModel, settings: AppSettings, modifier: Modifier, expandedHeight: Dp?) {
    val permission = rememberPermission(Manifest.permission.CAMERA)
    if (!permission.granted) {
        Box(modifier.then(if (expandedHeight != null) Modifier.height(expandedHeight) else Modifier)) {
            PermissionGate(
                icon = Icons.Filled.CameraAlt,
                title = stringResource(R.string.perm_camera_title),
                body = stringResource(R.string.perm_camera_body),
                onRequest = permission.request,
            )
        }
        return
    }
    KeepScreenOn()

    val liveText by vm.liveText.collectAsStateWithLifecycle()
    val paused by vm.cameraPaused.collectAsStateWithLifecycle()
    var roi by remember { mutableStateOf(settings.roi) }
    var zoom by rememberSaveable { mutableFloatStateOf(0f) }
    var compact by rememberSaveable { mutableStateOf(false) }
    val handle = remember { CameraHandle() }

    val height by animateDpAsState(
        targetValue = if (expandedHeight == null) 0.dp else if (compact) 150.dp else expandedHeight,
        label = "cameraHeight",
    )
    val sized = if (expandedHeight != null) modifier.height(height) else modifier

    Box(
        sized
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SubtitleCameraView(
                handle = handle,
                roi = roi,
                zoom = zoom,
                onText = vm::onOcrText,
                modifier = Modifier.fillMaxSize(),
            )
            RoiOverlay(
                roi = roi,
                onChange = { roi = it },
                onChangeFinished = { vm.updateSettings { s -> s.copy(roi = roi) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Live reading strip
        Surface(
            color = Color.Black.copy(alpha = 0.62f),
            contentColor = Color.White,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        paused -> stringResource(R.string.camera_paused)
                        liveText.isBlank() -> stringResource(R.string.camera_searching)
                        else -> liveText
                    },
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Controls
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(10.dp)
                .fillMaxWidth(),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                    IconButton(onClick = vm::toggleCameraPaused) {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = stringResource(if (paused) R.string.camera_resume else R.string.camera_pause),
                        )
                    }
                    Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.zoom), modifier = Modifier.size(18.dp))
                    Slider(value = zoom, onValueChange = { zoom = it }, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                    if (expandedHeight != null) {
                        IconButton(onClick = { compact = !compact }) {
                            Icon(
                                if (compact) Icons.Filled.OpenInFull else Icons.Filled.CloseFullscreen,
                                contentDescription = stringResource(R.string.camera_resize),
                            )
                        }
                    }
                    FilledIconButton(
                        onClick = { handle.snapshot()?.let(vm::onSnapshot) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.snap))
                    }
                }
            }
        }
    }
}

@Composable
private fun MicSource(vm: MainViewModel, settings: AppSettings, modifier: Modifier) {
    val permission = rememberPermission(Manifest.permission.RECORD_AUDIO)
    if (!permission.granted) {
        Box(modifier.height(320.dp)) {
            PermissionGate(
                icon = Icons.Filled.Mic,
                title = stringResource(R.string.perm_mic_title),
                body = stringResource(R.string.perm_mic_body),
                onRequest = permission.request,
            )
        }
        return
    }
    val listening by vm.listening.collectAsStateWithLifecycle()
    val level by vm.micLevel.collectAsStateWithLifecycle()
    val micError by vm.micError.collectAsStateWithLifecycle()
    var sensitivity by remember { mutableFloatStateOf(settings.micSensitivity) }
    if (listening) KeepScreenOn()

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(170.dp)) {
            ListeningOrb(level = level, active = listening, modifier = Modifier.fillMaxSize())
            LargeFloatingActionButton(
                onClick = { if (listening) vm.stopListening() else vm.startListening() },
                containerColor = Color.Transparent,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            ) {
                Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
        Text(
            stringResource(if (listening) R.string.listening_on else R.string.listening_off),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mic_sensitivity), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                onValueChangeFinished = { vm.updateSettings { s -> s.copy(micSensitivity = sensitivity) } },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
        }
        if (micError) {
            Text(stringResource(R.string.notice_mic_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Feed(vm: MainViewModel, settings: AppSettings, lines: List<LiveLine>, modifier: Modifier) {
    if (lines.isEmpty()) {
        Column(
            modifier = modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            GradientIcon(if (settings.liveMode == LiveMode.CAMERA) Icons.Filled.Subtitles else Icons.Filled.Mic, size = 64.dp)
            Text(
                stringResource(if (settings.liveMode == LiveMode.CAMERA) R.string.empty_camera else R.string.empty_mic),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    val newest = lines.first()
    LaunchedEffect(newest.id) { listState.animateScrollToItem(0) }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "hero-${newest.id}") {
            HeroLineCard(
                line = newest,
                textScale = settings.textScale,
                showOriginal = settings.showOriginal,
                onAsk = { vm.ask(newest) },
                onSpeak = { vm.speak(newest) },
                onRetry = { vm.retryLine(newest.id) },
            )
        }
        items(lines.drop(1).take(RECENT_IN_FEED), key = { it.id }) { line ->
            CompactLineRow(
                line = line,
                showOriginal = settings.showOriginal,
                onAsk = { vm.ask(line) },
                onSpeak = { vm.speak(line) },
                onRetry = { vm.retryLine(line.id) },
            )
        }
    }
}

private const val RECENT_IN_FEED = 30
