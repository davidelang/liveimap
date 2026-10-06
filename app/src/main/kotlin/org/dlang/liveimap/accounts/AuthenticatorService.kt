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
        return settingsIntent(response)
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle {
        // A second add opens settings. It does not create another account.
        return settingsIntent(response)
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
    ): Bundle = settingsIntent(response)

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

    private fun settingsIntent(response: AccountAuthenticatorResponse?): Bundle {
        val intent = Intent(context, AccountSetupActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    private fun unsupported(): Bundle {
        return Bundle().apply {
            putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
            putString(AccountManager.KEY_ERROR_MESSAGE, "unsupported")
        }
    }
}

class AccountSetupActivity : ComponentActivity() {
    private var authenticatorResponse: AccountAuthenticatorResponse? = null
    private var resultSent = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
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
        val existing = AccountManager.get(this).getAccountsByType(liveimapAccountType).firstOrNull()
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
