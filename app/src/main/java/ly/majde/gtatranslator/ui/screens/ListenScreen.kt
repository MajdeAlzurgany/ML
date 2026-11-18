@file:OptIn(ExperimentalMaterial3Api::class)

package ly.majde.gtatranslator.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.components.KeepScreenOn
import ly.majde.gtatranslator.ui.components.PermissionGate
import ly.majde.gtatranslator.ui.components.StatusBanner
import ly.majde.gtatranslator.ui.components.TranslationFeed
import ly.majde.gtatranslator.ui.components.rememberPermission

@Composable
fun ListenScreen(vm: MainViewModel) {
    val permission = rememberPermission(Manifest.permission.RECORD_AUDIO)
    if (!permission.granted) {
        PermissionGate(
            icon = Icons.Filled.Mic,
            title = stringResource(R.string.perm_mic_title),
            body = stringResource(R.string.perm_mic_body),
            onRequest = permission.request,
        )
        return
    }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val state by vm.engineState.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val level by vm.micLevel.collectAsStateWithLifecycle()
    val micError by vm.micError.collectAsStateWithLifecycle()
    var sensitivity by remember { mutableFloatStateOf(settings.micSensitivity) }

    // Stop recording when leaving this tab.
    DisposableEffect(Unit) { onDispose { vm.stopListening() } }
    if (listening) KeepScreenOn()

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LargeFloatingActionButton(
                onClick = { if (listening) vm.stopListening() else vm.startListening() },
                containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                stringResource(if (listening) R.string.listening_on else R.string.listening_off),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
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
            Text(
                stringResource(R.string.listen_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (micError) {
                Text(
                    stringResource(R.string.mic_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
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
            emptyHint = stringResource(R.string.listen_empty_hint),
            onExplainMore = vm::explainMore,
        )
    }
}
