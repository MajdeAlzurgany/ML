package ly.majde.gtatranslator.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.engine.Notice
import ly.majde.gtatranslator.ui.theme.NeonPink
import ly.majde.gtatranslator.ui.theme.OceanTeal
import ly.majde.gtatranslator.ui.theme.SunsetOrange

@Composable
fun noticeText(notice: Notice, detail: String?): String = when (notice) {
    Notice.NO_KEY -> stringResource(R.string.notice_no_key)
    Notice.OFFLINE_ONLY -> stringResource(R.string.notice_offline_only)
    Notice.INVALID_KEY -> stringResource(R.string.notice_invalid_key)
    Notice.RATE_LIMIT -> stringResource(R.string.notice_rate_limit)
    Notice.DAILY_LIMIT -> stringResource(R.string.notice_daily_limit)
    Notice.NETWORK -> stringResource(R.string.notice_network)
    Notice.SERVER -> stringResource(R.string.notice_server)
    Notice.REGION -> stringResource(R.string.notice_region)
    Notice.BLOCKED -> stringResource(R.string.notice_blocked)
    Notice.NOTHING_FOUND -> stringResource(R.string.notice_nothing_found)
    Notice.MIC_ERROR -> stringResource(R.string.notice_mic_error)
    Notice.BAD_REQUEST -> stringResource(R.string.notice_bad_request, (detail ?: "").take(140))
}

private fun Notice.isInfo() = this == Notice.OFFLINE_ONLY || this == Notice.RATE_LIMIT || this == Notice.NOTHING_FOUND

@Composable
fun NoticeBar(notice: Notice?, detail: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = notice != null, modifier = modifier) {
        if (notice == null) return@AnimatedVisibility
        val info = notice.isInfo()
        val colors = MaterialTheme.colorScheme
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (info) colors.secondaryContainer.copy(alpha = 0.6f) else colors.errorContainer,
            contentColor = if (info) colors.onSecondaryContainer else colors.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Icon(if (info) Icons.Filled.Info else Icons.Filled.WarningAmber, contentDescription = null)
                Text(
                    noticeText(notice, detail),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dismiss))
                }
            }
        }
    }
}

/** Animated "listening" visual whose rings react to the microphone level. */
@Composable
fun ListeningOrb(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val smooth by animateFloatAsState(targetValue = if (active) level else 0f, animationSpec = tween(120), label = "level")
    val pulse = rememberInfiniteTransition(label = "orb")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseR = size.minDimension * 0.26f
        if (active) {
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                drawCircle(
                    color = NeonPink.copy(alpha = (1f - p) * 0.35f),
                    radius = baseR + p * size.minDimension * 0.24f,
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(if (active) SunsetOrange else OceanTeal, if (active) NeonPink else Color(0xFF6C7BFF)),
                center = center,
                radius = baseR * 1.4f,
            ),
            radius = baseR * (1f + smooth * 0.45f),
            center = center,
        )
    }
}
