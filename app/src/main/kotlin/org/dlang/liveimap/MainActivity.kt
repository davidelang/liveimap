package org.dlang.liveimap

import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.ThemeMode
import org.dlang.liveimap.ui.NavHost

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
            LaunchedEffect(store) {
                theme = store.load().theme
            }
            val dark = when (theme) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.FollowSystem -> isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                NavHost()
            }
        }
    }
}
