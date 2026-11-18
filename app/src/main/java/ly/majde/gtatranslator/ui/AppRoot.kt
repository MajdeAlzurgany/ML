package ly.majde.gtatranslator.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ly.majde.gtatranslator.R
import ly.majde.gtatranslator.ui.screens.CameraScreen
import ly.majde.gtatranslator.ui.screens.ListenScreen
import ly.majde.gtatranslator.ui.screens.ManualScreen
import ly.majde.gtatranslator.ui.screens.SettingsScreen

private enum class AppTab(@StringRes val label: Int, val icon: ImageVector) {
    CAMERA(R.string.tab_camera, Icons.Filled.CameraAlt),
    LISTEN(R.string.tab_listen, Icons.Filled.Hearing),
    TYPE(R.string.tab_type, Icons.Filled.Keyboard),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable {
        mutableStateOf(if (settings.apiKey.isBlank()) AppTab.SETTINGS else AppTab.CAMERA)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
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
                AppTab.CAMERA -> CameraScreen(vm)
                AppTab.LISTEN -> ListenScreen(vm)
                AppTab.TYPE -> ManualScreen(vm)
                AppTab.SETTINGS -> SettingsScreen(vm, onDone = { tab = AppTab.CAMERA })
            }
        }
    }
}
