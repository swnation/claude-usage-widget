package com.claudeusage.widget

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * WebView-based Google OAuth2 implicit grant flow.
 * Opens Google's authorize endpoint, intercepts the redirect to localhost,
 * extracts access_token from the URL fragment, and saves it.
 */
class GoogleDriveAuthActivity : AppCompatActivity() {

    companion object {
        const val RESULT_LOGGED_IN = 200
        private const val REDIRECT_URI = "http://localhost"
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBackPressHandler()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val clientId = prefs.getString("google_oauth_client_id", null)

        if (clientId.isNullOrEmpty()) {
            promptForClientId()
            return
        }

        startOAuthFlow(clientId)
    }

    private fun promptForClientId() {
        val input = EditText(this).apply {
            hint = "Google OAuth Client ID"
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Google OAuth Client ID")
            .setMessage("Google Cloud Console에서 생성한 OAuth 2.0 Client ID를 입력하세요.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("확인") { _, _ ->
                val clientId = input.text.toString().trim()
                if (clientId.isEmpty()) {
                    Toast.makeText(this, "Client ID를 입력하세요", Toast.LENGTH_SHORT).show()
                    finish()
                    return@setPositiveButton
                }
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString("google_oauth_client_id", clientId).apply()
                startOAuthFlow(clientId)
            }
            .setNegativeButton("취소") { _, _ -> finish() }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startOAuthFlow(clientId: String) {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = LoginActivity.CHROME_UA

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith(REDIRECT_URI)) {
                        handleRedirect(url)
                        return true
                    }
                    return false
                }
            }
        }
        setContentView(webView)

        val authUrl = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("scope", SCOPE)
            .build()
            .toString()

        webView.loadUrl(authUrl)
    }

    private fun handleRedirect(url: String) {
        // Token is in the URL fragment: http://localhost#access_token=...&token_type=...
        val hashIdx = url.indexOf('#')
        val token = if (hashIdx >= 0) {
            url.substring(hashIdx + 1)
                .split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "access_token" }
                ?.get(1)
                ?.let { Uri.decode(it) }
        } else null

        if (!token.isNullOrEmpty()) {
            val fragment = token
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("google_drive_token", fragment)
                .putBoolean("google_drive_connected", true)
                .putLong("google_drive_token_time", System.currentTimeMillis())
                .apply()
            Toast.makeText(this, "Google Drive 연결 성공!", Toast.LENGTH_SHORT).show()
            setResult(RESULT_LOGGED_IN)
            finish()
        } else {
            Toast.makeText(this, "토큰 추출 실패", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
