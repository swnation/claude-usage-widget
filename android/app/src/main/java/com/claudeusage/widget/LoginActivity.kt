package com.claudeusage.widget

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * WebView로 claude.ai에 로그인.
 * 팝업 없이 단일 WebView에서 처리 (Google OAuth 호환).
 * 로그인 후 "로그인 완료" 버튼 → 쿠키만 저장하고 종료.
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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var googleRedirectRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        setContentView(container)

        mainWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = CHROME_UA
            settings.databaseEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false

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

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Google OAuth 후 "잠시만 기다려주세요"에서 멈추면
                    // 5초 후 자동으로 claude.ai로 이동
                    googleRedirectRunnable?.let { handler.removeCallbacks(it) }
                    if (url != null && (url.contains("accounts.google.com") ||
                        url.contains("accounts.youtube.com"))) {
                        googleRedirectRunnable = Runnable {
                            view?.loadUrl("https://claude.ai/")
                        }
                        handler.postDelayed(googleRedirectRunnable!!, 5000)
                    }
                }
            }
        }

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
        container.addView(doneButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 80
        })

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(mainWebView, true)
        }

        mainWebView.loadUrl(CLAUDE_URL)
    }

    private fun onDoneClicked() {
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
        if (cookies.isNullOrEmpty() || cookies.length < 20) {
            Toast.makeText(this, "먼저 로그인을 완료하세요", Toast.LENGTH_SHORT).show()
            return
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("session_key", cookies)
            .putBoolean("logged_in", true)
            .apply()

        Toast.makeText(this, "로그인 성공!", Toast.LENGTH_SHORT).show()
        setResult(RESULT_LOGGED_IN)
        finish()
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (mainWebView.canGoBack()) mainWebView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        googleRedirectRunnable?.let { handler.removeCallbacks(it) }
        mainWebView.destroy()
        super.onDestroy()
    }
}
