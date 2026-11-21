package ly.majde.gtatranslator.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.ui.components.UpdateDialog
import ly.majde.gtatranslator.ui.screens.ChatScreen
import ly.majde.gtatranslator.ui.screens.HistoryScreen
import ly.majde.gtatranslator.ui.screens.LiveScreen
import ly.majde.gtatranslator.ui.screens.OnboardingScreen
import ly.majde.gtatranslator.ui.screens.ReadingOverlay
import ly.majde.gtatranslator.ui.screens.SettingsScreen
import ly.majde.gtatranslator.ui.screens.WordsScreen
import ly.majde.gtatranslator.ui.theme.NightBrush

private enum class AppTab(@StringRes val label: Int, val icon: ImageVector) {
    LIVE(R.string.tab_live, Icons.Filled.Subtitles),
    CHAT(R.string.tab_chat, Icons.Filled.Forum),
    WORDS(R.string.tab_words, Icons.Filled.School),
    HISTORY(R.string.tab_history, Icons.Filled.History),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    if (!settings.onboardingDone) {
        OnboardingScreen(vm)
        return
    }

    var tab by rememberSaveable { mutableStateOf(AppTab.LIVE) }
    val askRequest by vm.askRequest.collectAsStateWithLifecycle()
    LaunchedEffect(askRequest) {
        if (askRequest != null) tab = AppTab.CHAT
    }

    val reading by vm.reading.collectAsStateWithLifecycle()
    LaunchedEffect(tab) { if (tab != AppTab.LIVE) vm.exitReading() }

    // Look for a new version whenever the app comes back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.onAppForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val update by vm.updateState.collectAsStateWithLifecycle()
    val postponed by vm.updatePopupDismissed.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(NightBrush),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label), maxLines = 1) },
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                AppTab.LIVE -> LiveScreen(vm)
                AppTab.CHAT -> ChatScreen(vm)
                AppTab.WORDS -> WordsScreen(vm)
                AppTab.HISTORY -> HistoryScreen(vm)
                AppTab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
    // Full-screen reading sits on top; the live screen (camera/mic) keeps running underneath.
    if (reading && tab == AppTab.LIVE) ReadingOverlay(vm)
    }

    UpdateDialog(
        state = update,
        dismissedVersion = postponed,
        onUpdate = vm::installUpdate,
        onInstall = vm::retryInstall,
        onLater = vm::postponeUpdate,
        onBrowser = vm::openUpdateInBrowser,
        onCancel = vm::cancelUpdate,
    )
}
