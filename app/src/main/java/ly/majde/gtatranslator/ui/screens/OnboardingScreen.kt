package ly.majde.gtatranslator.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.translate.OfflineTranslator
import ly.majde.gtatranslator.ui.KeyCheck
import ly.majde.gtatranslator.ui.MainViewModel
import ly.majde.gtatranslator.ui.Provider
import ly.majde.gtatranslator.ui.components.GlowCard
import ly.majde.gtatranslator.ui.components.GradientIcon
import ly.majde.gtatranslator.ui.theme.NightBrush
import ly.majde.gtatranslator.ui.theme.SunsetBrush

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keyChecks by vm.keyChecks.collectAsStateWithLifecycle()
    val offlineState by vm.offlineState.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(NightBrush)
    ) {
        Column(Modifier.fillMaxSize()) {
            StepDots(step = step, count = 4, modifier = Modifier.padding(top = 20.dp).align(Alignment.CenterHorizontally))
            AnimatedContent(
                targetState = step,
                transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut() },
                label = "onboarding",
                modifier = Modifier.weight(1f),
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> {
                            StepTitle(Icons.Filled.Bolt, stringResource(R.string.onb_groq_title), stringResource(R.string.onb_groq_body))
                            GlowCard {
                                Column(Modifier.padding(16.dp)) {
                                    KeyEditor(
                                        title = stringResource(R.string.groq_title),
                                        subtitle = stringResource(R.string.onb_groq_steps),
                                        badge = stringResource(R.string.recommended),
                                        current = settings.groqKey,
                                        check = keyChecks[Provider.GROQ] ?: KeyCheck.Idle,
                                        getKeyUrl = GROQ_KEYS_URL,
                                        onSave = { vm.saveAndCheckKey(Provider.GROQ, it) },
                                    )
                                }
                            }
                        }
                        2 -> {
                            StepTitle(Icons.Filled.AutoAwesome, stringResource(R.string.onb_gemini_title), stringResource(R.string.onb_gemini_body))
                            GlowCard {
                                Column(Modifier.padding(16.dp)) {
                                    KeyEditor(
                                        title = stringResource(R.string.gemini_title),
                                        subtitle = stringResource(R.string.onb_gemini_steps),
                                        badge = stringResource(R.string.optional),
                                        current = settings.geminiKey,
                                        check = keyChecks[Provider.GEMINI] ?: KeyCheck.Idle,
                                        getKeyUrl = GEMINI_KEYS_URL,
                                        onSave = { vm.saveAndCheckKey(Provider.GEMINI, it) },
                                    )
                                }
                            }
                        }
                        else -> {
                            StepTitle(Icons.Filled.SportsEsports, stringResource(R.string.onb_ready_title), stringResource(R.string.onb_ready_body))
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
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            if (!settings.hasAnyKey) {
                                Text(
                                    stringResource(R.string.onb_no_key_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) { Text(stringResource(R.string.back)) }
                }
                Spacer(Modifier.weight(1f))
                if (step == 1 || step == 2) {
                    TextButton(onClick = { step++ }) { Text(stringResource(R.string.skip)) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = {
                    if (step < 3) step++ else vm.updateSettings { it.copy(onboardingDone = true) }
                }) {
                    Text(stringResource(if (step < 3) R.string.next else R.string.start_now))
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        GradientIcon(Icons.Filled.Subtitles, size = 96.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.onb_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Feature(Icons.Filled.Bolt, stringResource(R.string.onb_f1_title), stringResource(R.string.onb_f1_body))
    Feature(Icons.Filled.Hearing, stringResource(R.string.onb_f2_title), stringResource(R.string.onb_f2_body))
    Feature(Icons.Filled.Forum, stringResource(R.string.onb_f3_title), stringResource(R.string.onb_f3_body))
    Feature(Icons.Filled.Key, stringResource(R.string.onb_f4_title), stringResource(R.string.onb_f4_body))
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientIcon(icon, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepTitle(icon: ImageVector, title: String, body: String) {
    GradientIcon(icon, size = 64.dp)
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StepDots(step: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(width = if (i == step) 22.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .then(
                        if (i == step) Modifier.background(SunsetBrush)
                        else Modifier.background(MaterialTheme.colorScheme.outlineVariant)
                    )
            )
        }
    }
}
