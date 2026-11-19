package ly.majde.gtatranslator.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.ui.theme.SunsetBrush

// ---- Permissions --------------------------------------------------------------------------

class PermissionHandle(val granted: Boolean, val request: () -> Unit)

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun rememberPermission(permission: String): PermissionHandle {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.isGranted(permission)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.isGranted(permission)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return PermissionHandle(granted) { launcher.launch(permission) }
}

@Composable
fun PermissionGate(icon: ImageVector, title: String, body: String, onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GradientIcon(icon, size = 72.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text(stringResource(R.string.perm_allow)) }
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }) { Text(stringResource(R.string.perm_open_settings)) }
    }
}

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

// ---- Visual building blocks -----------------------------------------------------------------

/** Icon inside a glowing sunset-gradient circle. */
@Composable
fun GradientIcon(icon: ImageVector, size: Dp = 56.dp, brush: Brush = SunsetBrush) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Card surface with an optional gradient outline. */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    glow: Boolean = false,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    val outlined = if (glow) {
        modifier.border(1.5.dp, SunsetBrush, shape)
    } else {
        modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
    }
    Surface(modifier = outlined, shape = shape, color = color, content = content)
}

/** Small rounded label. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = color, contentColor = contentColor) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

// ---- Light markdown (bold, bullets, headings) for chat answers -----------------------------

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified) {
    val accent = MaterialTheme.colorScheme.secondary
    Text(text = remember(text, accent) { renderMarkdown(text, accent) }, modifier = modifier, style = style, color = color)
}

private val boldRegex = Regex("\\*\\*(.+?)\\*\\*")

private fun renderMarkdown(src: String, accent: Color): AnnotatedString = buildAnnotatedString {
    val lines = src.lines()
    lines.forEachIndexed { index, rawLine ->
        var line = rawLine
        var heading = false
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("#") -> {
                line = trimmed.trimStart('#').trim()
                heading = true
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> line = "• " + trimmed.substring(2)
        }
        if (heading) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { appendInline(line) }
        } else {
            appendInline(line)
        }
        if (index < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInline(line: String) {
    var last = 0
    for (match in boldRegex.findAll(line)) {
        append(line.substring(last, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
        last = match.range.last + 1
    }
    append(line.substring(last))
}
