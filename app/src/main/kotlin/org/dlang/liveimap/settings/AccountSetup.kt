package org.dlang.liveimap.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dlang.liveimap.R
import org.dlang.liveimap.accounts.AccountCredentials
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.jmap.JmapOffer
import org.dlang.liveimap.jmap.jmapMailboxLevel
import org.dlang.liveimap.jmap.offerForAccount
import org.dlang.liveimap.jmap.pinnedJmapPost
import org.dlang.liveimap.jmap.platformJmapExchange
import org.dlang.liveimap.session.SerialMailSession
import org.dlang.liveimap.session.SmtpLoginCheck
import org.dlang.liveimap.session.confirmMailCertificate
import org.dlang.liveimap.session.confirmMailPlaintext

/** David, 2026-10-12: every account must be able to send: over JMAP, or through an SMTP server. */
fun sendingProblem(settings: AccountSettings): Int? =
    if (!settings.emailSubmission && settings.smtpHost.isBlank()) R.string.settings_no_way_to_send else null

/** Choose IMAP or JMAP access. Choosing JMAP with no SMTP server turns on sending over JMAP, so mail can go out. */
fun withMailAccess(settings: AccountSettings, jmap: Boolean): AccountSettings {
    val next = settings.copy(jmapFolders = jmap)
    return if (jmap && next.smtpHost.isBlank()) next.copy(emailSubmission = true) else next
}

/** What Test account needs to do, as replaceable steps (unit tests pass fakes). Each throws on failure. */
class AccountProbes(
    val imapLogin: suspend () -> Unit,
    val smtpLogin: suspend () -> SmtpLoginCheck,
    val jmapLogin: suspend () -> Unit,
)

/**
 * Test account: real logins, not reachability. IMAP access logs in over IMAP; JMAP access (or sending over JMAP)
 * makes one authenticated JMAP call; SMTP sending logs in to SMTP and quits without sending anything.
 */
suspend fun testAccountLogins(settings: AccountSettings, probes: AccountProbes): List<String> {
    val lines = ArrayList<String>()
    suspend fun step(name: String, block: suspend () -> String) {
        lines += try {
            "ok   $name: ${block()}"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            "FAIL $name: ${error.message ?: error.javaClass.simpleName}"
        }
    }
    if (settings.jmapFolders || settings.emailSubmission) step("JMAP login") { probes.jmapLogin(); "logged in" }
    if (!settings.jmapFolders) step("IMAP login") { probes.imapLogin(); "logged in" }
    if (!settings.emailSubmission) {
        if (settings.smtpHost.isBlank()) {
            lines += "FAIL sending: no SMTP server and sending over JMAP is off"
        } else {
            step("SMTP login") {
                when (probes.smtpLogin()) {
                    SmtpLoginCheck.LoggedIn -> "logged in"
                    SmtpLoginCheck.NoAuthOffered -> "connected; the server asks for no login"
                    SmtpLoginCheck.NoPassword -> throw IllegalStateException("no SMTP password set (or tick Same as IMAP)")
                }
            }
        }
    }
    return lines
}

/** The real probes: a throwaway connection with the candidate settings (the account's own session is untouched). */
internal fun testAccountProbes(
    accountId: String,
    candidate: AccountSettings,
    password: String,
    smtpPassword: String,
): AccountProbes {
    val credentials = AccountCredentials(accountId, candidate, password, smtpPassword)
    val session = SerialMailSession(LibetpanMailSession()).apply {
        setCertConfirmer { confirmMailCertificate(it) }
        setPlaintextConfirmer { confirmMailPlaintext() }
    }
    var opened = false
    suspend fun ensureOpen() {
        if (!opened) {
            session.openWith(credentials)
            opened = true
        }
    }
    return AccountProbes(
        imapLogin = {
            try { ensureOpen() } finally { if (candidate.emailSubmission) session.close() }
        },
        smtpLogin = {
            try {
                ensureOpen()
                session.smtpLoginCheck()
            } finally {
                session.close()
            }
        },
        jmapLogin = {
            withContext(Dispatchers.IO) {
                val offer = offerForAccount(candidate, ::platformJmapExchange)
                if (offer !is JmapOffer.Mail) throw IllegalStateException("no JMAP service found at ${candidate.imapHost}")
                jmapMailboxLevel(offer.session, null, candidate.username, password, candidate.certPin, pinnedJmapPost(candidate.certPin))
            }
        },
    )
}
