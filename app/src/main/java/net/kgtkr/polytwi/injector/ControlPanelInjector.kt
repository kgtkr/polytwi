package net.kgtkr.polytwi.injector

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CpftOptionsBundle(
    val optionsHtml: String,
    val optionsJs: String,
    val optionsCss: String,
    val messagesJa: String,
    val messagesEn: String
)

data class CpftReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String
)

class ControlPanelInjector(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ControlPanelInjector"
        const val DEFAULT_FALLBACK_TAG = "v4.25.1"
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/insin/control-panel-for-twitter/releases/latest"
        private const val RAW_BASE_URL =
            "https://raw.githubusercontent.com/insin/control-panel-for-twitter"

        private const val PREFS_NAME = "cpft_prefs"
        private const val KEY_CURRENT_VERSION = "current_version"
        private const val KEY_LAST_CHECK_MS = "last_check_ms"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

        private const val FILE_SCRIPT = "script.js"
        private const val FILE_OPTIONS_HTML = "options.html"
        private const val FILE_OPTIONS_JS = "options.js"
        private const val FILE_OPTIONS_CSS = "options.css"
        private const val FILE_MESSAGES_JA = "_locales/ja/messages.json"
        private const val FILE_MESSAGES_EN = "_locales/en/messages.json"

        private val ALL_REMOTE_FILES = listOf(
            FILE_SCRIPT to "cpft_script.js",
            FILE_OPTIONS_HTML to "cpft_options.html",
            FILE_OPTIONS_JS to "cpft_options.js",
            FILE_OPTIONS_CSS to "cpft_options.css",
            FILE_MESSAGES_JA to "cpft_messages_ja.json",
            FILE_MESSAGES_EN to "cpft_messages_en.json"
        )
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<String, String>()

    fun getCurrentVersion(): String {
        return prefs.getString(KEY_CURRENT_VERSION, null) ?: DEFAULT_FALLBACK_TAG
    }

    private fun setCurrentVersion(version: String) {
        prefs.edit().putString(KEY_CURRENT_VERSION, version).apply()
    }

    suspend fun getScript(): String = withContext(Dispatchers.IO) {
        val cached = readCacheFile("cpft_script.js")
        if (cached != null) {
            return@withContext cached
        }
        // First run: attempt to install the pinned default release tag
        if (installRelease(DEFAULT_FALLBACK_TAG)) {
            val installed = readCacheFile("cpft_script.js")
            if (installed != null) return@withContext installed
        }
        val fallback = loadFromAsset()
        memoryCache["cpft_script.js"] = fallback
        return@withContext fallback
    }

    suspend fun getOptionsBundle(): CpftOptionsBundle = withContext(Dispatchers.IO) {
        var html = readCacheFile("cpft_options.html")
        if (html == null) {
            installRelease(DEFAULT_FALLBACK_TAG)
            html = readCacheFile("cpft_options.html")
        }
        val js = readCacheFile("cpft_options.js") ?: ""
        val css = readCacheFile("cpft_options.css") ?: ""
        val ja = readCacheFile("cpft_messages_ja.json") ?: "{}"
        val en = readCacheFile("cpft_messages_en.json") ?: "{}"

        CpftOptionsBundle(
            optionsHtml = html ?: "<!DOCTYPE html><html><body><p>Failed to load CPFT options.</p></body></html>",
            optionsJs = js,
            optionsCss = css,
            messagesJa = ja,
            messagesEn = en
        )
    }

    suspend fun checkForUpdates(force: Boolean = false): CpftReleaseInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        if (!force && (now - lastCheck) < CHECK_INTERVAL_MS) {
            Log.d(TAG, "Skipping update check (checked within 24h)")
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_API)
                .header("User-Agent", "PolyTwiAndroid")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub Releases API returned ${response.code}")
                    return@withContext null
                }
                prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()

                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                val tagName = json.optString("tag_name", "")
                val name = json.optString("name", tagName)
                val body = json.optString("body", "")
                val publishedAt = json.optString("published_at", "")

                if (tagName.isNotBlank() && tagName != getCurrentVersion()) {
                    return@withContext CpftReleaseInfo(
                        tagName = tagName,
                        name = name,
                        body = body,
                        publishedAt = publishedAt
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for CPFT updates", e)
        }
        null
    }

    suspend fun installRelease(tag: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting CPFT installation for release tag: $tag")
        val downloadedFiles = mutableMapOf<String, String>()

        for ((remotePath, cacheName) in ALL_REMOTE_FILES) {
            val url = "$RAW_BASE_URL/$tag/$remotePath"
            val content = downloadUrl(url)
            if (content == null) {
                Log.e(TAG, "Failed to download $url - aborting install of $tag")
                return@withContext false
            }
            downloadedFiles[cacheName] = content
        }

        // Write all downloaded files atomically into cache
        for ((cacheName, content) in downloadedFiles) {
            File(context.filesDir, cacheName).writeText(content)
            memoryCache[cacheName] = content
        }

        setCurrentVersion(tag)
        Log.i(TAG, "Successfully installed CPFT release $tag")
        true
    }

    private fun readCacheFile(cacheFileName: String): String? {
        memoryCache[cacheFileName]?.let { return it }
        val file = File(context.filesDir, cacheFileName)
        if (file.exists()) {
            val text = file.readText()
            if (text.isNotBlank()) {
                memoryCache[cacheFileName] = text
                return text
            }
        }
        return null
    }

    private fun downloadUrl(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PolyTwiAndroid")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    Log.w(TAG, "Failed to download $url: HTTP ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading $url", e)
            null
        }
    }

    private fun loadFromAsset(): String {
        return try {
            context.assets.open("cpft.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read assets/cpft.js", e)
            "console.log('CPFT not available');"
        }
    }
}
