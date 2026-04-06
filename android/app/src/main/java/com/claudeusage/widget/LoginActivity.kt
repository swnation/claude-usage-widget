package com.claudeusage.widget

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * WebView로 claude.ai에 직접 로그인.
 * 로그인 성공 후 sessionKey 쿠키를 자동 추출하여 저장.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val CLAUDE_URL = "https://claude.ai/login"
        const val RESULT_LOGGED_IN = 100
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        }
        setContentView(webView)

        // 쿠키 허용
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 페이지 로드 후 쿠키 확인
                checkForSessionKey(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // claude.ai 도메인 내에서만 이동
                if (url.contains("claude.ai")) {
                    return false
                }
                // Google OAuth 등 외부 로그인도 허용
                if (url.contains("accounts.google.com") ||
                    url.contains("appleid.apple.com") ||
                    url.contains("login") ||
                    url.contains("auth") ||
                    url.contains("oauth") ||
                    url.contains("sso")
                ) {
                    return false
                }
                return false
            }
        }

        webView.loadUrl(CLAUDE_URL)
    }

    private fun checkForSessionKey(url: String?) {
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai") ?: return

        // sessionKey 쿠키 찾기
        val sessionKey = cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("sessionKey=") }
            ?.substringAfter("sessionKey=")
            ?.trim()

        if (!sessionKey.isNullOrEmpty() && sessionKey.startsWith("sk-ant-sid")) {
            // 로그인 성공 - 세션 키 저장
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("session_key", sessionKey)
                .apply()

            Toast.makeText(this, "로그인 성공!", Toast.LENGTH_SHORT).show()

            setResult(RESULT_LOGGED_IN)
            finish()
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
