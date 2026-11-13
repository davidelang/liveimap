package org.dlang.liveimap.oauth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.oauthClientSecretSlot
import org.dlang.liveimap.accounts.oauthPendingSlot
import org.dlang.liveimap.accounts.oauthTokenSlot
import org.dlang.liveimap.session.dropMailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.ui.openWebPage
import org.dlang.liveimap.ui.startExternal

object OauthBridge {
    @Volatile
    var transport: OauthTransport = PlatformOauthTransport

    private fun store(context: Context) = AccountStore.shared(context)

    private fun readState(context: Context, accountId: String): OauthTokenState? = runBlocking {
        store(context).oauthPlain(accountId, oauthTokenSlot)?.let(::decodeTokenState)
    }

    private fun writeState(context: Context, accountId: String, state: OauthTokenState?) {
        runBlocking {
            store(context).setOauthPlain(accountId, oauthTokenSlot, state?.let(::encodeTokenState))
        }
    }

    private fun secret(context: Context, accountId: String): String? = runBlocking {
        store(context).oauthPlain(accountId, oauthClientSecretSlot)
    }

    private fun arm(context: Context) {
        val dir = File(context.applicationContext.filesDir, "oauth-refresh")
        if (!dir.isDirectory) dir.mkdirs()
        OauthRefreshLocks.directory = dir
    }

    fun isSignedIn(context: Context, accountId: String, now: Long = System.currentTimeMillis()): Boolean =
        readState(context, accountId)?.signedIn(now) == true

    fun accessOrThrow(context: Context, accountId: String, settings: Settings): String {
        arm(context)
        val now = System.currentTimeMillis()
        return withRefreshLock(accountId) {
            val secret = secret(context, accountId)
            var current = readState(context, accountId) ?: throw OauthFailure("Sign in again")
            if (!current.signedIn(now)) throw OauthFailure("Sign in again")
            if (current.needsProactiveRefresh(now)) {
                current = refreshLocked(current, settings, secret, now, transport)
                writeState(context, accountId, current)
                if (current.access.isEmpty() || current.accessUnusable) throw OauthFailure("Sign in again")
            }
            if (current.access.isEmpty() || current.accessUnusable) throw OauthFailure("Sign in again")
            current.access
        }
    }

    fun onAuthRejected(context: Context, accountId: String, settings: Settings, rejectedAccess: String? = null): String? {
        arm(context)
        val now = System.currentTimeMillis()
        val next = onAuthFailure(
            accountId,
            null,
            settings,
            secret(context, accountId),
            now,
            transport,
            rejectedAccess,
            reread = { readState(context, accountId) },
        ) { updated -> writeState(context, accountId, updated) }
        if (next.access.isEmpty() || next.accessUnusable) return null
        return next.access
    }

    fun noteAuthSuccess(context: Context, accountId: String) {
        arm(context)
        withRefreshLock(accountId) {
            val current = readState(context, accountId) ?: return@withRefreshLock
            if (current.streak == 0 && !current.accessUnusable) return@withRefreshLock
            writeState(context, accountId, onAuthSuccess(current))
        }
    }

    fun discover(settings: Settings): Settings {
        val found = discoverOauth(settings.oauthIssuer, transport)
        return settings.copy(
            oauthAuthorizationEndpoint = found.authorization,
            oauthTokenEndpoint = found.token,
            oauthRevocationEndpoint = found.revocation.orEmpty(),
        )
    }

    fun beginBrowserSignIn(context: Context, accountId: String, settings: Settings): String =
        prepareBrowserSignIn(
            settings,
            writePending = { pending ->
                runBlocking {
                    store(context).setOauthPlain(accountId, oauthPendingSlot, encodePending(pending))
                }
            },
            readToken = { readState(context, accountId) },
        )

    suspend fun signOut(context: Context, accountId: String, settings: Settings) {
        arm(context)
        val accounts = store(context)
        withRefreshLock(accountId) {
            val state = readState(context, accountId)
            val secret = secret(context, accountId)
            signOutOrder(
                state,
                settings,
                secret,
                transport,
                dropSession = { runBlocking { dropMailSession(accountId) } },
                clearToken = { writeState(context, accountId, null) },
                clearPending = {
                    runBlocking { accounts.setOauthPlain(accountId, oauthPendingSlot, null) }
                },
            )
        }
    }

    fun completeBrowserRedirect(context: Context, query: Map<String, String>): RedirectResult = runBlocking {
        val accounts = store(context)
        val ids = accounts.listAccounts().map { it.id }
        val settings = HashMap<String, Settings>()
        for (id in ids) {
            settings[id] = runCatching { DataStoreSettingsStore(context, id).load() }.getOrElse { Settings() }
        }
        completeRedirect(
            accounts = ids,
            query = query,
            pendingOf = { id ->
                runBlocking { accounts.oauthPlain(id, oauthPendingSlot) }?.let(::decodePending)
            },
            tokenOf = { id ->
                runBlocking { accounts.oauthPlain(id, oauthTokenSlot) }?.let(::decodeTokenState)
            },
            writePending = { id, pending ->
                runBlocking { accounts.setOauthPlain(id, oauthPendingSlot, pending?.let(::encodePending)) }
            },
            writeToken = { id, state ->
                runBlocking { accounts.setOauthPlain(id, oauthTokenSlot, state?.let(::encodeTokenState)) }
            },
            settingsOf = { id -> settings[id] ?: Settings() },
            secretOf = { id -> runBlocking { accounts.oauthPlain(id, oauthClientSecretSlot) } },
            now = System.currentTimeMillis(),
            transport = transport,
        )
    }
}

fun customTabsPackage(context: Context): String? =
    runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()

fun openOauthBrowser(context: Context, url: String) {
    val pkg = customTabsPackage(context)
    if (browserOpenKind(pkg) == "openWebPage") {
        openWebPage(context, url)
        return
    }
    val tabs = CustomTabsIntent.Builder().build()
    tabs.intent.setPackage(pkg)
    if (!startExternal(context, tabs.intent.setData(Uri.parse(url)))) openWebPage(context, url)
}
