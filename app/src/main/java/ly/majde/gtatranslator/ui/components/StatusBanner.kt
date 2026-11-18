package ly.majde.gtatranslator.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.engine.EngineState
import ly.majde.gtatranslator.engine.Notice

@Composable
fun noticeMessage(notice: Notice, detail: String?, secondsLeft: Int = 0): String = when (notice) {
    Notice.NO_KEY -> stringResource(R.string.notice_no_key)
    Notice.INVALID_KEY -> stringResource(R.string.notice_invalid_key)
    Notice.RATE_LIMIT -> stringResource(R.string.notice_rate_limit, secondsLeft)
    Notice.DAILY_QUOTA -> stringResource(R.string.notice_daily_quota)
    Notice.BLOCKED -> stringResource(R.string.notice_blocked)
    Notice.NETWORK -> stringResource(R.string.notice_network)
    Notice.SERVER -> stringResource(R.string.notice_server)
    Notice.MODEL_NOT_FOUND -> stringResource(R.string.notice_model_not_found)
    Notice.REGION -> stringResource(R.string.notice_region)
    Notice.BAD_REQUEST -> stringResource(R.string.notice_bad_request, (detail ?: "").take(160))
    Notice.PARSE -> stringResource(R.string.notice_parse)
    Notice.NOTHING_FOUND -> stringResource(R.string.notice_nothing_found)
}

/** Shows "working…" progress and any problem (quota, key, network) in one strip. */
@Composable
fun StatusBanner(state: EngineState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.cooldownUntil) {
        while (state.cooldownUntil > SystemClock.elapsedRealtime()) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
        now = SystemClock.elapsedRealtime()
    }
    val secondsLeft = ((state.cooldownUntil - now).coerceAtLeast(0L) / 1000L).toInt() + 1

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.busy || state.pending > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.status_working, state.pending),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val notice = state.notice
        if (notice != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        noticeMessage(notice, state.noticeDetail, secondsLeft),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dismiss))
                    }
                }
            }
        }
    }
}
