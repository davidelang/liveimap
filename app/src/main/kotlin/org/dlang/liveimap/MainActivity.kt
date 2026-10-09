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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.applyExtraIdle
import org.dlang.liveimap.session.stopExtraIdle
import org.dlang.liveimap.session.suspendMailSessions
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.smoke.smokeLaunch
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.ui.LiveImapNavHost
import org.dlang.liveimap.ui.compose.ComposeBackgroundSave

class MainActivity : ComponentActivity() {
    private lateinit var store: DataStoreSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DataStoreSettingsStore(applicationContext)
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
            var theme by remember { mutableStateOf(ThemeMode.FollowSystem) }
            var dynamicColor by remember { mutableStateOf(true) }
            var fullScreen by remember { mutableStateOf(false) }
            LaunchedEffect(store) {
                store.theme().collect {
                    val loaded = store.load()
                    theme = loaded.theme
                    dynamicColor = loaded.dynamicColor
                    fullScreen = loaded.fullScreen
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
            val immersive = fullScreen
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
                val bars = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
                if (immersive) {
                    controller.hide(bars)
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(bars)
                }
            }
            MaterialTheme(colorScheme = colorScheme) {
                LiveImapNavHost()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (smokeLaunch(intent)) return
        lifecycleScope.launch {
            try {
                applyExtraIdle(store.load())
            } catch (_: MailFailure) {
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            lifecycleScope.launch(NonCancellable) {
                try {
                    ComposeBackgroundSave.hook?.invoke()
                } finally {
                    try {
                        suspendMailSessions()
                    } finally {
                        stopExtraIdle()
                    }
                }
            }
        }
        super.onStop()
    }
}
