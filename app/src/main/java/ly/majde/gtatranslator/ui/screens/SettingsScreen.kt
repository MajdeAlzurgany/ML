package ly.majde.gtatranslator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import ly.majde.gtatranslator.BuildConfig
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.data.AppSettings
import ly.majde.gtatranslator.data.DetailLevel
import ly.majde.gtatranslator.data.OutputStyle
import ly.majde.gtatranslator.ui.KeyCheck
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.components.noticeMessage

private const val AI_STUDIO_KEY_URL = "https://aistudio.google.com/apikey"

@Composable
fun SettingsScreen(vm: MainViewModel, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val keyCheck by vm.keyCheck.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    var keyInput by rememberSaveable { mutableStateOf(settings.apiKey) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var modelInput by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var rpm by remember { mutableIntStateOf(settings.requestsPerMinute) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SectionCard(stringResource(R.string.section_key)) {
            Text(
                stringResource(R.string.key_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it.trim() },
                label = { Text(stringResource(R.string.key_label)) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val key = keyInput.trim()
                    vm.updateSettings { it.copy(apiKey = key) }
                    vm.checkKey(key)
                }) { Text(stringResource(R.string.save_and_test)) }
                OutlinedButton(onClick = { uriHandler.openUri(AI_STUDIO_KEY_URL) }) {
                    Text(stringResource(R.string.get_free_key))
                }
            }
            KeyCheckRow(keyCheck)
        }

        SectionCard(stringResource(R.string.section_model)) {
            Box {
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it.trim() },
                    label = { Text(stringResource(R.string.model_label)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            if (models.isEmpty() && settings.apiKey.isNotBlank()) vm.checkKey()
                            modelMenuOpen = true
                        }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    (listOf(AppSettings.DEFAULT_MODEL) + models).distinct().forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                modelInput = name
                                modelMenuOpen = false
                                vm.updateSettings { it.copy(model = name) }
                            },
                        )
                    }
                }
            }
            if (modelInput != settings.model) {
                TextButton(onClick = {
                    val name = modelInput.ifBlank { AppSettings.DEFAULT_MODEL }
                    vm.updateSettings { it.copy(model = name) }
                }) { Text(stringResource(R.string.model_apply)) }
            }
            Text(
                stringResource(R.string.model_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(stringResource(R.string.section_language)) {
            RadioRow(stringResource(R.string.style_libyan), settings.style == OutputStyle.LIBYAN) {
                vm.updateSettings { it.copy(style = OutputStyle.LIBYAN) }
            }
            RadioRow(stringResource(R.string.style_msa), settings.style == OutputStyle.MSA) {
                vm.updateSettings { it.copy(style = OutputStyle.MSA) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            RadioRow(stringResource(R.string.detail_short), settings.detail == DetailLevel.SHORT) {
                vm.updateSettings { it.copy(detail = DetailLevel.SHORT) }
            }
            RadioRow(stringResource(R.string.detail_detailed), settings.detail == DetailLevel.DETAILED) {
                vm.updateSettings { it.copy(detail = DetailLevel.DETAILED) }
            }
        }

        SectionCard(stringResource(R.string.section_quota)) {
            Text(stringResource(R.string.rpm_value, rpm), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = rpm.toFloat(),
                onValueChange = { rpm = it.roundToInt() },
                valueRange = 2f..30f,
                steps = 27,
                onValueChangeFinished = { vm.updateSettings { s -> s.copy(requestsPerMinute = rpm) } },
            )
            Text(
                stringResource(R.string.rpm_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(stringResource(R.string.section_privacy)) {
            Text(
                stringResource(R.string.privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (settings.apiKey.isNotBlank()) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.start_now))
            }
        }
        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun KeyCheckRow(state: KeyCheck) {
    when (state) {
        KeyCheck.Idle -> Unit
        KeyCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.key_checking), style = MaterialTheme.typography.bodySmall)
        }
        is KeyCheck.Ok -> Text(
            stringResource(R.string.key_ok, state.modelCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        is KeyCheck.Failed -> Column {
            Text(
                stringResource(R.string.key_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                noticeMessage(state.notice, state.detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
