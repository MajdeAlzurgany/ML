package ly.majde.gtatranslator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.components.StatusBanner
import ly.majde.gtatranslator.ui.components.TranslationFeed

@Composable
fun ManualScreen(vm: MainViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val state by vm.engineState.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    val send = {
        if (input.isNotBlank()) {
            vm.submitManual(input)
            input = ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.manual_hint)) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = { send() }, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.manual_send))
            }
        }
        StatusBanner(state, onDismiss = vm::dismissNotice)
        TranslationFeed(
            entries = entries,
            emptyHint = stringResource(R.string.manual_empty_hint),
            onExplainMore = vm::explainMore,
        )
    }
}
