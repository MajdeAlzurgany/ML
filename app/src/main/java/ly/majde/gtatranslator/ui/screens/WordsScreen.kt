package ly.majde.gtatranslator.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.engine.SavedWord
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.components.GlowCard
import ly.majde.gtatranslator.ui.components.GradientIcon
import ly.majde.gtatranslator.ui.components.Pill

private enum class WordsMode { LIST, REVIEW, QUIZ }

@Composable
fun WordsScreen(vm: MainViewModel) {
    val words by vm.words.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(WordsMode.LIST) }
    val dueCount = remember(words) { words.count { it.due <= System.currentTimeMillis() } }

    when (mode) {
        WordsMode.LIST -> WordsList(vm, words, dueCount, onReview = { mode = WordsMode.REVIEW }, onQuiz = { mode = WordsMode.QUIZ })
        WordsMode.REVIEW -> Review(vm, onExit = { mode = WordsMode.LIST })
        WordsMode.QUIZ -> Quiz(vm, words, onExit = { mode = WordsMode.LIST })
    }
}

@Composable
private fun WordsList(vm: MainViewModel, words: List<SavedWord>, dueCount: Int, onReview: () -> Unit, onQuiz: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.words_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(
                stringResource(R.string.words_subtitle, words.size, dueCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (words.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                GradientIcon(Icons.Filled.School, size = 72.dp)
                Text(stringResource(R.string.words_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.words_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Button(onClick = onReview, enabled = dueCount > 0) {
                Icon(Icons.Filled.Style, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.words_review, dueCount))
            }
            FilledTonalButton(onClick = onQuiz, enabled = words.size >= 4) {
                Icon(Icons.Filled.Quiz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.words_quiz))
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(words, key = { it.id }) { w ->
                GlowCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                w.term,
                                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Text(w.meaning, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill(stringResource(R.string.words_level, w.box))
                                if (w.reviews > 0) Pill(stringResource(R.string.words_score, w.correct, w.reviews))
                            }
                        }
                        IconButton(onClick = { vm.speakEnglish(w.term) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.action_speak))
                        }
                        IconButton(onClick = { vm.removeWord(w.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clear))
                        }
                    }
                }
            }
        }
    }
}

/** Flashcards with spaced repetition: tap to flip, then say whether you knew it. */
@Composable
private fun Review(vm: MainViewModel, onExit: () -> Unit) {
    val queue = remember { vm.dueWords() }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var flipped by rememberSaveable { mutableStateOf(false) }
    var known by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.words_review_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onExit) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.back)) }
        }
        if (index >= queue.size) {
            Finished(stringResource(R.string.words_review_done, known, queue.size), onExit)
            return@Column
        }
        LinearProgressIndicator(progress = { index.toFloat() / queue.size }, modifier = Modifier.fillMaxWidth())
        val word = queue[index]
        val rotation by animateFloatAsState(targetValue = if (flipped) 180f else 0f, animationSpec = tween(350), label = "flip")
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12f * density
                }
                .clickable { flipped = !flipped },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
                if (rotation <= 90f) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            word.term,
                            style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center,
                        )
                        if (word.example.isNotBlank() && !word.example.equals(word.term, ignoreCase = true)) {
                            Text(
                                "“${word.example}”",
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(stringResource(R.string.words_tap_to_flip), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.graphicsLayer { rotationY = 180f },
                    ) {
                        Text(word.meaning, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        if (word.exampleTranslation.isNotBlank()) {
                            Text(
                                word.exampleTranslation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.speakEnglish(word.term) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.action_speak))
            }
            OutlinedButton(
                onClick = {
                    vm.gradeWord(word.id, knew = false)
                    flipped = false
                    index++
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.words_didnt_know)) }
            Button(
                onClick = {
                    vm.gradeWord(word.id, knew = true)
                    known++
                    flipped = false
                    index++
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.words_knew)) }
        }
    }
}

/** Quick multiple-choice quiz: pick the right meaning. */
@Composable
private fun Quiz(vm: MainViewModel, words: List<SavedWord>, onExit: () -> Unit) {
    val questions = remember {
        words.shuffled().take(10).map { w ->
            val wrong = words.filter { it.id != w.id && it.meaning != w.meaning }.shuffled().take(3).map { it.meaning }
            w to (wrong + w.meaning).shuffled()
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var score by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.words_quiz), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onExit) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.back)) }
        }
        if (index >= questions.size) {
            Finished(stringResource(R.string.words_quiz_done, score, questions.size), onExit)
            return@Column
        }
        LinearProgressIndicator(progress = { index.toFloat() / questions.size }, modifier = Modifier.fillMaxWidth())
        val (word, options) = questions[index]
        Text(stringResource(R.string.words_quiz_question), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            word.term,
            style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
            fontWeight = FontWeight.ExtraBold,
        )
        options.forEach { option ->
            val isRight = option == word.meaning
            val chosen = picked == option
            val colors = when {
                picked == null -> ButtonDefaults.outlinedButtonColors()
                isRight -> ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                chosen -> ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                else -> ButtonDefaults.outlinedButtonColors()
            }
            OutlinedButton(
                onClick = {
                    if (picked == null) {
                        picked = option
                        if (isRight) score++
                        vm.gradeWord(word.id, isRight)
                    }
                },
                colors = colors,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(option, modifier = Modifier.padding(vertical = 6.dp), textAlign = TextAlign.Center)
            }
        }
        if (picked != null) {
            Button(
                onClick = {
                    picked = null
                    index++
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.next)) }
        }
    }
}

@Composable
private fun Finished(message: String, onExit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GradientIcon(Icons.Filled.EmojiEvents, size = 84.dp)
        Text(message, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onExit) { Text(stringResource(R.string.back)) }
    }
}
