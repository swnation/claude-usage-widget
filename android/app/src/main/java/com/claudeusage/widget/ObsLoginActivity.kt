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
 * WebView로 오랑붕쌤 사이트에 Google 로그인.
 * 로그인 후 Drive에서 데이터 로드 → localStorage에 캐시 → 비용 스크래핑 가능.
 */
class ObsLoginActivity : AppCompatActivity() {

    companion object {
        const val OBS_URL = "https://swnation.github.io/OrangBoongSSem/"
        const val RESULT_LOGGED_IN = 200
    }

    private lateinit var container: FrameLayout
    private lateinit var mainWebView: WebView
    private lateinit var doneButton: Button
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
                    // 데이터 로드 완료 감지 (주기적 체크)
                    startDataCheck()
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

        doneButton = Button(this).apply {
            text = "✓ 연결 완료"
            setBackgroundColor(0xFF10a37f.toInt())
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
    }

    private var dataCheckCount = 0

    private fun startDataCheck() {
        dataCheckCount = 0
        checkForData()
    }

    private fun checkForData() {
        if (dataCheckCount > 30) return // 최대 30회 (60초)
        dataCheckCount++

        mainWebView.evaluateJavascript("""
            (function() {
                var hasData = false;
                for (var i = 0; i < localStorage.length; i++) {
                    if (localStorage.key(i).indexOf('om_usage_') === 0) {
                        hasData = true; break;
                    }
                }
                var autoLogin = localStorage.getItem('om_auto_login') === 'true';
                return JSON.stringify({ hasData: hasData, autoLogin: autoLogin });
            })();
        """.trimIndent()) { result ->
            try {
                val raw = result?.trim()?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"") ?: "{}"
                val json = com.google.gson.Gson().fromJson(raw, com.google.gson.JsonObject::class.java)
                val hasData = json?.get("hasData")?.asBoolean ?: false
                val autoLogin = json?.get("autoLogin")?.asBoolean ?: false

                if (hasData) {
                    doneButton.text = "✓ 데이터 확인됨 - 연결 완료"
                    doneButton.setBackgroundColor(0xFF10a37f.toInt())
                } else if (autoLogin) {
                    doneButton.text = "⏳ 데이터 로딩 중..."
                    handler.postDelayed({ checkForData() }, 2000)
                } else {
                    doneButton.text = "Google 로그인 후 눌러주세요"
                }
            } catch (_: Exception) {}
        }
    }

    private fun onDoneClicked() {
        mainWebView.evaluateJavascript("""
            (function() {
                var hasData = false;
                for (var i = 0; i < localStorage.length; i++) {
                    if (localStorage.key(i).indexOf('om_usage_') === 0) {
                        hasData = true; break;
                    }
                }
                return hasData;
            })();
        """.trimIndent()) { result ->
            val hasData = result?.trim() == "true"
            if (hasData) {
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean("obs_logged_in", true).apply()
                Toast.makeText(this, "오랑붕쌤 연결 성공!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_LOGGED_IN)
                finish()
            } else {
                Toast.makeText(this, "먼저 Google 로그인 후 데이터가 로드될 때까지 기다려주세요",
                    Toast.LENGTH_LONG).show()
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
