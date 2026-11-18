@file:OptIn(ExperimentalMaterial3Api::class)

package ly.majde.gtatranslator.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.data.NormRect
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.components.CameraHandle
import ly.majde.gtatranslator.ui.components.KeepScreenOn
import ly.majde.gtatranslator.ui.components.PermissionGate
import ly.majde.gtatranslator.ui.components.RoiOverlay
import ly.majde.gtatranslator.ui.components.StatusBanner
import ly.majde.gtatranslator.ui.components.SubtitleCameraView
import ly.majde.gtatranslator.ui.components.TranslationFeed
import ly.majde.gtatranslator.ui.components.rememberPermission

@Composable
fun CameraScreen(vm: MainViewModel) {
    val permission = rememberPermission(Manifest.permission.CAMERA)
    if (!permission.granted) {
        PermissionGate(
            icon = Icons.Filled.CameraAlt,
            title = stringResource(R.string.perm_camera_title),
            body = stringResource(R.string.perm_camera_body),
            onRequest = permission.request,
        )
        return
    }
    KeepScreenOn()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val state by vm.engineState.collectAsStateWithLifecycle()
    val liveText by vm.liveText.collectAsStateWithLifecycle()
    val paused by vm.cameraPaused.collectAsStateWithLifecycle()

    var roi by remember { mutableStateOf(settings.roi) }
    var zoom by rememberSaveable { mutableFloatStateOf(0f) }
    val handle = remember { CameraHandle() }

    val cameraPane: @Composable (Modifier) -> Unit = { paneModifier ->
        CameraPane(
            modifier = paneModifier,
            handle = handle,
            roi = roi,
            onRoiChange = { roi = it },
            onRoiCommit = { vm.updateSettings { s -> s.copy(roi = roi) } },
            zoom = zoom,
            onZoom = { zoom = it },
            liveText = liveText,
            paused = paused,
            onTogglePause = vm::toggleCameraPaused,
            onSnap = { handle.snapshot()?.let(vm::onSnapshot) },
            onText = vm::onOcrText,
        )
    }
    val feedPane: @Composable (Modifier) -> Unit = { paneModifier ->
        Column(paneModifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBanner(state, onDismiss = vm::dismissNotice, modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    IconButton(onClick = vm::clearHistory) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear_history))
                    }
                }
            }
            TranslationFeed(
                entries = entries,
                emptyHint = stringResource(R.string.camera_empty_hint),
                onExplainMore = vm::explainMore,
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                cameraPane(Modifier.weight(0.55f).fillMaxHeight())
                feedPane(Modifier.weight(0.45f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                cameraPane(Modifier.weight(0.5f).fillMaxWidth())
                feedPane(Modifier.weight(0.5f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CameraPane(
    modifier: Modifier,
    handle: CameraHandle,
    roi: NormRect,
    onRoiChange: (NormRect) -> Unit,
    onRoiCommit: () -> Unit,
    zoom: Float,
    onZoom: (Float) -> Unit,
    liveText: String,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onSnap: () -> Unit,
    onText: (String) -> Unit,
) {
    Column(modifier) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            // Camera coordinates are physical, so the preview + overlay are always laid out LTR.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SubtitleCameraView(
                    handle = handle,
                    roi = roi,
                    zoom = zoom,
                    onText = onText,
                    modifier = Modifier.fillMaxSize(),
                )
                RoiOverlay(
                    roi = roi,
                    onChange = onRoiChange,
                    onChangeFinished = onRoiCommit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (liveText.isNotBlank() || paused) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                ) {
                    Text(
                        text = if (paused) stringResource(R.string.camera_paused) else liveText,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onTogglePause) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = stringResource(if (paused) R.string.camera_resume else R.string.camera_pause),
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ZoomIn,
                contentDescription = stringResource(R.string.zoom),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(value = zoom, onValueChange = onZoom, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSnap) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.snap))
            }
        }
    }
}
