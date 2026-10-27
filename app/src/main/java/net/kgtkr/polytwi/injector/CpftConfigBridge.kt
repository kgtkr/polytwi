package net.kgtkr.polytwi.injector

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kgtkr.polytwi.util.UrlValidator

class CpftConfigBridge(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "cpft_shared_config"
        private const val KEY_CONFIG_JSON = "__cpft_config_json__"

        // Schema-less initial seed: CPFT script.js and options.js maintain their own
        // defaultConfig/mobileConfig, so only seed "version": "mobile" when storage is empty.
        private val INITIAL_SEED_CONFIG: Map<String, JsonElement> = mapOf(
            "version" to JsonPrimitive("mobile")
        )
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _configFlow = MutableStateFlow(loadConfigMap())
    val configFlow: StateFlow<Map<String, JsonElement>> = _configFlow.asStateFlow()

    var onConfigChangedListener: ((String) -> Unit)? = null

    private fun loadConfigMap(): Map<String, JsonElement> {
        val raw = prefs.getString(KEY_CONFIG_JSON, null)
        val merged = INITIAL_SEED_CONFIG.toMutableMap()
        if (!raw.isNullOrBlank()) {
            try {
                val parsed = json.parseToJsonElement(raw).jsonObject
                merged.putAll(parsed)
            } catch (_: Exception) {
            }
        }
        return merged
    }

    @Synchronized
    private fun saveConfigMap(map: Map<String, JsonElement>) {
        val serialized = json.encodeToString(JsonObject(map))
        prefs.edit().putString(KEY_CONFIG_JSON, serialized).apply()
        _configFlow.value = map
    }

    private fun dispatchConfigChanges(changesJson: String) {
        val listener = onConfigChangedListener ?: return
        mainHandler.post {
            listener.invoke(changesJson)
        }
    }

    fun getString(key: String, default: String = ""): String {
        val prim = _configFlow.value[key] as? JsonPrimitive ?: return default
        return if (prim.isString) prim.content else default
    }

    @JavascriptInterface
    fun getAllConfig(): String {
        return json.encodeToString(JsonObject(_configFlow.value))
    }

    @JavascriptInterface
    fun mergeConfig(changesJson: String) {
        try {
            val parsed = json.parseToJsonElement(changesJson).jsonObject
            if (parsed.isEmpty()) return
            val updated = _configFlow.value.toMutableMap()
            var changed = false
            for ((k, v) in parsed) {
                if (updated[k] != v) {
                    updated[k] = v
                    changed = true
                }
            }
            if (changed) {
                saveConfigMap(updated)
                dispatchConfigChanges(changesJson)
            }
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun removeKeys(keysJson: String) {
        try {
            val element = json.parseToJsonElement(keysJson)
            val keysToRemove = when (element) {
                is JsonArray -> element.mapNotNull { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(element.content)
                else -> emptyList()
            }
            if (keysToRemove.isEmpty()) return
            val updated = _configFlow.value.toMutableMap()
            var changed = false
            for (k in keysToRemove) {
                if (updated.remove(k) != null) {
                    changed = true
                }
            }
            if (changed) {
                saveConfigMap(updated)
                dispatchConfigChanges(json.encodeToString(JsonObject(updated)))
            }
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun clearAllConfig(): String {
        saveConfigMap(INITIAL_SEED_CONFIG)
        val resetJson = json.encodeToString(JsonObject(INITIAL_SEED_CONFIG))
        dispatchConfigChanges(resetJson)
        return resetJson
    }

    @JavascriptInterface
    fun isDarkMode(): Boolean {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMask == Configuration.UI_MODE_NIGHT_YES
    }

    @JavascriptInterface
    fun get(key: String, defaultValueJson: String?): String? {
        val elem = _configFlow.value[key]
        if (elem != null) {
            return json.encodeToString(elem)
        }
        return prefs.getString(key, defaultValueJson)
    }

    @JavascriptInterface
    fun set(key: String, valueJson: String) {
        prefs.edit().putString(key, valueJson).apply()
        try {
            val elem = json.parseToJsonElement(valueJson)
            val updated = _configFlow.value.toMutableMap()
            if (updated[key] != elem) {
                updated[key] = elem
                saveConfigMap(updated)
                val singleChange = json.encodeToString(JsonObject(mapOf(key to elem)))
                dispatchConfigChanges(singleChange)
            }
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun delete(key: String) {
        prefs.edit().remove(key).apply()
        val updated = _configFlow.value.toMutableMap()
        if (updated.remove(key) != null) {
            saveConfigMap(updated)
        }
    }
}

class WebViewCpftBridge(
    private val delegate: CpftConfigBridge,
    private val bridgeToken: String,
    private val urlProvider: () -> String?
) {
    private fun isAuthorized(token: String?): Boolean {
        return bridgeToken.isNotEmpty() && token == bridgeToken && UrlValidator.isAllowedCpftOrigin(urlProvider())
    }

    @JavascriptInterface
    fun getAllConfig(token: String?): String {
        if (!isAuthorized(token)) return "{}"
        return delegate.getAllConfig()
    }

    @JavascriptInterface
    fun mergeConfig(token: String?, changesJson: String) {
        if (!isAuthorized(token)) return
        delegate.mergeConfig(changesJson)
    }

    @JavascriptInterface
    fun removeKeys(token: String?, keysJson: String) {
        if (!isAuthorized(token)) return
        delegate.removeKeys(keysJson)
    }

    @JavascriptInterface
    fun clearAllConfig(token: String?): String {
        if (!isAuthorized(token)) return "{}"
        return delegate.clearAllConfig()
    }

    @JavascriptInterface
    fun isDarkMode(token: String?): Boolean {
        if (!isAuthorized(token)) return false
        return delegate.isDarkMode()
    }

    @JavascriptInterface
    fun get(token: String?, key: String, defaultValueJson: String?): String? {
        if (!isAuthorized(token)) return defaultValueJson
        return delegate.get(key, defaultValueJson)
    }

    @JavascriptInterface
    fun set(token: String?, key: String, valueJson: String) {
        if (!isAuthorized(token)) return
        delegate.set(key, valueJson)
    }

    @JavascriptInterface
    fun delete(token: String?, key: String) {
        if (!isAuthorized(token)) return
        delegate.delete(key)
    }
}
