package app.nightwatch.store

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID

/** A stable native host: loading callbacks never recompose or replace the focused web editor. */
class RiotLoginActivity : ComponentActivity() {
    private lateinit var web: RiotWebView
    private var completed = false
    private var rendererAvailable = true
    private lateinit var state: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        state = savedInstanceState?.getString("oauth_state") ?: UUID.randomUUID().toString()
        val locale = if(intent.getStringExtra(LOCALE) == "tr-tr") "tr-tr" else "en-us"
        val tr = locale == "tr-tr"
        val url = SessionStore.authorizeUrl(locale, state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            if(Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
            if(Build.VERSION.SDK_INT >= 34) setAutoHandwritingEnabled(false)
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(58) }
        val close = Button(this).apply {
            text = "×"; textSize = 26f
            contentDescription = if(tr) "Girişi kapat" else "Close sign-in"
            setTextColor(Color.BLACK); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        bar.addView(close, LinearLayout.LayoutParams(dp(52), dp(52)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val host = TextView(this).apply {
            text = "auth.riotgames.com"; textSize = 13f
            setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        labels.addView(host)
        labels.addView(TextView(this).apply {
            text = if(tr) "Riot'un giriş sayfası · HTTPS" else "Riot sign-in page · HTTPS"
            textSize = 10f; setTextColor(Color.DKGRAY)
        })
        bar.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = if(tr) "Yardım" else "Help"; textSize = 11f; isAllCaps = false
            setTextColor(Color.DKGRAY); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showLoginHelp(tr) }
        }, LinearLayout.LayoutParams(dp(72), dp(48)))
        bar.addView(Button(this).apply {
            text = if(tr) "Yenile" else "Reload"; textSize = 11f; isAllCaps = false
            setTextColor(Color.DKGRAY); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { if(rendererAvailable) web.reload() else recreate() }
        }, LinearLayout.LayoutParams(dp(80), dp(48)))
        root.addView(bar)
        val loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(loading, LinearLayout.LayoutParams(-1, dp(2)))
        val error = TextView(this).apply {
            visibility = View.GONE; setTextColor(Color.rgb(150, 25, 35)); textSize = 13f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            text = if(tr) "Sayfa yüklenemedi. Bağlantını kontrol edip Yenile'ye dokun." else "Page failed to load. Check your connection and tap Reload."
        }
        root.addView(error)
        web = RiotWebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, value: Int) {
                loading.progress = value
                loading.visibility = if(value < 100) View.VISIBLE else View.INVISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            fun capture(target: String): Boolean {
                if(completed) return true
                val session = SessionStore.sessionFromRedirect(target, state) ?: return false
                completed = true
                web.stopLoading()
                CookieManager.getInstance().flush()
                setResult(RESULT_OK, Intent().putExtra(ACCESS, session.accessToken).putExtra(ID, session.idToken))
                finish()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if(request == null || !request.isForMainFrame) return false
                if(capture(request.url.toString())) return true
                val target = request.url
                val name = target.host.orEmpty()
                if(name == "accounts.google.com" || name.endsWith(".google.com") || name == "appleid.apple.com") {
                    showSocialLoginHelp(tr)
                    return true
                }
                val allowed = target.scheme == "https" && (name == "riotgames.com" || name.endsWith(".riotgames.com") || name == "playvalorant.com" || name.endsWith(".playvalorant.com"))
                if(!allowed) {
                    if(target.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, target)) }
                    return true
                }
                return false
            }
            override fun onPageStarted(view: WebView?, target: String?, favicon: android.graphics.Bitmap?) {
                host.text = target?.let { Uri.parse(it).host }.orEmpty()
                error.visibility = View.GONE
            }
            override fun onPageFinished(view: WebView?, target: String?) {
                target?.let(::capture)
                CookieManager.getInstance().flush()
            }
            override fun doUpdateVisitedHistory(view: WebView?, target: String?, isReload: Boolean) { target?.let(::capture) }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) {
                if(request?.isForMainFrame == true) error.visibility = View.VISIBLE
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                if(request?.isForMainFrame == true && (response?.statusCode ?: 0) >= 500) error.visibility = View.VISIBLE
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                rendererAvailable = false
                root.removeView(web)
                web.destroy()
                error.visibility = View.VISIBLE
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(rendererAvailable && web.canGoBack()) web.goBack() else finish()
            }
        })
        if(savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("oauth_state", state)
        if(rendererAvailable) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if(::web.isInitialized && rendererAvailable) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private fun showSocialLoginHelp(tr: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if(tr) "Google ile giriş" else "Google sign-in")
            .setMessage(if(tr)
                "Google, uygulama içindeki web penceresinden girişe izin vermiyor. Tarayıcıda açılan giriş de bu oturuma geri dönemez. Riot hesabında kullanıcı adı ve parola varsa bunlarla giriş yapabilirsin. Yoksa Riot hesap yönetiminden hesabını kontrol et; bu işlem hesabını uygulamadan çıkarmaz."
                else "Google does not allow sign-in inside an embedded web window. Opening it in a browser cannot return to this session. If your Riot account has a username and password, use those here. Otherwise, check your account at Riot Account Management. This does not sign you out of the app.")
            .setPositiveButton(if(tr) "Riot hesap yönetimi" else "Riot Account Management") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://account.riotgames.com/")))
            }
            .setNegativeButton(if(tr) "Geri dön" else "Back", null)
            .show()
    }
    private fun showLoginHelp(tr: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if(tr) "Giriş yardımı" else "Sign-in help")
            .setMessage(if(tr)
                "İki aşamalı doğrulama açıksa Riot'un bu sayfada istediği e-posta kodunu veya Riot Mobile onayını tamamla. Kod gelmiyorsa spam klasörünü kontrol et. Google ile giriş bu uygulama içi pencerede desteklenmiyor."
                else "If two-factor authentication is enabled, complete the email code or Riot Mobile approval requested on this Riot page. Check spam if the code does not arrive. Google sign-in is not supported in this embedded window.")
            .setPositiveButton(if(tr) "Tamam" else "OK", null)
            .setNeutralButton(if(tr) "Riot MFA yardımı" else "Riot MFA help") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://support.riotgames.com/en-us/riot/account/multi-factor-authentication-how-to-faq")))
            }
            .show()
    }
    companion object {
        const val LOCALE = "locale"
        const val ACCESS = "access_token"
        const val ID = "id_token"
    }
}

/** Never changes the page's HTML, CSS, field values, selection or password visibility. */
@SuppressLint("SetJavaScriptEnabled")
internal class RiotWebView(context: Context) : WebView(context) {
    init {
        setBackgroundColor(Color.WHITE)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        isFocusable = true
        isFocusableInTouchMode = true
        if(Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        if(Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = false
        if(Build.VERSION.SDK_INT >= 34) setAutoHandwritingEnabled(false)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgentString.replace("; wv", "")
        }
    }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        // Usernames are identifiers, not prose. Leave all selection/composition handling to Chromium.
        if(outAttrs.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            outAttrs.inputType = (outAttrs.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT.inv()) or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return connection
    }
}
