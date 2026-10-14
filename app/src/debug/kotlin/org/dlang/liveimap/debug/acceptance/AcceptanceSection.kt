package org.dlang.liveimap.debug.acceptance

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.R
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.confirmMailCertificate
import org.dlang.liveimap.session.confirmMailPlaintext
import org.dlang.liveimap.settings.DataStoreSettingsStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Account setup "Test this server": runs a suite against the open account on its own connection. */
@Composable
fun AcceptanceSection() {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var lastFile by remember { mutableStateOf("") }
    fun start(suite: AcceptanceSuite) {
        if (running) return
        running = true
        line = ""
        scope.launch {
            try {
                val id = store.chosenAccountId() ?: throw IllegalStateException("no account")
                val credentials = store.accountById(id) ?: throw IllegalStateException("no account")
                val account = credentials.settings
                val session = org.dlang.liveimap.session.SerialMailSession(LibetpanMailSession())
                session.setCertConfirmer(::confirmMailCertificate)
                session.setPlaintextConfirmer(::confirmMailPlaintext)
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val selected = selectCases(deviceAcceptanceTests.map { it.case }, suite, deviceAcceptanceTests.flatMap { it.case.plans }, 3, "")
                    .map { it.name }.toSet()
                val tests = deviceAcceptanceTests.filter { it.case.name in selected }
                var caps = ""
                // F46: the session is closed whether or not the open succeeded.
                val rows = try {
                    val opened = session.openWith(credentials)
                    caps = session.capabilities.joinToString(" ")
                    if (opened == OpenResult.Connected) {
                        runAcceptance(AcceptanceContext(session, account) { session.openWith(credentials) }, tests, readOnly = false,
                            // Lab hosts the person running it supplied (adb: run-as ... files/acceptance-write-hosts); none: read-only.
                            writeHosts = java.io.File(appContext.filesDir, "acceptance-write-hosts").takeIf { it.exists() }?.readText()?.let(::parseWriteHosts).orEmpty(),
                            progress = { done, total, name -> line = "$done/$total $name" })
                    } else {
                        val why = (opened as? OpenResult.Failed)?.text ?: (opened as? OpenResult.Rejected)?.capabilities ?: "open failed"
                        listOf(ReportRow("login", "tls-auth", listOf(RunnerPlan), "fail", why))
                    }
                } finally {
                    caps = session.capabilities.joinToString(" ").ifEmpty { caps }
                    session.close()
                }
                val header = listOf(
                    "build" to BuildConfig.VERSION_NAME,
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                    "imap" to "${account.imapHost}:${account.imapPort} ${account.tlsMode}",
                    "smtp" to "${account.smtpHost.ifBlank { account.imapHost }}:${account.smtpPort}",
                    "user" to account.username,
                    "lab user" to allowUser(account.username).toString(),
                    "capability" to caps,
                    "summary" to summaryLine(rows),
                )
                // F46: app-private storage (external files are readable by storage-permission apps on API 26-28).
                val dir = File(appContext.filesDir, "acceptance")
                withContext(Dispatchers.IO) { writeReport(dir, AcceptanceReport(suite, rows, header), stamp) }
                line = summaryLine(rows)
                lastFile = "acceptance-$stamp-${suite.name}.txt"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                line = error.message ?: error.toString()
            } finally {
                running = false
            }
        }
    }
    Column {
        Text(stringResource(if (running) R.string.acceptance_running else R.string.acceptance_test_this_server))
        Row {
            TextButton(onClick = { start(AcceptanceSuite.Full) }, enabled = !running) { Text(stringResource(R.string.acceptance_full)) }
            TextButton(onClick = { start(AcceptanceSuite.ThisTurn) }, enabled = !running) { Text(stringResource(R.string.acceptance_this_turn)) }
            TextButton(onClick = { start(AcceptanceSuite.LastN) }, enabled = !running) { Text(stringResource(R.string.acceptance_last_n)) }
        }
        if (line.isNotEmpty()) Text(line, fontFamily = FontFamily.Monospace)
        if (lastFile.isNotEmpty()) Text(lastFile, fontFamily = FontFamily.Monospace)
    }
}
