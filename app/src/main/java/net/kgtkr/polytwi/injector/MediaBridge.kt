package net.kgtkr.polytwi.injector

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.JavascriptInterface
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kgtkr.polytwi.util.UrlValidator

class MediaBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bridgeToken: String,
    private val currentUrlProvider: () -> String? = { null }
) {
    @JavascriptInterface
    fun onImageLongPressed(token: String?, imageUrl: String) {
        if (bridgeToken.isEmpty() || token != bridgeToken) {
            android.util.Log.w("MediaBridge", "Blocked onImageLongPressed with invalid bridge token")
            return
        }
        val currentUrl = currentUrlProvider()
        if (!UrlValidator.isAllowedWebUrl(currentUrl)) {
            android.util.Log.w("MediaBridge", "Blocked onImageLongPressed from untrusted origin: $currentUrl")
            return
        }
        if (!UrlValidator.isAllowedImageHost(imageUrl)) {
            android.util.Log.w("MediaBridge", "Blocked onImageLongPressed for untrusted image host: $imageUrl")
            return
        }
        scope.launch(Dispatchers.Main) {
            showImageOptionsDialog(context, imageUrl)
        }
    }

    companion object {
        fun buildLongPressListenerScript(bridgeToken: String): String = """
            (function() {
                const _bridgeToken = "$bridgeToken";
                const _mediaBridge = window.AndroidMediaBridge;
                try {
                    delete window.AndroidMediaBridge;
                } catch (_) {}
                try {
                    Object.defineProperty(window, 'AndroidMediaBridge', {
                        value: undefined,
                        writable: false,
                        configurable: true
                    });
                } catch (_) {}

                function findImageUrl(el) {
                    if (!el) return null;
                    if (el.tagName === 'IMG' && el.src) return el.src;
                    let img = el.querySelector('img');
                    if (img && img.src) return img.src;
                    
                    let container = el.closest('div[data-testid="tweetPhoto"]') ||
                                    el.closest('div[data-testid="swipe-to-dismiss"]') ||
                                    el.closest('div[aria-label="Image"]') ||
                                    el.closest('div[data-testid="carousel"]') ||
                                    el.closest('div[data-testid="preview"]');
                    if (container) {
                        let cImg = container.querySelector('img');
                        if (cImg && cImg.src) return cImg.src;
                    }

                    let bg = window.getComputedStyle(el).backgroundImage;
                    if (bg && bg.startsWith('url(')) {
                        return bg.slice(4, -1).replace(/["']/g, "");
                    }
                    return null;
                }

                document.addEventListener('contextmenu', function(e) {
                    let imgUrl = findImageUrl(e.target);
                    if (imgUrl && (imgUrl.includes('twimg.com') || imgUrl.startsWith('http'))) {
                        if (_mediaBridge) {
                            e.preventDefault();
                            _mediaBridge.onImageLongPressed(_bridgeToken, imgUrl);
                        }
                    }
                }, true);
            })();
        """

        fun showImageOptionsDialog(context: Context, rawUrl: String) {
            if (!UrlValidator.isAllowedImageHost(rawUrl)) {
                return
            }
            val originalUrl = getOriginalQualityUrl(rawUrl)

            AlertDialog.Builder(context)
                .setTitle("Image Options")
                .setItems(arrayOf("Save Image (Original Quality)", "Copy Image Link", "Open in External App")) { _, which ->
                    when (which) {
                        0 -> downloadImage(context, originalUrl)
                        1 -> copyLink(context, originalUrl)
                        2 -> openExternal(context, originalUrl)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun getOriginalQualityUrl(url: String): String {
            return try {
                val uri = Uri.parse(url)
                if (uri.host?.contains("twimg.com") == true && uri.path?.contains("/media/") == true) {
                    val builder = uri.buildUpon().clearQuery()
                    val format = uri.getQueryParameter("format") ?: "jpg"
                    builder.appendQueryParameter("format", format)
                    builder.appendQueryParameter("name", "orig")
                    builder.build().toString()
                } else {
                    url
                }
            } catch (e: Exception) {
                url
            }
        }

        fun downloadImage(context: Context, url: String) {
            try {
                if (!UrlValidator.isAllowedImageHost(url)) {
                    Toast.makeText(context, "Invalid or untrusted image URL", Toast.LENGTH_SHORT).show()
                    return
                }

                val uri = Uri.parse(url)
                val rawFormat = uri.getQueryParameter("format")?.lowercase() ?: "jpg"
                val safeFormat = when (rawFormat) {
                    "png" -> "png"
                    "webp" -> "webp"
                    "gif" -> "gif"
                    "jpeg", "jpg" -> "jpg"
                    else -> "jpg"
                }

                val rawMediaId = uri.lastPathSegment ?: System.currentTimeMillis().toString()
                val safeMediaId = rawMediaId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val fileName = "twitter_${safeMediaId}_orig.$safeFormat"

                val request = DownloadManager.Request(uri).apply {
                    setTitle("Saving $fileName")
                    setDescription("Downloading original image from X")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "PolyTwi/$fileName")
                    // Do not attach session cookies; public CDN images do not need authentication
                    addRequestHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                    )
                }

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                dm?.enqueue(request)
                Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to download image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        private fun copyLink(context: Context, url: String) {
            if (!UrlValidator.isAllowedImageHost(url)) return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Image Link", url))
            Toast.makeText(context, "Image link copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        private fun openExternal(context: Context, url: String) {
            if (!UrlValidator.isAllowedImageHost(url)) return
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
