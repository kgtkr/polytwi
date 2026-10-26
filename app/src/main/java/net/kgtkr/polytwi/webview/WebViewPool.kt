package net.kgtkr.polytwi.webview

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kgtkr.polytwi.account.AccountManager
import net.kgtkr.polytwi.injector.ControlPanelInjector
import net.kgtkr.polytwi.injector.CpftConfigBridge
import net.kgtkr.polytwi.injector.MediaBridge
import net.kgtkr.polytwi.injector.PushJsBridge
import net.kgtkr.polytwi.injector.PushPolyfillInjector
import net.kgtkr.polytwi.model.Account
import net.kgtkr.polytwi.push.NotificationDispatcher
import net.kgtkr.polytwi.push.PushBridgeClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class WebViewPool(
    private val accountManager: AccountManager,
    private val controlPanelInjector: ControlPanelInjector,
    private val cpftConfigBridge: CpftConfigBridge,
    private val pushBridgeClient: PushBridgeClient,
    private val notificationDispatcher: NotificationDispatcher,
    private val scope: CoroutineScope
) {
    private val pool = ConcurrentHashMap<String, WebView>()
    private val lastNightModeState = ConcurrentHashMap<String, Boolean>()

    init {
        cpftConfigBridge.onConfigChangedListener = { changesJson ->
            applyCpftConfigChanges(changesJson)
        }
    }

    fun getOrCreateWebView(
        activityContext: Context,
        account: Account,
        webChromeClient: WebChromeClient
    ): WebView {
        val existing = pool[account.id]
        if (existing != null) {
            existing.webChromeClient = webChromeClient
            val currentNight = isSystemNightMode(activityContext)
            val prevNight = lastNightModeState[account.id]
            if (prevNight != null && prevNight != currentNight) {
                lastNightModeState[account.id] = currentNight
                applyDarkModeSettings(activityContext, existing)
                syncNightModeCookie(activityContext, existing)
                existing.reload()
            }
            return existing
        }

        val newWebView = createWebView(activityContext, account, webChromeClient)
        pool[account.id] = newWebView
        lastNightModeState[account.id] = isSystemNightMode(activityContext)
        return newWebView
    }

    private fun isSystemNightMode(context: Context): Boolean {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun getCookieManagerForWebView(webView: WebView): CookieManager {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            return WebViewCompat.getProfile(webView).cookieManager
        }
        return CookieManager.getInstance()
    }

    private fun syncNightModeCookie(context: Context, webView: WebView) {
        val isNight = isSystemNightMode(context)
        val darkTheme = cpftConfigBridge.getString("darkModeTheme", "lightsOut")
        val modeVal = if (isNight) {
            if (darkTheme == "dim") "1" else "2"
        } else {
            "0"
        }
        val cookieManager = getCookieManagerForWebView(webView)
        cookieManager.setAcceptCookie(true)
        val maxAge = 60 * 60 * 24 * 365
        cookieManager.setCookie(
            "https://x.com",
            "night_mode=$modeVal; Domain=.x.com; Path=/; Max-Age=$maxAge; Secure; SameSite=None"
        )
        cookieManager.setCookie(
            "https://twitter.com",
            "night_mode=$modeVal; Domain=.twitter.com; Path=/; Max-Age=$maxAge; Secure; SameSite=None"
        )
        cookieManager.flush()
    }

    @Suppress("DEPRECATION")
    private fun applyDarkModeSettings(context: Context, webView: WebView) {
        val isNightMode = isSystemNightMode(context)
        webView.setBackgroundColor(if (isNightMode) Color.BLACK else Color.WHITE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                val forceDarkMode = if (isNightMode) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                WebSettingsCompat.setForceDark(webView.settings, forceDarkMode)
            }
        }
    }

    private fun createWebView(activityContext: Context, account: Account, chromeClient: WebChromeClient): WebView {
        val webView = WebView(activityContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Configure multi-profile if supported
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val profileStore = ProfileStore.getInstance()
            val profile = profileStore.getOrCreateProfile(account.profileName)
            WebViewCompat.setProfile(webView, profile.name)
        }

        // Configure settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)

            // Adjust User-Agent: remove '; wv' and 'Version/X.X' to disguise as normal Chrome Mobile
            val defaultUa = userAgentString
            val cleanedUa = defaultUa
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
            userAgentString = cleanedUa
        }

        // Enable system dark mode support & sync X.com night_mode cookie
        applyDarkModeSettings(activityContext, webView)
        syncNightModeCookie(activityContext, webView)

        webView.webChromeClient = chromeClient
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                return handleUrlNavigation(view?.context ?: activityContext, url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, urlStr: String?): Boolean {
                val url = urlStr?.let { Uri.parse(it) } ?: return false
                return handleUrlNavigation(view?.context ?: activityContext, url)
            }
        }

        // Handle image long press via native hitTestResult
        webView.setOnLongClickListener { v ->
            val result = (v as WebView).hitTestResult
            val type = result.type
            val extra = result.extra
            if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                if (!extra.isNullOrBlank()) {
                    MediaBridge.showImageOptionsDialog(activityContext, extra)
                    return@setOnLongClickListener true
                }
            }
            false
        }

        // Add Push JS Bridge, Shared CPFT Config Bridge, and Media Bridge
        val bridge = PushJsBridge(account.id, accountManager, pushBridgeClient, notificationDispatcher, scope)
        val mediaBridge = MediaBridge(activityContext, scope)
        webView.addJavascriptInterface(bridge, "AndroidPushBridge")
        webView.addJavascriptInterface(cpftConfigBridge, "AndroidCpftBridge")
        webView.addJavascriptInterface(mediaBridge, "AndroidMediaBridge")

        // Inject Push Polyfill, Media Listener & CPFT script at document start
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val allowedOrigins = setOf(
                "https://x.com",
                "https://*.x.com",
                "https://twitter.com",
                "https://*.twitter.com",
                "https://mobile.twitter.com"
            )

            // Inject push polyfill & image long-press listener
            WebViewCompat.addDocumentStartJavaScript(webView, PushPolyfillInjector.JS_POLYFILL, allowedOrigins)
            WebViewCompat.addDocumentStartJavaScript(webView, MediaBridge.JS_IMAGE_LONGPRESS_LISTENER, allowedOrigins)

            // Inject Control Panel for Twitter synchronously before loadUrl
            scope.launch(Dispatchers.Main) {
                val cpftScript = controlPanelInjector.getScript()
                WebViewCompat.addDocumentStartJavaScript(webView, cpftScript, allowedOrigins)
                webView.loadUrl("https://x.com")
            }
        } else {
            webView.loadUrl("https://x.com")
        }

        return webView
    }

    fun applyCpftConfigChanges(changesJson: String) {
        val escaped = JSONObject.quote(changesJson)
        pool.values.forEach { webView ->
            syncNightModeCookie(webView.context, webView)
            webView.evaluateJavascript(
                "if (window.__applyCpftConfigChanges) { window.__applyCpftConfigChanges($escaped); }",
                null
            )
        }
    }

    private fun handleUrlNavigation(context: Context, uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false

        // Allow common in-app navigation (x.com, twitter.com, t.co, authentication)
        val isInternal = host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") ||
                host == "t.co" || host == "accounts.google.com" ||
                host == "appleid.apple.com"

        if (isInternal) {
            return false // Stay inside WebView
        }

        // External link: open in external browser / Custom Tabs
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun removeWebView(accountId: String) {
        lastNightModeState.remove(accountId)
        val webView = pool.remove(accountId)
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
    }

    fun destroyAll() {
        lastNightModeState.clear()
        pool.values.forEach {
            it.stopLoading()
            it.destroy()
        }
        pool.clear()
    }
}
