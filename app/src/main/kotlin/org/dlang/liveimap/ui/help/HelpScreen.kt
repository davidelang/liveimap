package org.dlang.liveimap.ui.help

import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.R
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.ui.debug.DebugReportReview

const val HELP_ISSUES_URL = "https://github.com/davidelang/liveimap/issues"

@Composable
fun HelpScreen() {
    val context = LocalContext.current
    val unreadable = stringResource(R.string.help_unreadable)
    val gettingStartedTitle = stringResource(R.string.help_getting_started)
    val reportingTitle = stringResource(R.string.help_reporting)
    val reportBug = stringResource(R.string.help_report_bug)
    val copyReport = stringResource(R.string.help_copy_report)
    val appContext = context.applicationContext
    val assets = context.assets
    val store = remember { DataStoreSettingsStore(appContext) }
    val scope = rememberCoroutineScope()
    var gettingStarted by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    var reportLines by remember { mutableStateOf<List<String>?>(null) }
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
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HELP_ISSUES_URL)))
            }) { Text(reportBug) }
            TextButton(onClick = {
                scope.launch {
                    val settings = store.load()
                    val log = TrafficLog.install(File(appContext.cacheDir, "imap-traffic.log"))
                    val device = listOf(Build.MANUFACTURER, Build.MODEL)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    reportLines = log.debugReport(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        androidVersion = Build.VERSION.RELEASE,
                        device = device,
                        settings = settings,
                    ).lines()
                }
            }) { Text(copyReport) }
        }
    }
    val shownReport = reportLines
    if (shownReport != null) {
        DebugReportReview(
            lines = shownReport,
            onLines = { reportLines = it },
            onDismiss = { reportLines = null },
        )
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
