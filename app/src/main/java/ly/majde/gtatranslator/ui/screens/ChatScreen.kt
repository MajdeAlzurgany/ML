@file:OptIn(ExperimentalLayoutApi::class)

package ly.majde.gtatranslator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.ai.Role
import ly.majde.gtatranslator.engine.ChatMessage
import ly.majde.gtatranslator.engine.LiveLine
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.components.GradientIcon
import ly.majde.gtatranslator.ui.components.MarkdownText
import ly.majde.gtatranslator.ui.components.Pill
import ly.majde.gtatranslator.ui.components.noticeText
import ly.majde.gtatranslator.ui.theme.SunsetBrush

@Composable
fun ChatScreen(vm: MainViewModel) {
    val messages by vm.chatMessages.collectAsStateWithLifecycle()
    val streaming by vm.chatStreaming.collectAsStateWithLifecycle()
    val askRequest by vm.askRequest.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var quote by remember { mutableStateOf<LiveLine?>(null) }
    val explainLine = stringResource(R.string.chat_explain_line)

    LaunchedEffect(askRequest) {
        val line = askRequest ?: return@LaunchedEffect
        quote = line
        input = explainLine
        vm.consumeAsk()
    }

    val send: (String) -> Unit = { text ->
        if (text.isNotBlank() && !streaming) {
            vm.sendChat(text, quote)
            input = ""
            quote = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(
                    stringResource(R.string.chat_subtitle, lines.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = vm::clearChat) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear))
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                ChatEmptyState(onPick = send)
            } else {
                val listState = rememberLazyListState()
                val last = messages.last()
                LaunchedEffect(messages.size, last.text.length) {
                    listState.scrollToItem(messages.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(messages, key = { it.id }) { msg -> ChatBubble(msg) }
                }
            }
        }

        quote?.let { q ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Icon(Icons.Filled.FormatQuote, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(q.original, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            q.bestTranslation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { quote = null }) { Icon(Icons.Filled.Close, contentDescription = null) }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.chat_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (streaming) {
                FilledIconButton(
                    onClick = vm::stopChat,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop)) }
            } else {
                FilledIconButton(
                    onClick = { send(input) },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send)) }
            }
        }
    }
}

@Composable
private fun ChatEmptyState(onPick: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.chat_suggest_summary),
        stringResource(R.string.chat_suggest_characters),
        stringResource(R.string.chat_suggest_last_line),
        stringResource(R.string.chat_suggest_joke),
        stringResource(R.string.chat_suggest_slang),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        GradientIcon(Icons.Filled.AutoAwesome, size = 72.dp)
        Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { s ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { onPick(s) },
                ) {
                    Text(s, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    val isUser = msg.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End,
    ) {
        val shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = if (isUser) 6.dp else 20.dp,
            bottomEnd = if (isUser) 20.dp else 6.dp,
        )
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .then(if (isUser) Modifier.background(SunsetBrush) else Modifier.background(colors.surfaceContainerHigh))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            msg.quote?.let { q ->
                Surface(color = colors.scrim.copy(alpha = 0.18f), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        q,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            if (isUser) {
                Text(msg.text, style = MaterialTheme.typography.bodyLarge, color = colors.onPrimary, fontWeight = FontWeight.Medium)
            } else {
                val body = if (msg.streaming && msg.text.isEmpty()) "…" else msg.text + if (msg.streaming) " ▍" else ""
                if (body.isNotBlank()) MarkdownText(body)
                msg.error?.let { err ->
                    Text(noticeText(err, null), style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
                if (msg.provider.isNotBlank() && !msg.streaming) {
                    Pill(msg.provider, icon = Icons.Filled.AutoAwesome)
                }
            }
        }
    }
}
