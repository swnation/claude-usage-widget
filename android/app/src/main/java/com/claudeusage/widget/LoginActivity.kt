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
 * WebView로 claude.ai에 로그인 → 사용량 페이지 스크래핑.
 * API를 추측하지 않고, 사용량 페이지의 DOM에서 직접 데이터를 추출.
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

        mainWebView = createWebView()
        container.addView(mainWebView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

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

    private fun onDoneClicked() {
        doneButton.isEnabled = false
        doneButton.text = "쿠키 저장 중..."

        // 전체 쿠키 저장
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
        if (cookies.isNullOrEmpty()) {
            Toast.makeText(this, "쿠키가 없습니다. 먼저 로그인하세요.", Toast.LENGTH_SHORT).show()
            resetButton()
            return
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("session_key", cookies)
            .apply()

        doneButton.text = "API 탐색 중..."

        // JS로 여러 API를 호출하고, 모든 결과를 반환
        mainWebView.evaluateJavascript("""
            (async function() {
                const results = {};

                // 1. 현재 페이지의 모든 XHR/fetch URL 수집은 불가하므로
                //    직접 여러 엔드포인트를 시도

                // 시도할 엔드포인트 목록
                const endpoints = [
                    '/api/organizations',
                    '/api/auth/session',
                    '/api/account',
                    '/api/settings',
                    '/api/me',
                ];

                for (const ep of endpoints) {
                    try {
                        const r = await fetch(ep, {credentials:'include'});
                        if (r.ok) {
                            const t = await r.text();
                            results[ep] = t.substring(0, 300);
                        } else {
                            results[ep] = 'HTTP ' + r.status;
                        }
                    } catch(e) {
                        results[ep] = 'ERR: ' + e.message;
                    }
                }

                // 페이지 소스에서 UUID 패턴 찾기
                const bodyText = document.body?.innerText || '';
                const allText = document.documentElement?.innerHTML || '';
                const uuidMatch = allText.match(/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/);
                results['page_uuid'] = uuidMatch ? uuidMatch[0] : 'none';

                // URL 체크
                results['url'] = window.location.href;

                // document.cookie
                results['doc_cookies'] = (document.cookie || '').substring(0, 200);

                return JSON.stringify(results);
            })();
        """.trimIndent()) { jsResult ->
            handleApiProbeResult(jsResult, cookies)
        }
    }

    private fun handleApiProbeResult(jsResult: String?, cookies: String) {
        val raw = jsResult?.trim()
            ?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\/", "/")
            ?: "{}"

        try {
            val gson = com.google.gson.Gson()
            val results = gson.fromJson(raw, com.google.gson.JsonObject::class.java)

            // 결과에서 org ID 추출 시도
            var orgId: String? = null

            // /api/organizations 응답에서
            val orgsResp = results?.get("/api/organizations")?.asString ?: ""
            if (orgsResp.isNotEmpty() && !orgsResp.startsWith("HTTP") && !orgsResp.startsWith("ERR")) {
                val uuidMatch = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    .find(orgsResp)
                if (uuidMatch != null) orgId = uuidMatch.value
            }

            // 다른 응답에서도 시도
            if (orgId == null) {
                for (key in (results?.keySet() ?: emptySet())) {
                    val value = results?.get(key)?.asString ?: ""
                    if (value.startsWith("HTTP") || value.startsWith("ERR") || value == "none") continue
                    val uuidMatch = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                        .find(value)
                    if (uuidMatch != null) {
                        orgId = uuidMatch.value
                        break
                    }
                }
            }

            // 페이지에서 찾은 UUID
            if (orgId == null) {
                val pageUuid = results?.get("page_uuid")?.asString
                if (pageUuid != null && pageUuid != "none") orgId = pageUuid
            }

            if (orgId != null) {
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString("org_id", orgId)
                    .apply()

                loginCompleted = true
                Toast.makeText(this, "로그인 성공!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_LOGGED_IN)
                finish()
            } else {
                // 디버그: 모든 응답 표시
                val debugInfo = buildString {
                    results?.keySet()?.forEach { key ->
                        val v = results.get(key)?.asString ?: ""
                        append("$key: ${v.take(60)}\n")
                    }
                }
                Toast.makeText(this, "org ID 못 찾음.\n$debugInfo", Toast.LENGTH_LONG).show()
                resetButton()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "오류: ${e.message}\n원본: ${raw.take(100)}", Toast.LENGTH_LONG).show()
            resetButton()
        }
    }

    private fun resetButton() {
        doneButton.isEnabled = true
        doneButton.text = "✓ 다시 시도"
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
