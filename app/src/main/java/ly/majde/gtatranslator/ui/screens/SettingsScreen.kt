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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.BuildConfig
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.data.AppSettings
import ly.majde.gtatranslator.data.DetailLevel
import ly.majde.gtatranslator.data.OutputStyle
import ly.majde.gtatranslator.translate.OfflineTranslator
import ly.majde.gtatranslator.ui.KeyCheck
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.Provider
import ly.majde.gtatranslator.ui.components.GlowCard
import ly.majde.gtatranslator.ui.components.Pill
import ly.majde.gtatranslator.ui.components.noticeText

const val GROQ_KEYS_URL = "https://console.groq.com/keys"
const val GEMINI_KEYS_URL = "https://aistudio.google.com/apikey"

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keyChecks by vm.keyChecks.collectAsStateWithLifecycle()
    val groqModels by vm.groqModels.collectAsStateWithLifecycle()
    val offlineState by vm.offlineState.collectAsStateWithLifecycle()
    var textScale by remember { mutableFloatStateOf(settings.textScale) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)

        Section(stringResource(R.string.section_ai)) {
            Text(
                stringResource(R.string.ai_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyEditor(
                title = stringResource(R.string.groq_title),
                subtitle = stringResource(R.string.groq_subtitle),
                badge = stringResource(R.string.recommended),
                current = settings.groqKey,
                check = keyChecks[Provider.GROQ] ?: KeyCheck.Idle,
                getKeyUrl = GROQ_KEYS_URL,
                onSave = { vm.saveAndCheckKey(Provider.GROQ, it) },
            )
            HorizontalDivider()
            KeyEditor(
                title = stringResource(R.string.gemini_title),
                subtitle = stringResource(R.string.gemini_subtitle),
                badge = stringResource(R.string.optional),
                current = settings.geminiKey,
                check = keyChecks[Provider.GEMINI] ?: KeyCheck.Idle,
                getKeyUrl = GEMINI_KEYS_URL,
                onSave = { vm.saveAndCheckKey(Provider.GEMINI, it) },
            )
        }

        Section(stringResource(R.string.section_models)) {
            ModelPicker(
                label = stringResource(R.string.groq_model),
                value = settings.groqModel,
                autoLabel = stringResource(R.string.model_auto),
                options = groqModels.filter { m -> listOf("whisper", "guard", "orpheus", "tts", "compound").none { m.contains(it) } },
                onSelect = { m -> vm.updateSettings { it.copy(groqModel = m) } },
            )
            ModelPicker(
                label = stringResource(R.string.gemini_model),
                value = settings.geminiModel,
                autoLabel = AppSettings.DEFAULT_GEMINI_MODEL,
                options = vm.geminiModels.collectAsStateWithLifecycle().value,
                onSelect = { m -> vm.updateSettings { it.copy(geminiModel = m.ifBlank { AppSettings.DEFAULT_GEMINI_MODEL }) } },
            )
            Text(
                stringResource(R.string.models_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(stringResource(R.string.section_offline)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (offlineState) {
                    OfflineTranslator.State.READY -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                    OfflineTranslator.State.DOWNLOADING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    OfflineTranslator.State.FAILED -> Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                    OfflineTranslator.State.IDLE -> Icon(Icons.Filled.CloudDownload, null)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        when (offlineState) {
                            OfflineTranslator.State.READY -> R.string.offline_ready
                            OfflineTranslator.State.DOWNLOADING -> R.string.offline_downloading
                            OfflineTranslator.State.FAILED -> R.string.offline_failed
                            OfflineTranslator.State.IDLE -> R.string.offline_idle
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (offlineState == OfflineTranslator.State.FAILED || offlineState == OfflineTranslator.State.IDLE) {
                    TextButton(onClick = vm::retryOfflinePack) { Text(stringResource(R.string.download)) }
                }
            }
            Text(
                stringResource(R.string.offline_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(stringResource(R.string.section_language)) {
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

        Section(stringResource(R.string.section_display)) {
            Text(stringResource(R.string.text_size), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = textScale,
                onValueChange = { textScale = it },
                valueRange = 0.85f..1.6f,
                onValueChangeFinished = { vm.updateSettings { it.copy(textScale = textScale) } },
            )
            Text(
                stringResource(R.string.text_size_preview),
                style = MaterialTheme.typography.headlineSmall.let { it.copy(fontSize = it.fontSize * textScale) },
                fontWeight = FontWeight.ExtraBold,
            )
            SwitchRow(stringResource(R.string.show_original), settings.showOriginal) { v -> vm.updateSettings { it.copy(showOriginal = v) } }
            SwitchRow(stringResource(R.string.speak_translations), settings.speak) { v -> vm.updateSettings { it.copy(speak = v) } }
            SwitchRow(stringResource(R.string.web_search), settings.webSearch) { v -> vm.updateSettings { it.copy(webSearch = v) } }
        }

        Section(stringResource(R.string.section_privacy)) {
            Text(
                stringResource(R.string.privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = { vm.updateSettings { it.copy(onboardingDone = false) } }) {
            Text(stringResource(R.string.replay_onboarding))
        }
        Text(
            "v${BuildConfig.VERSION_NAME} · Tajawal font © its authors (SIL OFL 1.1)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun KeyEditor(
    title: String,
    subtitle: String,
    badge: String,
    current: String,
    check: KeyCheck,
    getKeyUrl: String,
    onSave: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var input by rememberSaveable(current) { mutableStateOf(current) }
    var show by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Pill(badge)
            Spacer(Modifier.weight(1f))
            if (current.isNotBlank()) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.trim() },
            label = { Text(stringResource(R.string.api_key)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(input) }) { Text(stringResource(R.string.save_and_test)) }
            OutlinedButton(onClick = { uriHandler.openUri(getKeyUrl) }) { Text(stringResource(R.string.get_free_key)) }
        }
        KeyCheckRow(check)
    }
}

@Composable
fun KeyCheckRow(state: KeyCheck) {
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
        is KeyCheck.Failed -> Text(
            stringResource(R.string.key_failed) + " " + noticeText(state.notice, state.detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ModelPicker(label: String, value: String, autoLabel: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value.ifBlank { autoLabel },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                IconButton(onClick = { open = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(autoLabel) }, onClick = {
                onSelect("")
                open = false
            })
            options.forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = {
                    onSelect(m)
                    open = false
                })
            }
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
