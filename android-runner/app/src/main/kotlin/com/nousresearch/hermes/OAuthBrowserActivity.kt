package com.nousresearch.hermes

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.security.SecureRandom

/**
 * OAuth in-app browser tab. Phase 5 stub.
 *
 * The desktop's `src/main/oauth-login.ts` opens a system browser
 * window for the provider's auth page, listens for a custom-scheme
 * redirect (`hermes://oauth-callback?code=...`), and writes the
 * resulting token to the gateway's `.env`. The mobile equivalent
 * uses an in-app Activity with a WebView (no system hand-off)
 * so the user never leaves the Hermes app.
 *
 * For Phase 5 v1 we keep this very small:
 *   1. Generate a CSRF state token, stash it in SharedPreferences.
 *   2. Open the provider's auth URL in a system browser via
 *      `Intent.ACTION_VIEW` (the renderer's settings screen
 *      already has the user pick the provider).
 *   3. The provider's redirect hits our `hermes://oauth-callback`
 *      scheme (declared in the manifest's intent-filter) and
 *      re-enters the app at MainActivity.
 *   4. MainActivity's onNewIntent() routes the URL to
 *      OAuthBrowserActivity.handleRedirect() which validates
 *      the state, writes the code to hermes' auth file, and
 *      finishes.
 *
 * Why not Chrome Custom Tabs: the dependency is ~600KB and
 * requires androidx.browser:browser. For Phase 5 v1 a system
 * browser hand-off is acceptable (still in-app for the user —
 * the browser is a separate task that comes back via the
 * manifest filter). Phase 6 can swap in Custom Tabs if the
 * polish matters.
 */
class OAuthBrowserActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_AUTH_URL)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }
        val state = generateState()
        val prefs = getSharedPreferences("hermes_oauth", MODE_PRIVATE)
        prefs.edit().putString("pending_state", state).apply()
        // Append state to the URL. The provider's callback URL is
        // configured to be `hermes://oauth-callback` and the
        // provider echoes state back unchanged; we verify on
        // handleRedirect.
        val sep = if (url.contains("?")) "&" else "?"
        val finalUrl = "$url${sep}state=$state"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)))
        } catch (e: Exception) {
            Log.e(TAG, "failed to open auth URL: ${e.message}")
            finish()
        }
    }

    /**
     * Called by MainActivity (or the host) when the
     * `hermes://oauth-callback` intent fires. Validates the state
     * token, writes the code to the auth file, and finishes.
     */
    fun handleRedirect(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val prefs = getSharedPreferences("hermes_oauth", MODE_PRIVATE)
        val expectedState = prefs.getString("pending_state", null)
        prefs.edit().remove("pending_state").apply()
        if (error != null) {
            Log.w(TAG, "OAuth provider returned error: $error")
            finish()
            return
        }
        if (code == null || state == null || state != expectedState) {
            Log.w(TAG, "OAuth state mismatch or missing code")
            finish()
            return
        }
        // Persist the code to auth.json so the gateway can
        // exchange it for an access token on next start. The
        // desktop does the same in `src/main/oauth-login.ts`.
        val authFile = java.io.File(filesDir, "auth.json")
        authFile.writeText("""{"code": "$code", "received_at": ${System.currentTimeMillis()}}""")
        Log.i(TAG, "OAuth code written to auth.json")
        finish()
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val EXTRA_AUTH_URL = "auth_url"
        private const val TAG = "OAuthBrowser"
    }
}
