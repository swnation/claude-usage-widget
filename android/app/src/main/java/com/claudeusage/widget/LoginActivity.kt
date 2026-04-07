package com.claudeusage.widget

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * WebView로 claude.ai에 직접 로그인.
 * X-Requested-With 헤더 제거로 Google OAuth WebView 차단 우회.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val CLAUDE_URL = "https://claude.ai/login"
        const val RESULT_LOGGED_IN = 100
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var container: FrameLayout
    private lateinit var mainWebView: WebView
    private var popupWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        setContentView(container)

        mainWebView = createWebView()
        container.addView(mainWebView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(mainWebView, true)
        }

        mainWebView.loadUrl(CLAUDE_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.userAgentString = CHROME_UA
            settings.databaseEnabled = true

            // Google이 WebView를 감지하는 X-Requested-With 헤더 제거
            WebView.setWebContentsDebuggingEnabled(false)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    // X-Requested-With 헤더를 제거하여 Google OAuth 차단 우회
                    request?.requestHeaders?.remove("X-Requested-With")
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    checkForSessionKey(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val popup = createPopupWebView()
                    popupWebView = popup
                    container.addView(popup, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    mainWebView.visibility = View.GONE

                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = popup
                    resultMsg?.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    closePopup()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopupWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = CHROME_UA
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.requestHeaders?.remove("X-Requested-With")
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.contains("claude.ai") && !url.contains("login")) {
                        closePopup()
                        mainWebView.reload()
                    }
                    checkForSessionKey(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    closePopup()
                }
            }
        }
    }

    private fun closePopup() {
        popupWebView?.let {
            container.removeView(it)
            it.destroy()
        }
        popupWebView = null
        mainWebView.visibility = View.VISIBLE
    }

    private var loginCompleted = false

    private fun checkForSessionKey(url: String?) {
        if (loginCompleted) return
        if (url == null) return

        val cookies = CookieManager.getInstance().getCookie("https://claude.ai") ?: return

        // sessionKey 쿠키 찾기 (여러 가능한 이름)
        val cookieMap = cookies.split(";").associate {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim()
            else parts[0].trim() to ""
        }

        val sessionKey = cookieMap["sessionKey"]
            ?: cookieMap["session_key"]
            ?: cookieMap["__Secure-next-auth.session-token"]

        // 방법 1: sessionKey 쿠키를 직접 찾은 경우
        if (!sessionKey.isNullOrEmpty() && sessionKey.length > 10) {
            saveAndFinish(sessionKey)
            return
        }

        // 방법 2: 로그인 후 메인 페이지에 도달한 경우 (쿠키 전체 저장)
        // claude.ai 메인 페이지에 왔다면 로그인 성공으로 간주
        if (url.contains("claude.ai") &&
            !url.contains("/login") &&
            !url.contains("/auth") &&
            !url.contains("/verify")) {

            // sk-ant로 시작하는 쿠키가 있는지 전체 검색
            val anySessionCookie = cookieMap.entries.find {
                it.value.startsWith("sk-ant") || it.value.length > 100
            }

            if (anySessionCookie != null) {
                saveAndFinish(anySessionCookie.value)
                return
            }

            // 쿠키에서 못 찾아도 메인 페이지에 왔으면 전체 쿠키 문자열 저장
            if (cookies.length > 50) {
                saveAndFinish(cookies, isFullCookie = true)
                return
            }
        }
    }

    private fun saveAndFinish(key: String, isFullCookie: Boolean = false) {
        loginCompleted = true
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (isFullCookie) {
            // 전체 쿠키 문자열 저장 (ClaudeWebClient가 Cookie 헤더로 사용)
            prefs.edit().putString("session_key", key)
                .putBoolean("is_full_cookie", true)
                .apply()
        } else {
            prefs.edit().putString("session_key", key)
                .putBoolean("is_full_cookie", false)
                .apply()
        }

        Toast.makeText(this, "로그인 성공!", Toast.LENGTH_SHORT).show()
        setResult(RESULT_LOGGED_IN)
        finish()
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (popupWebView != null) {
            closePopup()
        } else if (mainWebView.canGoBack()) {
            mainWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        popupWebView?.destroy()
        mainWebView.destroy()
        super.onDestroy()
    }
}
