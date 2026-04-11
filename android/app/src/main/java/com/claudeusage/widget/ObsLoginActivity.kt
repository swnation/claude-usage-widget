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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * WebView로 오랑붕쌤 사이트에 Google 로그인.
 * 로그인 후 S.token (Google OAuth 토큰)을 추출하여 저장.
 * 이후 Drive API 직접 호출로 비용 데이터 조회.
 */
class ObsLoginActivity : AppCompatActivity() {

    companion object {
        const val OBS_URL = "https://swnation.github.io/OrangBoongSSem/"
        const val RESULT_LOGGED_IN = 200
    }

    private lateinit var container: FrameLayout
    private lateinit var mainWebView: WebView
    private lateinit var doneButton: Button
    private lateinit var statusLabel: TextView
    private var popupWebView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)
        setContentView(container)

        mainWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = LoginActivity.CHROME_UA
            settings.databaseEnabled = true
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    startTokenCheck()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean,
                    isUserGesture: Boolean, resultMsg: Message?
                ): Boolean {
                    popupWebView = createPopupWebView()
                    container.addView(popupWebView, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    mainWebView.visibility = View.GONE
                    doneButton.visibility = View.GONE
                    statusLabel.visibility = View.GONE

                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = popupWebView
                    resultMsg?.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    closePopup()
                }
            }
        }

        container.addView(mainWebView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 상태 라벨
        statusLabel = TextView(this).apply {
            text = "Google 로그인 후 토큰을 가져옵니다"
            setTextColor(0xFFaaaaaa.toInt())
            textSize = 12f
            setPadding(32, 16, 32, 0)
            setBackgroundColor(0xDD1a1a2e.toInt())
        }
        container.addView(statusLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM; bottomMargin = 140 })

        doneButton = Button(this).apply {
            text = "Google 로그인 후 눌러주세요"
            setBackgroundColor(0xFF555555.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(48, 24, 48, 24)
            elevation = 8f
            isEnabled = false
            setOnClickListener { onDoneClicked() }
        }
        container.addView(doneButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 60
        })

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(mainWebView, true)
        }

        mainWebView.loadUrl(OBS_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopupWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = LoginActivity.CHROME_UA
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.contains("swnation.github.io") && !url.contains("accounts.google")) {
                        closePopup()
                        mainWebView.loadUrl(url)
                        return true
                    }
                    return false
                }
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
        doneButton.visibility = View.VISIBLE
        statusLabel.visibility = View.VISIBLE
    }

    private var tokenCheckCount = 0

    private fun startTokenCheck() {
        tokenCheckCount = 0
        checkForToken()
    }

    private fun checkForToken() {
        if (tokenCheckCount > 60) return
        tokenCheckCount++

        mainWebView.evaluateJavascript("""
            (function() {
                var token = (typeof S !== 'undefined' && S && S.token) ? S.token : null;
                return JSON.stringify({ token: token });
            })();
        """.trimIndent()) { result ->
            try {
                val raw = result?.trim()?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"") ?: "{}"
                val json = com.google.gson.Gson().fromJson(raw, com.google.gson.JsonObject::class.java)
                val token = json?.get("token")?.let {
                    if (it.isJsonNull) null else it.asString
                }

                if (token != null && token.isNotEmpty()) {
                    doneButton.text = "✓ 토큰 확인됨 - 연결 완료"
                    doneButton.setBackgroundColor(0xFF10a37f.toInt())
                    doneButton.isEnabled = true
                    statusLabel.text = "Google OAuth 토큰 획득 완료"
                    statusLabel.setTextColor(0xFF10a37f.toInt())
                } else {
                    statusLabel.text = "로그인 대기 중... (${tokenCheckCount})"
                    handler.postDelayed({ checkForToken() }, 2000)
                }
            } catch (_: Exception) {
                handler.postDelayed({ checkForToken() }, 2000)
            }
        }
    }

    private fun onDoneClicked() {
        mainWebView.evaluateJavascript("""
            (function() {
                var token = (typeof S !== 'undefined' && S && S.token) ? S.token : null;
                return JSON.stringify({ token: token });
            })();
        """.trimIndent()) { result ->
            try {
                val raw = result?.trim()?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"") ?: "{}"
                val json = com.google.gson.Gson().fromJson(raw, com.google.gson.JsonObject::class.java)
                val token = json?.get("token")?.let {
                    if (it.isJsonNull) null else it.asString
                }

                if (token != null && token.isNotEmpty()) {
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean("obs_logged_in", true)
                        .putString("google_oauth_token", token)
                        .putLong("google_oauth_time", System.currentTimeMillis())
                        .apply()
                    Toast.makeText(this, "오랑붕쌤 연결 성공!", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_LOGGED_IN)
                    finish()
                } else {
                    Toast.makeText(this, "먼저 Google 로그인을 완료하세요", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, "토큰 추출 실패", Toast.LENGTH_SHORT).show()
            }
        }
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
