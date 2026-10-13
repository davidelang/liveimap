package org.dlang.liveimap.accounts

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.dlang.liveimap.settings.ExpandedFoldersScreen
import org.dlang.liveimap.settings.FolderStartsScreen
import org.dlang.liveimap.settings.FolderViewsScreen
import org.dlang.liveimap.settings.SettingsGroup
import org.dlang.liveimap.settings.SettingsGroupList
import org.dlang.liveimap.settings.SettingsGroupScreen
import org.dlang.liveimap.ui.contacts.ContactCopyScreen

class AuthenticatorService : Service() {
    private val authenticator by lazy { LiveImapAuthenticator(this) }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}

private class LiveImapAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {
    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle {
        return settingsIntent(response, null)
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle {
        // A second add opens settings. It does not create another account.
        return settingsIntent(response, options)
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = settingsIntent(response, options)

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle {
        return Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }

    override fun getAccountRemovalAllowed(
        response: AccountAuthenticatorResponse?,
        account: Account?,
    ): Bundle {
        if (account != null && account.type == liveimapAccountType) {
            deleteAccountArtifacts(context, account)
        }
        return Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }
    }

    /**
     * F03 (David, 2026-10-12): the setup intent carries a one-time token only when AccountManager says the request
     * came from LiveIMAP itself or from a system app (Settings). KEY_CALLER_UID is set by the system, not by the
     * caller. A third-party addAccount gets an intent without a token, and the activity refuses it.
     */
    private fun settingsIntent(response: AccountAuthenticatorResponse?, options: Bundle?): Bundle {
        val intent = Intent(context, AccountSetupActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        val callerUid = options?.getInt(AccountManager.KEY_CALLER_UID, -1) ?: -1
        val system = callerUid >= 0 && context.packageManager.getPackagesForUid(callerUid).orEmpty().any { name ->
            val info = runCatching { context.packageManager.getApplicationInfo(name, 0) }.getOrNull()
            info != null && setupCallerIsSystem(info.flags, info.uid)
        }
        if (setupTokenAllowed(callerUid, android.os.Process.myUid(), system)) {
            intent.putExtra(SetupTokens.EXTRA, SetupTokens.issue())
        }
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    private fun unsupported(): Bundle {
        return Bundle().apply {
            putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
            putString(AccountManager.KEY_ERROR_MESSAGE, "unsupported")
        }
    }
}

/** F77: the account created during this setup, if any; an account that was already there is not reported. */
internal fun newSetupAccount(before: Set<String>, now: List<String>): String? = now.firstOrNull { it !in before }

class AccountSetupActivity : ComponentActivity() {
    private var authenticatorResponse: AccountAuthenticatorResponse? = null
    private var resultSent = false
    /** F77: accounts that already existed when this activity opened; they are never reported back. */
    private var namesAtStart: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Exported because Settings starts AccountManager's KEY_INTENT from its own process (exported=false
        // would break Settings > Add account). So only a system app or LiveIMAP itself may open it, and
        // overlays cannot click through to the settings toggles.
        val token = intent.getStringExtra(SetupTokens.EXTRA)
        // Recreated after a configuration change: it was verified when first opened (the token is spent).
        val verified = savedInstanceState != null || token?.let { SetupTokens.consume(it) } == true
        if (!setupCallerDecision(callingPackage, packageName, verified)) {
            resultSent = true
            super.finish()
            return
        }
        window.decorView.filterTouchesWhenObscured = true
        // F03 addendum: dialogs are separate windows the decorView flag does not cover; on API 31+ hide every
        // non-system overlay while this activity is shown, which protects its dialogs as well.
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        namesAtStart = AccountManager.get(this).getAccountsByType(liveimapAccountType).map { it.name }.toSet()
        authenticatorResponse = readResponse(intent)
        authenticatorResponse?.onRequestContinued()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AccountSetupContent()
                }
            }
        }
    }

    override fun finish() {
        sendResult()
        super.finish()
    }

    private fun sendResult() {
        if (resultSent) return
        resultSent = true
        val response = authenticatorResponse ?: return
        val existing = newSetupAccount(
            namesAtStart,
            AccountManager.get(this).getAccountsByType(liveimapAccountType).map { it.name },
        )?.let { name -> android.accounts.Account(name, liveimapAccountType) }
        if (existing == null) {
            response.onError(AccountManager.ERROR_CODE_CANCELED, "canceled")
            return
        }
        val result = Bundle()
        result.putString(AccountManager.KEY_ACCOUNT_NAME, existing.name)
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, existing.type)
        response.onResult(result)
    }

    private fun readResponse(source: Intent): AccountAuthenticatorResponse? {
        val key = AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE
        return if (Build.VERSION.SDK_INT >= 33) {
            source.getParcelableExtra(key, AccountAuthenticatorResponse::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(key)
        }
    }
}

private sealed interface SetupScreen {
    data object List : SetupScreen
    data class Group(val group: SettingsGroup) : SetupScreen
    data object Expanded : SetupScreen
    data object Views : SetupScreen
    data object Starts : SetupScreen
    data object Contacts : SetupScreen
}

@Composable
private fun AccountSetupContent() {
    var stack by remember { mutableStateOf(listOf<SetupScreen>(SetupScreen.List)) }
    BackHandler(enabled = stack.size > 1) {
        stack = stack.dropLast(1)
    }
    when (val screen = stack.last()) {
        SetupScreen.List -> SettingsGroupList { group ->
            stack = stack + SetupScreen.Group(group)
        }
        is SetupScreen.Group -> SettingsGroupScreen(
            group = screen.group,
            onOpenExpanded = { stack = stack + SetupScreen.Expanded },
            onOpenViews = { stack = stack + SetupScreen.Views },
            onOpenStarts = { stack = stack + SetupScreen.Starts },
            onCopyContacts = { stack = stack + SetupScreen.Contacts },
        )
        SetupScreen.Expanded -> ExpandedFoldersScreen()
        SetupScreen.Views -> FolderViewsScreen()
        SetupScreen.Starts -> FolderStartsScreen()
        SetupScreen.Contacts -> ContactCopyScreen()
    }
}

/** A Settings-style caller: a system app (FLAG_SYSTEM or updated system app) or the system uid. */
internal fun setupCallerIsSystem(flags: Int, uid: Int): Boolean =
    uid == android.os.Process.SYSTEM_UID ||
        (flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

/** F03: a token is issued only for a request from LiveIMAP itself or a system app (system-set caller uid). */
internal fun setupTokenAllowed(callerUid: Int, ownUid: Int, callerIsSystem: Boolean): Boolean =
    callerUid >= 0 && (callerUid == ownUid || callerIsSystem)

/**
 * F03 (David, 2026-10-12): setup opens only from our own app (in-app Settings: caller is our package) or from the
 * system AccountManager flow we verified (a valid one-time token from [SetupTokens]). The authenticator response
 * extra alone, or a system calling package without a token, is never enough.
 */
internal fun setupCallerDecision(caller: String?, ownPackage: String, verifiedToken: Boolean): Boolean =
    caller == ownPackage || verifiedToken

/** One-time, short-lived setup tokens, held only in this process. */
internal object SetupTokens {
    const val EXTRA = "org.dlang.liveimap.SETUP_TOKEN"
    private const val LIFE_MS = 10 * 60 * 1000L
    private val issued = HashMap<String, Long>()
    private val random = java.security.SecureRandom()
    internal var clock: () -> Long = { System.currentTimeMillis() }

    @Synchronized fun issue(): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = clock()
        issued.entries.removeAll { now - it.value > LIFE_MS }
        issued[token] = now
        return token
    }

    @Synchronized fun consume(token: String): Boolean {
        val at = issued.remove(token) ?: return false
        return clock() - at <= LIFE_MS
    }
}
