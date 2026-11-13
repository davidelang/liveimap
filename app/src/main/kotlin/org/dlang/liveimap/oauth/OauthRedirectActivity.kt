package org.dlang.liveimap.oauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Authorization-code redirect. Exported, singleTask, and in the default process so every window sees the slots. */
class OauthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accept(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent?) {
        val data = intent?.data
        if (data == null || data.scheme != "com.davidlang.liveimap" || data.path != "/oauth2redirect") {
            finish()
            return
        }
        val query = LinkedHashMap<String, String>()
        for (name in data.queryParameterNames) query[name] = data.getQueryParameter(name).orEmpty()
        val result = try {
            OauthBridge.completeBrowserRedirect(this, query)
        } catch (error: Exception) {
            RedirectResult(null, wroteToken = false, clearedPending = false, error = error.message ?: "browser sign-in: failed")
        }
        val message = result.error
        if (!message.isNullOrBlank()) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
