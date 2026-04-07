package com.claudeusage.widget

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * WebView로 claude.ai에 직접 로그인.
 * 로그인 성공 후:
 * 1. JavaScript로 /api/organizations를 호출하여 org ID 추출
 * 2. org ID + 전체 쿠키를 저장
 * → OkHttp가 아닌 WebView 컨텍스트에서 API를 호출하므로 쿠키 문제 없음
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
    private var loginCompleted = false
    private val handler = Handler(Looper.getMainLooper())

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

            addJavascriptInterface(JsBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.requestHeaders?.remove("X-Requested-With")
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (loginCompleted) return
                    if (url == null) return

                    // claude.ai에 있고, 로그인/인증 페이지가 아니면 org ID 탐색
                    val isMainPage = url.contains("claude.ai") &&
                        !url.contains("/login") &&
                        !url.contains("/verify") &&
                        !url.contains("/oauth") &&
                        !url.contains("/auth") &&
                        !url.contains("/signup")

                    if (isMainPage) {
                        // 페이지 로드 후 약간 대기 (세션 안정화)
                        handler.postDelayed({ fetchOrgIdViaJs(view) }, 2000)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean,
                    isUserGesture: Boolean, resultMsg: Message?
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

                override fun onCloseWindow(window: WebView?) { closePopup() }
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
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.requestHeaders?.remove("X-Requested-With")
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null) return
                    // OAuth 완료 후 claude.ai로 돌아오면 팝업 닫기
                    val isBackToClaude = url.contains("claude.ai") &&
                        !url.contains("/login") && !url.contains("/oauth")
                    if (isBackToClaude) {
                        handler.postDelayed({
                            closePopup()
                            mainWebView.loadUrl("https://claude.ai/")
                        }, 1000)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) { closePopup() }
            }
        }
    }

    /**
     * WebView 안에서 JavaScript로 /api/organizations를 fetch.
     * WebView의 쿠키가 자동으로 포함되므로 인증 문제 없음.
     */
    private fun fetchOrgIdViaJs(view: WebView?) {
        view?.evaluateJavascript("""
            (async function() {
                try {
                    const resp = await fetch('/api/organizations', {credentials: 'include'});
                    const data = await resp.json();

                    let orgId = null;
                    if (Array.isArray(data) && data.length > 0) {
                        orgId = data[0].uuid || data[0].id;
                    } else if (data && data.uuid) {
                        orgId = data.uuid;
                    }

                    // 쿠키도 같이 보냄
                    const cookies = document.cookie;

                    AndroidBridge.onOrgFound(
                        orgId || '',
                        cookies || '',
                        JSON.stringify(data).substring(0, 500)
                    );
                } catch(e) {
                    AndroidBridge.onOrgFound('', '', 'fetch error: ' + e.message);
                }
            })();
        """.trimIndent(), null)
    }

    /** JavaScript에서 호출하는 브릿지 */
    inner class JsBridge {
        @JavascriptInterface
        fun onOrgFound(orgId: String, cookies: String, debug: String) {
            handler.post {
                if (loginCompleted) return@post

                val prefs = PreferenceManager.getDefaultSharedPreferences(this@LoginActivity)

                if (orgId.isNotEmpty()) {
                    // org ID를 직접 저장
                    prefs.edit()
                        .putString("org_id", orgId)
                        .putString("cookies", cookies)
                        .apply()

                    // WebView CookieManager에서도 전체 쿠키 저장
                    val allCookies = CookieManager.getInstance()
                        .getCookie("https://claude.ai") ?: cookies
                    prefs.edit()
                        .putString("session_key", allCookies)
                        .putBoolean("is_full_cookie", true)
                        .apply()

                    loginCompleted = true
                    Toast.makeText(this@LoginActivity, "로그인 성공!", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_LOGGED_IN)
                    finish()
                } else {
                    // org ID를 못 찾음 - 디버그 표시
                    Toast.makeText(this@LoginActivity,
                        "org ID 탐색 중... $debug", Toast.LENGTH_LONG).show()

                    // 3초 후 재시도
                    handler.postDelayed({
                        if (!loginCompleted) fetchOrgIdViaJs(mainWebView)
                    }, 3000)
                }
            }
        }
    }

    private fun closePopup() {
        popupWebView?.let { container.removeView(it); it.destroy() }
        popupWebView = null
        mainWebView.visibility = View.VISIBLE
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (popupWebView != null) closePopup()
        else if (mainWebView.canGoBack()) mainWebView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        popupWebView?.destroy()
        mainWebView.destroy()
        super.onDestroy()
    }
}
