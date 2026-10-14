package org.dlang.liveimap.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.debug.acceptance.AcceptanceContext
import org.dlang.liveimap.debug.acceptance.AcceptanceReport
import org.dlang.liveimap.debug.acceptance.AcceptanceSuite
import org.dlang.liveimap.debug.acceptance.MatrixServer
import org.dlang.liveimap.debug.acceptance.acceptanceTests
import org.dlang.liveimap.debug.acceptance.acceptanceVariants
import org.dlang.liveimap.debug.acceptance.runMatrix
import org.dlang.liveimap.debug.acceptance.summaryLine
import org.dlang.liveimap.debug.acceptance.variantAccount
import org.dlang.liveimap.debug.acceptance.variantSession
import org.dlang.liveimap.debug.acceptance.writeReport
import org.dlang.liveimap.engine.LabTestHooks
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.TlsMode
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lab only: the acceptance matrix (every test x every capability/fallback variant) against one lab server.
 * -e accServer <label> -e labHost/labPort/labTls/labUser/labPass/labCa -e labRun <name> [-e accVariants plain,no SORT].
 * Writes cache/recordings/<run>.json/.txt (the report) for lab.py-style pulling.
 */
@RunWith(AndroidJUnit4::class)
class AcceptanceLabTest {
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String, d: String = "") = args.getString(k) ?: d

    @Test
    fun acceptanceMatrix(): Unit = runBlocking {
        val host = arg("labHost")
        assumeTrue("no labHost argument", host.isNotEmpty())
        LabTestHooks.set(host, arg("labPass"))
        val ca = arg("labCa")
        if (ca.isNotEmpty()) LabTestHooks.setLabCa(android.util.Base64.decode(ca, android.util.Base64.DEFAULT))
        val account = AccountSettings(imapHost = host, imapPort = arg("labPort").toInt(), username = arg("labUser"),
            tlsMode = TlsMode.valueOf(arg("labTls", "None")), email = arg("labUser") + "@lab.test")
        val wanted = arg("accVariants").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val variants = if (wanted.isEmpty()) acceptanceVariants else acceptanceVariants.filter { it.name in wanted }
        var current: MailSession? = null
        val server = MatrixServer(arg("accServer", host), open = { v ->
            current?.close()
            val raw = LibetpanMailSession()
            raw.setCertConfirmer { false } // only the lab CA is trusted
            raw.setPlaintextConfirmer { true } // lab plain ports, as LabSuiteTest
            current = raw
            val a = variantAccount(account, v)
            when (val r = raw.open(a)) {
                OpenResult.Connected -> AcceptanceContext(variantSession(raw, v), a) { raw.open(a) } to ""
                is OpenResult.Failed -> null to r.text
                is OpenResult.Rejected -> null to r.capabilities
            }
        }, close = { current?.close() })
        val rows = runMatrix(listOf(server), variants, acceptanceTests)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "recordings").apply { mkdirs() }
        val header = listOf("server" to server.label, "imap" to "$host:${arg("labPort")} ${arg("labTls", "None")}", "summary" to summaryLine(rows))
        writeReport(dir, AcceptanceReport(AcceptanceSuite.Full, rows, header), arg("labRun", "acc"))
        android.util.Log.i("AcceptanceLab", summaryLine(rows))
    }
}
