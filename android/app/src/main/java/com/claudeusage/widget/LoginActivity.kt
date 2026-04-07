package com.claudeusage.widget

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * WebView로 claude.ai에 로그인.
 * 로그인 후 "로그인 완료" 버튼을 누르면 쿠키 + org ID를 자동 저장.
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
    private lateinit var doneButton: Button
    private var popupWebView: WebView? = null
    private var loginCompleted = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        setContentView(container)

        // WebView
        mainWebView = createWebView()
        container.addView(mainWebView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // "로그인 완료" 버튼 (WebView 위에 플로팅)
        doneButton = Button(this).apply {
            text = "✓ 로그인 완료"
            setBackgroundColor(0xFFc084fc.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(48, 24, 48, 24)
            elevation = 8f
            setOnClickListener { onDoneClicked() }
        }
        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 80
        }
        container.addView(doneButton, btnParams)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(mainWebView, true)
        }

        mainWebView.loadUrl(CLAUDE_URL)
    }

    /**
     * "로그인 완료" 버튼 클릭 시:
     * 1. CookieManager에서 전체 쿠키 저장
     * 2. JS로 /api/organizations 호출하여 org ID 추출
     */
    private fun onDoneClicked() {
        doneButton.isEnabled = false
        doneButton.text = "확인 중..."

        // 전체 쿠키 저장
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
        if (cookies.isNullOrEmpty()) {
            Toast.makeText(this, "쿠키를 찾을 수 없습니다. 먼저 로그인하세요.", Toast.LENGTH_SHORT).show()
            doneButton.isEnabled = true
            doneButton.text = "✓ 로그인 완료"
            return
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("session_key", cookies)
            .apply()

        // JS로 org ID 가져오기
        mainWebView.evaluateJavascript("""
            (async function() {
                try {
                    const resp = await fetch('/api/organizations', {credentials: 'include'});
                    const text = await resp.text();
                    return text;
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })();
        """.trimIndent()) { result ->
            handleOrgResponse(result, cookies)
        }
    }

    private fun handleOrgResponse(jsResult: String?, cookies: String) {
        // evaluateJavascript returns a JSON-encoded string (with quotes)
        val raw = jsResult
            ?.trim()
            ?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: ""

        try {
            val gson = com.google.gson.Gson()
            val element = gson.fromJson(raw, com.google.gson.JsonElement::class.java)

            var orgId: String? = null

            if (element.isJsonArray) {
                val arr = element.asJsonArray
                if (arr.size() > 0) {
                    val first = arr[0].asJsonObject
                    orgId = first.get("uuid")?.asString ?: first.get("id")?.asString
                }
            } else if (element.isJsonObject) {
                val obj = element.asJsonObject
                orgId = obj.get("uuid")?.asString ?: obj.get("id")?.asString
            }

            if (orgId != null) {
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString("org_id", orgId)
                    .apply()

                loginCompleted = true
                Toast.makeText(this, "로그인 성공! (org: ${orgId.take(8)}...)", Toast.LENGTH_SHORT).show()
                setResult(RESULT_LOGGED_IN)
                finish()
            } else {
                Toast.makeText(this, "org ID를 찾을 수 없습니다: ${raw.take(100)}", Toast.LENGTH_LONG).show()
                doneButton.isEnabled = true
                doneButton.text = "✓ 로그인 완료"
            }
        } catch (e: Exception) {
            Toast.makeText(this, "파싱 오류: ${e.message}\n응답: ${raw.take(100)}", Toast.LENGTH_LONG).show()
            doneButton.isEnabled = true
            doneButton.text = "✓ 로그인 완료"
        }
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

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.requestHeaders?.remove("X-Requested-With")
                    return super.shouldInterceptRequest(view, request)
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
                    container.addView(popup, 0, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    mainWebView.visibility = View.GONE
                    doneButton.visibility = View.GONE
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
                    // OAuth 완료 후 claude.ai로 돌아오면 팝업 닫기
                    if (url != null && url.contains("claude.ai") &&
                        !url.contains("/login") && !url.contains("/oauth")) {
                        handler.postDelayed({ closePopup() }, 1500)
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

    private fun closePopup() {
        popupWebView?.let { container.removeView(it); it.destroy() }
        popupWebView = null
        mainWebView.visibility = View.VISIBLE
        doneButton.visibility = View.VISIBLE
        mainWebView.loadUrl("https://claude.ai/")
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
