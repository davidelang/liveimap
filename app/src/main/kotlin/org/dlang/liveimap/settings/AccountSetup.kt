package org.dlang.liveimap.settings

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.engine.ImapServerAuthenticationRejected
import org.dlang.liveimap.engine.SmtpProbeMaterial
import org.dlang.liveimap.R
import org.dlang.liveimap.accounts.AccountCredentials
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.jmap.JmapOffer
import org.dlang.liveimap.jmap.JmapPlainHttp
import org.dlang.liveimap.jmap.jmapMailboxLevel
import org.dlang.liveimap.jmap.offerForAccount
import org.dlang.liveimap.jmap.pinnedJmapPost
import org.dlang.liveimap.jmap.platformJmapExchange
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.OpenResult
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
    /** The SMTP password is the IMAP password (the tick, or the same text). */
    val smtpUsesImapPassword: Boolean = false,
)

/** Server rejected the IMAP login. A connection failure is not this, and is still allowed to try SMTP. */
internal fun imapAuthenticationFailure(error: Throwable?): Boolean =
    error is ImapServerAuthenticationRejected

/**
 * Test account: real logins, not reachability. IMAP access logs in over IMAP; JMAP access (or sending over JMAP)
 * makes one authenticated JMAP call; SMTP sending logs in to SMTP and quits without sending anything.
 */
suspend fun testAccountLogins(settings: AccountSettings, probes: AccountProbes): List<String> {
    val lines = ArrayList<String>()
    var imapAuthFailed = false
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
    if (!settings.jmapFolders) {
        step("IMAP login") {
            try {
                probes.imapLogin()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (probes.smtpUsesImapPassword && imapAuthenticationFailure(error)) imapAuthFailed = true
                throw error
            }
            "logged in"
        }
    }
    if (!settings.emailSubmission) {
        if (settings.smtpHost.isBlank()) {
            lines += "FAIL sending: no SMTP server and sending over JMAP is off"
        } else if (imapAuthFailed) {
            lines += "FAIL SMTP login: not tried; IMAP rejected this password"
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
    session: MailSession? = null,
): AccountProbes {
    val credentials = AccountCredentials(accountId, candidate, password, smtpPassword)
    val live = session ?: SerialMailSession(LibetpanMailSession()).apply {
        setCertConfirmer { confirmMailCertificate(it) }
        setPlaintextConfirmer { confirmMailPlaintext() }
    }
    val closed = AtomicBoolean(false)
    suspend fun closeProbe() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable + Dispatchers.IO) { live.close() }
    }
    var opened = false
    var armed = false
    suspend fun ensureOpen() {
        if (!armed) {
            armed = true
            // The caller's completion closes too, including a cancel that leaves this function.
            coroutineContext[Job]?.invokeOnCompletion {
                if (closed.get()) return@invokeOnCompletion
                CoroutineScope(Dispatchers.IO).launch { closeProbe() }
            }
        }
        if (opened) return
        try {
            when (val result = live.openWith(credentials)) {
                OpenResult.Connected -> opened = true
                is OpenResult.AuthRejected -> throw ImapServerAuthenticationRejected(result.text)
                is OpenResult.Failed -> throw IllegalStateException(result.text)
                is OpenResult.Rejected -> throw IllegalStateException("server rejected: ${result.capabilities}")
            }
        } finally {
            // Cancel during openWith, and a failed login, both leave no probe connected.
            if (!opened) closeProbe()
        }
    }
    val smtpWillRun = !candidate.emailSubmission && candidate.smtpHost.isNotBlank()
    return AccountProbes(
        imapLogin = {
            try {
                ensureOpen()
            } finally {
                if (!smtpWillRun) closeProbe()
            }
        },
        smtpLogin = {
            LibetpanMailSession.smtpProbe = SmtpProbeMaterial(candidate, password, smtpPassword)
            try {
                live.smtpLoginCheck()
            } finally {
                LibetpanMailSession.smtpProbe = null
                closeProbe()
            }
        },
        jmapLogin = {
            withContext(Dispatchers.IO) {
                val probeKey = java.util.UUID.randomUUID().toString()
                try {
                    val policy = candidate.tlsPolicy()
                    val offer = offerForAccount(candidate, platformJmapExchange(candidate.jmapCertPin, policy), authorization = org.dlang.liveimap.jmap.jmapBasicAuthorization(candidate.username, password), accountKey = probeKey)
                    if (offer !is JmapOffer.Mail) {
                        throw IllegalStateException(
                            if (candidate.jmapFolders) "no JMAP service found" else "no JMAP service found at ${candidate.imapHost}",
                        )
                    }
                    jmapMailboxLevel(offer.session, null, candidate.username, password, candidate.jmapCertPin, pinnedJmapPost(candidate.jmapCertPin, policy), policy = policy, accountKey = probeKey)
                } finally {
                    JmapPlainHttp.drop(probeKey)
                }
            }
        },
        smtpUsesImapPassword = candidate.smtpSameAsImap || smtpPassword == password,
    )
}
