package net.kgtkr.polytwi.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import net.kgtkr.polytwi.injector.ControlPanelInjector
import net.kgtkr.polytwi.injector.CpftConfigBridge
import net.kgtkr.polytwi.injector.CpftOptionsBundle
import net.kgtkr.polytwi.injector.CpftReleaseInfo
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val OPTIONS_BASE_URL = "https://cpft-options.local/options.html"

private const val TWITTER_BIRD_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24"><path fill="#1d9bf0" d="M23.643 4.937c-.835.37-1.732.62-2.675.733.962-.576 1.7-1.49 2.048-2.578-.9.534-1.897.922-2.958 1.13-.85-.904-2.06-1.47-3.4-1.47-2.572 0-4.658 2.086-4.658 4.66 0 .364.042.718.12 1.06-3.873-.195-7.304-2.05-9.602-4.868-.4.69-.63 1.49-.63 2.342 0 1.616.823 3.043 2.072 3.878-.764-.025-1.482-.234-2.11-.583v.06c0 2.257 1.605 4.14 3.737 4.568-.392.106-.803.162-1.227.162-.3 0-.593-.028-.877-.082.593 1.85 2.313 3.198 4.352 3.234-1.595 1.25-3.604 1.995-5.786 1.995-.376 0-.747-.022-1.112-.065 2.062 1.323 4.51 2.093 7.14 2.093 8.57 0 13.255-7.098 13.255-13.254 0-.2-.005-.402-.014-.602.91-.658 1.7-1.477 2.323-2.41z"/></svg>"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CpftSettingsDialog(
    controlPanelInjector: ControlPanelInjector,
    cpftConfigBridge: CpftConfigBridge,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val coroutineScope = rememberCoroutineScope()
    var optionsBundle by remember { mutableStateOf<CpftOptionsBundle?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<CpftReleaseInfo?>(null) }
    var isApplyingUpdate by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    val currentVersion = remember(reloadTrigger) { controlPanelInjector.getCurrentVersion() }

    LaunchedEffect(reloadTrigger) {
        optionsBundle = controlPanelInjector.getOptionsBundle()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Control Panel for Twitter",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Version: $currentVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !isCheckingUpdate && !isApplyingUpdate,
                            onClick = {
                                isCheckingUpdate = true
                                coroutineScope.launch {
                                    val release = controlPanelInjector.checkForUpdates(force = true)
                                    isCheckingUpdate = false
                                    if (release != null) {
                                        pendingUpdate = release
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "You are using the latest release ($currentVersion)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        ) {
                            Text(if (isCheckingUpdate) "Checking..." else "Check for Updates")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }

                HorizontalDivider()

                val bundle = optionsBundle
                if (bundle == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.WHITE)
                                @Suppress("DEPRECATION")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                                    }
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                                        WebSettingsCompat.setForceDark(
                                            settings,
                                            if (isDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                                        )
                                    }
                                }
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                addJavascriptInterface(cpftConfigBridge, "AndroidCpftBridge")

                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                        if (consoleMessage != null) {
                                            Log.d(
                                                "CpftOptionsWebView",
                                                "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                                            )
                                        }
                                        return true
                                    }
                                }
                            }
                        },
                        update = { webView ->
                            webView.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val url = request?.url ?: return null
                                    if (url.host != "cpft-options.local") return null
                                    return when (url.path) {
                                        "/options.html" -> {
                                            val fullHtml = buildInjectedOptionsHtml(bundle, isDark)
                                            WebResourceResponse(
                                                "text/html",
                                                "UTF-8",
                                                ByteArrayInputStream(fullHtml.toByteArray(Charsets.UTF_8))
                                            )
                                        }
                                        "/options.css" -> {
                                            WebResourceResponse(
                                                "text/css",
                                                "UTF-8",
                                                ByteArrayInputStream(bundle.optionsCss.toByteArray(Charsets.UTF_8))
                                            )
                                        }
                                        "/options.js" -> {
                                            WebResourceResponse(
                                                "application/javascript",
                                                "UTF-8",
                                                ByteArrayInputStream(bundle.optionsJs.toByteArray(Charsets.UTF_8))
                                            )
                                        }
                                        "/options-icon.png" -> {
                                            WebResourceResponse(
                                                "image/svg+xml",
                                                "UTF-8",
                                                ByteArrayInputStream(TWITTER_BIRD_SVG.toByteArray(Charsets.UTF_8))
                                            )
                                        }
                                        else -> null
                                    }
                                }
                            }
                            val targetTag = "${reloadTrigger}_${isDark}"
                            if (webView.tag != targetTag) {
                                webView.tag = targetTag
                                webView.loadUrl(OPTIONS_BASE_URL)
                            }
                        },
                        onRelease = { webView ->
                            webView.stopLoading()
                            webView.destroy()
                        }
                    )
                }
            }
        }
    }

    pendingUpdate?.let { release ->
        CpftUpdateDialog(
            releaseInfo = release,
            currentVersion = currentVersion,
            isUpdating = isApplyingUpdate,
            onConfirmUpdate = {
                isApplyingUpdate = true
                coroutineScope.launch {
                    val success = controlPanelInjector.installRelease(release.tagName)
                    isApplyingUpdate = false
                    if (success) {
                        Toast.makeText(
                            context,
                            "Updated CPFT to ${release.tagName}",
                            Toast.LENGTH_SHORT
                        ).show()
                        pendingUpdate = null
                        reloadTrigger++
                    } else {
                        Toast.makeText(
                            context,
                            "Failed to download update",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onDismiss = {
                if (!isApplyingUpdate) pendingUpdate = null
            }
        )
    }
}

private fun buildInjectedOptionsHtml(bundle: CpftOptionsBundle, isDark: Boolean): String {
    val quotedJa = JSONObject.quote(bundle.messagesJa)
    val quotedEn = JSONObject.quote(bundle.messagesEn)

    val polyfillAndStyle = """
        <style>
            :root {
                color-scheme: ${if (isDark) "dark" else "light"};
            }
            body, body.browserAction {
                min-width: 0 !important;
                max-width: 100vw !important;
                overflow-x: hidden;
                padding: 8px 6px 32px 6px !important;
                box-sizing: border-box;
                font-size: 14px !important;
                background-color: ${if (isDark) "#121212" else "#ffffff"};
                color: ${if (isDark) "#e7e9ea" else "#0f1419"};
            }
            section.group > section {
                margin-left: 18px !important;
            }
            section.group > p, section.group > section > p {
                margin-left: 18px !important;
                margin-right: 12px !important;
                color: ${if (isDark) "#8b98a5" else "rgb(95, 99, 104)"} !important;
            }
            section.group > section > * {
                color: ${if (isDark) "#c4cfd6" else "rgb(95, 99, 104)"} !important;
            }
            section:not(:first-of-type) {
                border-top: 1px solid ${if (isDark) "#2f3336" else "#f0f0f0"} !important;
            }
            select, textarea, input[type="text"], button {
                background-color: ${if (isDark) "#1e2732" else "#f7f9f9"};
                color: ${if (isDark) "#e7e9ea" else "#0f1419"};
                border: 1px solid ${if (isDark) "#38444d" else "#cfd9de"};
                border-radius: 6px;
                padding: 4px 8px;
                font-size: 13px;
            }
            input[type="checkbox"] {
                width: 18px;
                height: 18px;
                accent-color: #1d9bf0;
            }
        </style>
        <script>
        (function() {
            let messagesJa = {};
            let messagesEn = {};
            try { messagesJa = JSON.parse($quotedJa); } catch (e) {}
            try { messagesEn = JSON.parse($quotedEn); } catch (e) {}

            const preferJa = (navigator.language || 'ja').toLowerCase().startsWith('ja');

            function getMessage(key, substitutions) {
                const primary = preferJa ? messagesJa : messagesEn;
                const secondary = preferJa ? messagesEn : messagesJa;
                const entry = (primary && primary[key]) || (secondary && secondary[key]);
                if (!entry || typeof entry.message !== 'string') {
                    return '';
                }
                let msg = entry.message;
                const subs = typeof substitutions === 'undefined'
                    ? []
                    : (Array.isArray(substitutions) ? substitutions : [substitutions]);

                if (entry.placeholders) {
                    Object.keys(entry.placeholders).forEach(function(phKey) {
                        const ph = entry.placeholders[phKey];
                        if (ph && ph.content) {
                            let contentVal = ph.content;
                            subs.forEach(function(sub, idx) {
                                contentVal = contentVal.replace(new RegExp('\\$' + (idx + 1), 'g'), String(sub));
                            });
                            msg = msg.replace(new RegExp('\\$' + phKey + '\\$', 'gi'), contentVal);
                        }
                    });
                }
                subs.forEach(function(sub, idx) {
                    msg = msg.replace(new RegExp('\\$' + (idx + 1), 'g'), String(sub));
                });
                return msg;
            }

            const storageListeners = [];
            function notifyListeners(changes) {
                if (!changes || Object.keys(changes).length === 0) return;
                storageListeners.slice().forEach(function(fn) {
                    try { fn(changes, 'local'); } catch (e) { console.error(e); }
                });
            }

            function readAllFromBridge() {
                try {
                    if (window.AndroidCpftBridge && window.AndroidCpftBridge.getAllConfig) {
                        return JSON.parse(window.AndroidCpftBridge.getAllConfig() || '{"version":"mobile"}');
                    }
                } catch (e) {
                    console.error('[CPFT Options] readAllFromBridge error', e);
                }
                return { version: 'mobile' };
            }

            const onChangedEvent = {
                addListener: function(cb) {
                    if (typeof cb === 'function' && !storageListeners.includes(cb)) {
                        storageListeners.push(cb);
                    }
                },
                removeListener: function(cb) {
                    const idx = storageListeners.indexOf(cb);
                    if (idx !== -1) storageListeners.splice(idx, 1);
                },
                hasListener: function(cb) {
                    return storageListeners.includes(cb);
                }
            };

            const localArea = {
                get: function(keys, callback) {
                    const all = readAllFromBridge();
                    let cb = callback;
                    let result = {};
                    if (typeof keys === 'function') {
                        cb = keys;
                        result = Object.assign({}, all);
                    } else if (keys === null || typeof keys === 'undefined') {
                        result = Object.assign({}, all);
                    } else if (typeof keys === 'string') {
                        if (keys in all) result[keys] = all[keys];
                    } else if (Array.isArray(keys)) {
                        keys.forEach(function(k) {
                            if (k in all) result[k] = all[k];
                        });
                    } else if (typeof keys === 'object') {
                        result = Object.assign({}, keys, all);
                    }
                    if (typeof cb === 'function') {
                        setTimeout(function() { cb(result); }, 0);
                    }
                    return Promise.resolve(result);
                },
                set: function(items, callback) {
                    const prev = readAllFromBridge();
                    const changes = {};
                    if (items && typeof items === 'object') {
                        Object.keys(items).forEach(function(k) {
                            changes[k] = {
                                oldValue: prev[k],
                                newValue: items[k]
                            };
                        });
                        try {
                            if (window.AndroidCpftBridge && window.AndroidCpftBridge.mergeConfig) {
                                window.AndroidCpftBridge.mergeConfig(JSON.stringify(items));
                            }
                        } catch (e) {
                            console.error('[CPFT Options] mergeConfig error', e);
                        }
                    }
                    notifyListeners(changes);
                    if (typeof callback === 'function') {
                        setTimeout(function() { callback(); }, 0);
                    }
                    return Promise.resolve();
                },
                remove: function(keys, callback) {
                    const prev = readAllFromBridge();
                    const keyList = Array.isArray(keys) ? keys : [keys];
                    const changes = {};
                    keyList.forEach(function(k) {
                        if (k in prev) {
                            changes[k] = { oldValue: prev[k] };
                        }
                    });
                    try {
                        if (window.AndroidCpftBridge && window.AndroidCpftBridge.removeKeys) {
                            window.AndroidCpftBridge.removeKeys(JSON.stringify(keyList));
                        }
                    } catch (e) {
                        console.error('[CPFT Options] removeKeys error', e);
                    }
                    notifyListeners(changes);
                    if (typeof callback === 'function') {
                        setTimeout(function() { callback(); }, 0);
                    }
                    return Promise.resolve();
                },
                clear: function(callback) {
                    try {
                        if (window.AndroidCpftBridge && window.AndroidCpftBridge.clearAllConfig) {
                            window.AndroidCpftBridge.clearAllConfig();
                        }
                    } catch (e) {}
                    if (typeof callback === 'function') {
                        setTimeout(function() { callback(); }, 0);
                    }
                    return Promise.resolve();
                },
                onChanged: onChangedEvent
            };

            window.chrome = window.chrome || {};
            window.chrome.i18n = {
                getMessage: getMessage,
                getUILanguage: function() { return preferJa ? 'ja' : 'en'; }
            };
            window.chrome.storage = {
                local: localArea,
                sync: localArea,
                onChanged: onChangedEvent
            };
            window.chrome.runtime = window.chrome.runtime || {
                lastError: null,
                sendMessage: function() { return Promise.resolve(); },
                onMessage: { addListener: function() {}, removeListener: function() {} },
                getURL: function(path) { return path; }
            };
            window.browser = window.chrome;
        })();
        </script>
    """.trimIndent()

    val headIndex = bundle.optionsHtml.indexOf("<head>", ignoreCase = true)
    return if (headIndex != -1) {
        val insertPos = headIndex + "<head>".length
        bundle.optionsHtml.substring(0, insertPos) + "\n" + polyfillAndStyle + "\n" + bundle.optionsHtml.substring(insertPos)
    } else {
        polyfillAndStyle + "\n" + bundle.optionsHtml
    }
}
