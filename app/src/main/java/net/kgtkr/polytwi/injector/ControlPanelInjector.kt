package net.kgtkr.polytwi.injector

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

data class CpftOptionsBundle(
    val version: String,
    val optionsHtml: String,
    val messagesJa: String,
    val messagesEn: String
)

data class CpftReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val assetDownloadUrl: String? = null
)

class ControlPanelInjector(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ControlPanelInjector"
        const val DEFAULT_FALLBACK_TAG = "v4.99.4"
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/insin/control-panel-for-twitter/releases/latest"
        private const val GITHUB_RELEASE_BY_TAG_API =
            "https://api.github.com/repos/insin/control-panel-for-twitter/releases/tags"

        private const val PREFS_NAME = "cpft_prefs"
        private const val KEY_CURRENT_VERSION = "current_version"
        private const val KEY_LAST_CHECK_MS = "last_check_ms"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

        private const val RELEASE_DIR_NAME = "cpft_release"
        private const val RELEASE_TMP_DIR_NAME = "cpft_release_tmp"
        private const val RELEASE_OLD_DIR_NAME = "cpft_release_old"

        private const val MAX_ZIP_ENTRIES = 500
        private const val MAX_ZIP_TOTAL_BYTES = 20L * 1024 * 1024 // 20 MB
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<String, String>()
    private val releaseDir: File
        get() = File(context.filesDir, RELEASE_DIR_NAME)

    fun getCurrentVersion(): String {
        return prefs.getString(KEY_CURRENT_VERSION, null) ?: DEFAULT_FALLBACK_TAG
    }

    private fun setCurrentVersion(version: String) {
        prefs.edit().putString(KEY_CURRENT_VERSION, version).apply()
    }

    suspend fun getScript(): String = withContext(Dispatchers.IO) {
        val cached = readReleaseText("script.js") ?: readLegacyCacheFile("cpft_script.js")
        if (cached != null) {
            return@withContext cached
        }
        // First run: attempt to install the pinned default release tag
        if (installRelease(DEFAULT_FALLBACK_TAG)) {
            val installed = readReleaseText("script.js")
            if (installed != null) return@withContext installed
        }
        val fallback = loadFromAsset()
        memoryCache["script.js"] = fallback
        return@withContext fallback
    }

    suspend fun getOptionsBundle(): CpftOptionsBundle = withContext(Dispatchers.IO) {
        var html = readReleaseText("options.html")
        if (html == null) {
            val targetTag = getCurrentVersion()
            if (!installRelease(targetTag) && targetTag != DEFAULT_FALLBACK_TAG) {
                installRelease(DEFAULT_FALLBACK_TAG)
            }
            html = readReleaseText("options.html") ?: readLegacyCacheFile("cpft_options.html")
        }
        val ja = readReleaseText("_locales/ja/messages.json")
            ?: readLegacyCacheFile("cpft_messages_ja.json")
            ?: "{}"
        val en = readReleaseText("_locales/en/messages.json")
            ?: readLegacyCacheFile("cpft_messages_en.json")
            ?: "{}"

        CpftOptionsBundle(
            version = getCurrentVersion(),
            optionsHtml = html ?: "<!DOCTYPE html><html><body><p>Failed to load CPFT options.</p></body></html>",
            messagesJa = ja,
            messagesEn = en
        )
    }

    fun readReleaseFile(urlPath: String): ByteArray? {
        val cleanPath = urlPath.trimStart('/')
        if (cleanPath.isEmpty()) return null
        val baseDir = releaseDir
        if (baseDir.isDirectory) {
            try {
                val baseCanonical = baseDir.canonicalPath + File.separator
                val targetFile = File(baseDir, cleanPath).canonicalFile
                if (targetFile.path.startsWith(baseCanonical) && targetFile.isFile) {
                    return targetFile.readBytes()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read release file: $cleanPath", e)
            }
        }
        // Fallback for legacy single-file cache before migration to zip extraction
        val legacyName = when (cleanPath) {
            "options.css" -> "cpft_options.css"
            "options.js" -> "cpft_options.js"
            else -> null
        }
        return legacyName?.let { readLegacyCacheFile(it)?.toByteArray(Charsets.UTF_8) }
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
                val assetUrl = selectZipAssetUrl(json.optJSONArray("assets"))

                if (tagName.isNotBlank() && tagName != getCurrentVersion()) {
                    return@withContext CpftReleaseInfo(
                        tagName = tagName,
                        name = name,
                        body = body,
                        publishedAt = publishedAt,
                        assetDownloadUrl = assetUrl
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for CPFT updates", e)
        }
        null
    }

    suspend fun installRelease(tag: String, assetDownloadUrl: String? = null): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting CPFT installation for release tag: $tag")

        val zipUrl = assetDownloadUrl ?: fetchReleaseZipAssetUrl(tag)
        if (zipUrl.isNullOrBlank()) {
            Log.e(TAG, "Could not find release zip asset for tag $tag")
            return@withContext false
        }

        val zipBytes = downloadBytes(zipUrl)
        if (zipBytes == null) {
            Log.e(TAG, "Failed to download release archive: $zipUrl")
            return@withContext false
        }

        val tmpDir = File(context.filesDir, RELEASE_TMP_DIR_NAME)
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        if (!tmpDir.mkdirs()) {
            Log.e(TAG, "Failed to create temporary extraction directory")
            return@withContext false
        }

        val extracted = extractZipSafely(zipBytes, tmpDir)
        if (!extracted) {
            tmpDir.deleteRecursively()
            return@withContext false
        }

        if (!File(tmpDir, "script.js").isFile || !File(tmpDir, "options.html").isFile) {
            Log.e(TAG, "Release archive for $tag is missing required files (script.js / options.html)")
            tmpDir.deleteRecursively()
            return@withContext false
        }

        val targetDir = releaseDir
        val oldDir = File(context.filesDir, RELEASE_OLD_DIR_NAME)
        if (oldDir.exists()) oldDir.deleteRecursively()

        if (targetDir.exists() && !targetDir.renameTo(oldDir)) {
            targetDir.deleteRecursively()
        }
        if (!tmpDir.renameTo(targetDir)) {
            Log.e(TAG, "Failed to move extracted CPFT release into place")
            if (oldDir.exists()) oldDir.renameTo(targetDir)
            tmpDir.deleteRecursively()
            return@withContext false
        }
        if (oldDir.exists()) oldDir.deleteRecursively()

        memoryCache.clear()
        setCurrentVersion(tag)
        Log.i(TAG, "Successfully installed CPFT release $tag from $zipUrl")
        true
    }

    private fun fetchReleaseZipAssetUrl(tag: String): String? {
        return try {
            val request = Request.Builder()
                .url("$GITHUB_RELEASE_BY_TAG_API/$tag")
                .header("User-Agent", "PolyTwiAndroid")
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub Release tag API returned ${response.code} for $tag")
                    return null
                }
                val bodyStr = response.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                selectZipAssetUrl(json.optJSONArray("assets"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching release metadata for $tag", e)
            null
        }
    }

    private fun selectZipAssetUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        var mv3Url: String? = null
        var mv2Url: String? = null
        var fallbackZipUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "").lowercase()
            val url = asset.optString("browser_download_url", "")
            if (url.isBlank() || !name.endsWith(".zip")) continue
            when {
                name.contains(".mv3.zip") -> mv3Url = url
                name.contains(".mv2.zip") -> mv2Url = url
                fallbackZipUrl == null -> fallbackZipUrl = url
            }
        }
        return mv3Url ?: mv2Url ?: fallbackZipUrl
    }

    private fun extractZipSafely(zipBytes: ByteArray, destDir: File): Boolean {
        return try {
            val destCanonical = destDir.canonicalPath + File.separator
            var entryCount = 0
            var totalBytes = 0L
            val buffer = ByteArray(8192)

            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        Log.e(TAG, "Zip archive exceeded maximum entry count")
                        return false
                    }
                    val outFile = File(destDir, entry.name).canonicalFile
                    if (!outFile.path.startsWith(destCanonical)) {
                        Log.e(TAG, "Blocked zip path traversal entry: ${entry.name}")
                        return false
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { fos ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read <= 0) break
                                totalBytes += read
                                if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                                    Log.e(TAG, "Zip archive exceeded maximum extracted size")
                                    return false
                                }
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract CPFT release zip", e)
            false
        }
    }

    private fun readReleaseText(relativePath: String): String? {
        memoryCache[relativePath]?.let { return it }
        val bytes = readReleaseFile(relativePath) ?: return null
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isNotBlank()) {
            memoryCache[relativePath] = text
            return text
        }
        return null
    }

    private fun readLegacyCacheFile(cacheFileName: String): String? {
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

    private fun downloadBytes(url: String): ByteArray? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PolyTwiAndroid")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to download $url: HTTP ${response.code}")
                    return null
                }
                val stream = response.body?.byteStream() ?: return null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                stream.use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_ZIP_TOTAL_BYTES) {
                            Log.e(TAG, "Downloaded archive exceeded size limit")
                            return null
                        }
                        out.write(buf, 0, read)
                    }
                }
                out.toByteArray()
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
