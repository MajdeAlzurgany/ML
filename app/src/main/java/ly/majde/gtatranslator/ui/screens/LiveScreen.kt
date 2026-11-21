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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.delay
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.data.AppSettings
import ly.majde.gtatranslator.data.LiveMode
import ly.majde.gtatranslator.engine.LiveLine
import ly.majde.gtatranslator.engine.Source
import ly.majde.gtatranslator.translate.OfflineTranslator
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.ScanState
import ly.majde.gtatranslator.ui.components.CameraHandle
import ly.majde.gtatranslator.ui.components.CaptionStack
import ly.majde.gtatranslator.ui.components.CaptionStyle
import ly.majde.gtatranslator.ui.components.CompactLineRow
import ly.majde.gtatranslator.ui.components.GlowCard
import ly.majde.gtatranslator.ui.components.GradientIcon
import ly.majde.gtatranslator.ui.components.HeroLineCard
import ly.majde.gtatranslator.ui.components.KeepScreenOn
import ly.majde.gtatranslator.ui.components.ListeningOrb
import ly.majde.gtatranslator.ui.components.MarkdownText
import ly.majde.gtatranslator.ui.components.NoticeBar
import ly.majde.gtatranslator.ui.components.PermissionGate
import ly.majde.gtatranslator.ui.components.Pill
import ly.majde.gtatranslator.ui.components.RoiOverlay
import ly.majde.gtatranslator.ui.components.SubtitleCameraView
import ly.majde.gtatranslator.ui.components.rememberLineActions
import ly.majde.gtatranslator.ui.components.rememberPermission
import ly.majde.gtatranslator.update.UpdateState

@Composable
fun LiveScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val offlineState by vm.offlineState.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        LiveHeader(
            settings.liveMode,
            busy = status.busy > 0,
            offlineState = offlineState,
            activeModel = activeModel,
            onMode = vm::setLiveMode,
            onReading = vm::enterReading,
        )
        UpdateBanner(update, vm)
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
private fun LiveHeader(
    mode: LiveMode,
    busy: Boolean,
    offlineState: OfflineTranslator.State,
    activeModel: String?,
    onMode: (LiveMode) -> Unit,
    onReading: () -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            if (busy) Pill(stringResource(R.string.live_translating), contentColor = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.weight(1f))
            when (offlineState) {
                OfflineTranslator.State.DOWNLOADING -> Pill(stringResource(R.string.offline_downloading_short))
                OfflineTranslator.State.FAILED -> Pill(stringResource(R.string.offline_failed_short), contentColor = MaterialTheme.colorScheme.error)
                else -> if (activeModel != null) Pill(activeModel.substringAfterLast('/').substringAfter(':'))
            }
            FilledTonalIconButton(onClick = onReading) {
                Icon(Icons.Filled.Fullscreen, contentDescription = stringResource(R.string.reading_open))
            }
        }
        Spacer(Modifier.height(10.dp))
        val modes = listOf(LiveMode.CAMERA, LiveMode.BOTH, LiveMode.MIC)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { onMode(item) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    icon = {
                        Icon(
                            when (item) {
                                LiveMode.CAMERA -> Icons.Filled.CameraAlt
                                LiveMode.MIC -> Icons.Filled.Mic
                                LiveMode.BOTH -> Icons.Filled.Merge
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text(
                        stringResource(
                            when (item) {
                                LiveMode.CAMERA -> R.string.mode_camera
                                LiveMode.MIC -> R.string.mode_mic
                                LiveMode.BOTH -> R.string.mode_both
                            }
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(state: UpdateState, vm: MainViewModel) {
    val postponed by vm.updatePopupDismissed.collectAsStateWithLifecycle()
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    } ?: return
    // The pop-up handles updates; this small card only stays after "Later".
    if (postponed != release.versionCode) return
    val colors = MaterialTheme.colorScheme
    GlowCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        glow = true,
        color = colors.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.update_available, release.versionName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::dismissUpdate) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dismiss)) }
            }
            when (state) {
                is UpdateState.Downloading -> LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                is UpdateState.ReadyToInstall -> Button(onClick = vm::retryInstall) { Text(stringResource(R.string.update_install)) }
                is UpdateState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.installUpdate(release) }) { Text(stringResource(R.string.update_retry)) }
                    TextButton(onClick = { vm.openUpdateInBrowser(release) }) { Text(stringResource(R.string.update_browser)) }
                }
                else -> Button(onClick = { vm.installUpdate(release) }) { Text(stringResource(R.string.update_now)) }
            }
        }
    }
}

@Composable
private fun SourcePane(vm: MainViewModel, settings: AppSettings, modifier: Modifier, expandedHeight: Dp?) {
    when (settings.liveMode) {
        LiveMode.CAMERA, LiveMode.BOTH -> CameraSource(vm, settings, modifier, expandedHeight)
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
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    var roi by remember(settings.roi) { mutableStateOf(settings.roi) }
    var zoom by rememberSaveable { mutableFloatStateOf(0f) }
    var compact by rememberSaveable { mutableStateOf(settings.roiConfigured) }
    val handle = remember { CameraHandle() }
    val hybrid = settings.liveMode == LiveMode.BOTH

    // First use: keep scanning for subtitles until the region is placed (max ~40 s).
    LaunchedEffect(settings.roiConfigured) {
        if (!settings.roiConfigured) {
            repeat(20) {
                delay(2_000)
                if (vm.settings.value.roiConfigured) return@LaunchedEffect
                vm.onScanStarted()
                handle.requestScan()
            }
        }
    }
    LaunchedEffect(scanState) {
        if (scanState == ScanState.FOUND || scanState == ScanState.NOT_FOUND) {
            delay(2_500)
            vm.clearScanState()
        }
    }

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
                onScan = { lines, size -> vm.onScanResult(lines, size.width, size.height) },
                modifier = Modifier.fillMaxSize(),
            )
            RoiOverlay(
                roi = roi,
                onChange = { roi = it },
                onChangeFinished = { vm.updateSettings { s -> s.copy(roi = roi, roiConfigured = true) } },
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
                if (scanState == ScanState.SCANNING) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
                } else {
                    Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        scanState == ScanState.SCANNING -> stringResource(R.string.scan_running)
                        scanState == ScanState.FOUND -> stringResource(R.string.scan_found)
                        scanState == ScanState.NOT_FOUND -> stringResource(R.string.scan_not_found)
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
        if (hybrid) {
            HybridMicChip(vm, Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 10.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = vm::toggleCameraPaused) {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = stringResource(if (paused) R.string.camera_resume else R.string.camera_pause),
                        )
                    }
                    IconButton(onClick = {
                        vm.onScanStarted()
                        handle.requestScan()
                    }) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = stringResource(R.string.scan_button))
                    }
                    Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.zoom), modifier = Modifier.size(18.dp))
                    Slider(value = zoom, onValueChange = { zoom = it }, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
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

/** In camera + mic mode the microphone runs in the background; this chip shows and toggles it. */
@Composable
private fun HybridMicChip(vm: MainViewModel, modifier: Modifier) {
    val permission = rememberPermission(Manifest.permission.RECORD_AUDIO)
    val listening by vm.listening.collectAsStateWithLifecycle()
    val level by vm.micLevel.collectAsStateWithLifecycle()
    var muted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(permission.granted, muted) {
        if (permission.granted && !muted) vm.startListening() else vm.stopListening()
    }
    DisposableEffect(Unit) { onDispose { vm.stopListening() } }

    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 10.dp)) {
            IconButton(
                onClick = { if (!permission.granted) permission.request() else muted = !muted },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    if (listening) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = stringResource(R.string.mode_mic),
                    tint = if (listening) MaterialTheme.colorScheme.secondary else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            LinearProgressIndicator(
                progress = { if (listening) level else 0f },
                modifier = Modifier
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
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
    DisposableEffect(Unit) { onDispose { vm.stopListening() } }

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
    val story by vm.storyState.collectAsStateWithLifecycle()
    val hero by vm.hero.collectAsStateWithLifecycle()
    val actions = rememberLineActions(vm)
    val colors = MaterialTheme.colorScheme
    val scale = settings.textScale
    Column(modifier) {
        if (story.showRecapCard) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                RecapCard(story.recap, story.loadingRecap, onDismiss = vm::dismissRecapCard)
            }
        }
        CaptionStack(
            lines = lines,
            mode = settings.displayMode,
            showOriginal = settings.showOriginal,
            style = CaptionStyle(
                newestSp = 26f * scale,
                recentSp = 20f * scale,
                olderSp = 17f * scale,
                color = colors.onSurface,
                originalColor = colors.onSurfaceVariant,
                centered = false,
            ),
            actions = actions,
            paused = hero.frozen,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 20.dp),
            emptyContent = { EmptyFeed(settings) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun RecapCard(recap: String, loading: Boolean, onDismiss: () -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth(), glow = true, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.recap_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dismiss)) }
            }
            if (loading && recap.isBlank()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                MarkdownText(recap, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmptyFeed(settings: AppSettings) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GradientIcon(if (settings.liveMode == LiveMode.MIC) Icons.Filled.Mic else Icons.Filled.Subtitles, size = 64.dp)
        Text(
            stringResource(
                when (settings.liveMode) {
                    LiveMode.CAMERA -> R.string.empty_camera
                    LiveMode.BOTH -> R.string.empty_both
                    LiveMode.MIC -> R.string.empty_mic
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val RECENT_IN_FEED = 30
