package ly.majde.gtatranslator.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.update.AppRelease
import ly.majde.gtatranslator.update.UpdateState

/**
 * "New version" pop-up shown when the app opens. "Update now" downloads and opens the
 * installer; "Later" hides it for this session (a small card stays on the live screen).
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    dismissedVersion: Int?,
    onUpdate: (AppRelease) -> Unit,
    onInstall: () -> Unit,
    onLater: (AppRelease) -> Unit,
    onBrowser: (AppRelease) -> Unit,
    onCancel: () -> Unit,
) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.Failed -> state.release
        is UpdateState.SignatureMismatch -> state.release
        else -> null
    } ?: return
    if (dismissedVersion == release.versionCode) return

    val downloading = state is UpdateState.Downloading
    AlertDialog(
        onDismissRequest = { if (!downloading) onLater(release) },
        properties = DialogProperties(dismissOnClickOutside = !downloading, dismissOnBackPress = !downloading),
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Text(stringResource(R.string.update_dialog_title, release.versionName), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (release.notes.isNotBlank()) {
                    Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(release.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    stringResource(R.string.update_size, release.sizeBytes / 1_000_000f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    is UpdateState.Downloading -> {
                        Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.update_progress_mb,
                                state.downloadedBytes / 1_000_000f,
                                (if (state.totalBytes > 0L) state.totalBytes else release.sizeBytes) / 1_000_000f,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateState.SignatureMismatch -> Text(
                        stringResource(R.string.update_signature_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is UpdateState.Failed -> Text(
                        stringResource(R.string.update_failed_install),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is UpdateState.ReadyToInstall -> Text(
                        stringResource(R.string.update_ready_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Downloading -> Unit
                is UpdateState.SignatureMismatch -> Button(onClick = { onBrowser(release) }) { Text(stringResource(R.string.update_browser)) }
                is UpdateState.ReadyToInstall -> Button(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
                is UpdateState.Failed -> Button(onClick = { onUpdate(release) }) { Text(stringResource(R.string.update_retry)) }
                else -> Button(onClick = { onUpdate(release) }) { Text(stringResource(R.string.update_now)) }
            }
        },
        dismissButton = {
            if (downloading) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            } else {
                if (state is UpdateState.Failed) {
                    TextButton(onClick = { onBrowser(release) }) { Text(stringResource(R.string.update_browser)) }
                } else {
                    TextButton(onClick = { onLater(release) }) { Text(stringResource(R.string.update_later)) }
                }
            }
        },
    )
}
