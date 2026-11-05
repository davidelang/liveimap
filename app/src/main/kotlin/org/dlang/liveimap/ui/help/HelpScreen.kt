package org.dlang.liveimap.ui.help
import org.dlang.liveimap.i18n.S

import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.davidlang.liveimap.BuildConfig
import android.widget.Toast
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.trafficText
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.SettingsHub

const val HELP_ISSUES_URL = "https://github.com/davidelang/liveimap/issues"

@Composable
fun HelpScreen() {
    val context = LocalContext.current
    val unreadable = S("The help text could not be read.")
    val gettingStartedTitle = S("Getting started")
    val reportingTitle = S("Reporting a bug")
    val reportBug = S("Report a bug")
    val copyReport = S("Copy debug report")
    val appContext = context.applicationContext
    val assets = context.assets
    val store = remember { DataStoreSettingsStore(appContext) }
    val scope = rememberCoroutineScope()
    var gettingStarted by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(assets) {
        val loaded = withContext(Dispatchers.IO) { readHelpAssets(assets) }
        if (loaded == null) {
            missing = true
        } else {
            gettingStarted = loaded.first
            reporting = loaded.second
        }
    }
    val started = gettingStarted
    val bugReport = reporting
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (missing) {
            Text(unreadable)
        } else if (started != null && bugReport != null) {
            Text(
                text = gettingStartedTitle,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SelectionContainer {
                Text(text = started, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = reportingTitle,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SelectionContainer {
                Text(text = bugReport, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (missing || (started != null && bugReport != null)) {
            Row {
                TextButton(onClick = {
                    org.dlang.liveimap.ui.openWebPage(context, MANUAL_URL)
                }) { Text(S("Online manual")) }
                TextButton(onClick = {
                    org.dlang.liveimap.ui.openWebPage(context, HELP_ISSUES_URL)
                }) { Text(reportBug) }
            }
            TextButton(onClick = {
                org.dlang.liveimap.ui.openWebPage(context, PRIVACY_URL)
            }) { Text(S("Privacy policy")) }
            TextButton(onClick = {
                scope.launch {
                    try {
                        val id = try {
                            store.chosenAccountId()
                        } catch (_: org.dlang.liveimap.accounts.NoAccountSelected) {
                            null
                        }
                        val device = listOf(Build.MANUFACTURER, Build.MODEL)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        val report = TrafficLog.debugReport(
                            versionName = BuildConfig.VERSION_NAME,
                            versionCode = BuildConfig.VERSION_CODE,
                            androidVersion = Build.VERSION.RELEASE,
                            device = device,
                            settings = SettingsHub.uiNow(),
                            traffic = if (id != null) trafficText(appContext.cacheDir, id) else "",
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        val chooser = Intent.createChooser(send, S(context, "Share debug report"))
                        if (!org.dlang.liveimap.ui.startExternal(context, chooser)) {
                            Toast.makeText(context, S(context, "No email app found"), Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: org.dlang.liveimap.accounts.NoAccountSelected) {
                    }
                }
            }) { Text(copyReport) }
            Text(
                text = S("How to support the developer"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(S("This build cannot take a tip yet."))
            TextButton(onClick = {}, enabled = false) {
                Text(S("Tip with Google Play"))
            }
        }
    }
}

private fun readHelpAssets(assets: AssetManager): Pair<String, String>? {
    return try {
        val started = assets.open("help/getting-started.txt").bufferedReader().use { it.readText() }
        val reporting = assets.open("help/reporting-a-bug.txt").bufferedReader().use { it.readText() }
        Pair(started, reporting)
    } catch (e: IOException) {
        null
    }
}
