package ly.majde.gtatranslator.ui.components

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Size as AndroidSize
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import ly.majde.gtatranslator.camera.SubtitleText
import ly.majde.gtatranslator.camera.ThrottledAnalyzer
import ly.majde.gtatranslator.data.NormRect

/** Lets the screen grab the current preview frame for "snap & explain". */
class CameraHandle {
    @Volatile
    var previewView: PreviewView? = null

    fun snapshot(): Bitmap? = previewView?.bitmap
}

/**
 * Live camera preview + on-device OCR (ML Kit). Text coordinates are delivered in
 * preview-view space, so they line up exactly with the subtitle region drawn on top.
 */
@Composable
fun SubtitleCameraView(
    handle: CameraHandle,
    roi: NormRect,
    zoom: Float,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }
    val roiPx = remember { AtomicReference(RectF()) }
    val viewSize = remember { mutableStateOf(IntSize.Zero) }
    val latestOnText by rememberUpdatedState(onText)

    LaunchedEffect(roi, viewSize.value) {
        val s = viewSize.value
        roiPx.set(RectF(roi.left * s.width, roi.top * s.height, roi.right * s.width, roi.bottom * s.height))
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val mlKitAnalyzer = MlKitAnalyzer(
            listOf(recognizer),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            executor,
        ) { result ->
            val text = result.getValue(recognizer)
            if (text != null) latestOnText(SubtitleText.extract(text, roiPx.get()))
        }
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    AndroidSize(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()
        controller.setImageAnalysisAnalyzer(executor, ThrottledAnalyzer(mlKitAnalyzer, ANALYSIS_INTERVAL_MS))
        controller.bindToLifecycle(lifecycleOwner)

        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            recognizer.close()
            executor.shutdown()
        }
    }

    LaunchedEffect(zoom) {
        runCatching { controller.setLinearZoom(zoom.coerceIn(0f, 1f)) }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
                handle.previewView = this
            }
        },
        modifier = modifier.onSizeChanged { viewSize.value = it },
        onRelease = { handle.previewView = null },
    )
}

private const val ANALYSIS_INTERVAL_MS = 350L

/**
 * Draggable subtitle region: drag inside to move, drag the corner dots to resize.
 * Must be laid out left-to-right (coordinates are physical pixels).
 */
@Composable
fun RoiOverlay(
    roi: NormRect,
    onChange: (NormRect) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentRoi by rememberUpdatedState(roi)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinished by rememberUpdatedState(onChangeFinished)
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val l = roi.left * w
        val t = roi.top * h
        val r = roi.right * w
        val b = roi.bottom * h

        Canvas(Modifier.fillMaxSize()) {
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset.Zero, Size(size.width, t))
            drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
            drawRect(shade, Offset(0f, t), Size(l, b - t))
            drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
            drawRoundRect(
                color = accent,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        // Move: drag anywhere inside the region.
        Box(
            Modifier
                .absoluteOffset { IntOffset(l.roundToInt(), t.roundToInt()) }
                .size(with(density) { (r - l).toDp() }, with(density) { (b - t).toDp() })
                .roiDrag(
                    w, h,
                    current = { currentRoi },
                    transform = { start, dx, dy -> start.moveBy(dx, dy) },
                    onChange = { currentOnChange(it) },
                    onEnd = { currentOnFinished() },
                )
        )

        RoiHandle(cx = l, cy = t, color = accent) {
            Modifier.roiDrag(
                w, h,
                current = { currentRoi },
                transform = { start, dx, dy -> start.resizeTopLeft(dx, dy) },
                onChange = { currentOnChange(it) },
                onEnd = { currentOnFinished() },
            )
        }
        RoiHandle(cx = r, cy = b, color = accent) {
            Modifier.roiDrag(
                w, h,
                current = { currentRoi },
                transform = { start, dx, dy -> start.resizeBottomRight(dx, dy) },
                onChange = { currentOnChange(it) },
                onEnd = { currentOnFinished() },
            )
        }
    }
}

@Composable
private fun RoiHandle(cx: Float, cy: Float, color: Color, gesture: () -> Modifier) {
    val touchSize = 44.dp
    val half = with(LocalDensity.current) { (touchSize / 2).roundToPx() }
    Box(
        modifier = Modifier
            .absoluteOffset { IntOffset(cx.roundToInt() - half, cy.roundToInt() - half) }
            .size(touchSize)
            .then(gesture()),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(color, CircleShape)
                .border(2.dp, Color.White, CircleShape)
        )
    }
}

private fun Modifier.roiDrag(
    w: Float,
    h: Float,
    current: () -> NormRect,
    transform: (start: NormRect, dx: Float, dy: Float) -> NormRect,
    onChange: (NormRect) -> Unit,
    onEnd: () -> Unit,
): Modifier = pointerInput(w, h) {
    var start = current()
    var accumulated = Offset.Zero
    detectDragGestures(
        onDragStart = {
            start = current()
            accumulated = Offset.Zero
        },
        onDragEnd = onEnd,
        onDragCancel = onEnd,
    ) { change, dragAmount ->
        change.consume()
        accumulated += dragAmount
        onChange(transform(start, accumulated.x / w, accumulated.y / h))
    }
}
