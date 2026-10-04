package org.dlang.liveimap

import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.ui.LiveImapNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
        enableEdgeToEdge()
        setContent {
            val appContext = LocalContext.current.applicationContext
            val store = remember { DataStoreSettingsStore(appContext) }
            var theme by remember { mutableStateOf(ThemeMode.FollowSystem) }
            var dynamicColor by remember { mutableStateOf(true) }
            LaunchedEffect(store) {
                store.theme().collect {
                    val loaded = store.load()
                    theme = loaded.theme
                    dynamicColor = loaded.dynamicColor
                }
            }
            val dark = when (theme) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.FollowSystem -> isSystemInDarkTheme()
            }
            val context = LocalContext.current
            val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            MaterialTheme(colorScheme = colorScheme) {
                LiveImapNavHost()
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            lifecycleScope.launch(NonCancellable) {
                mailSession().suspendConnections()
            }
        }
        super.onStop()
    }
}
