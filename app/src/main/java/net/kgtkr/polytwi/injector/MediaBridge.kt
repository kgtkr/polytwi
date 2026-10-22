package net.kgtkr.polytwi.injector

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MediaBridge(
    private val context: Context,
    private val scope: CoroutineScope
) {
    @JavascriptInterface
    fun onImageLongPressed(imageUrl: String) {
        scope.launch(Dispatchers.Main) {
            showImageOptionsDialog(context, imageUrl)
        }
    }

    companion object {
        const val JS_IMAGE_LONGPRESS_LISTENER = """
            (function() {
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
                        if (window.AndroidMediaBridge) {
                            e.preventDefault();
                            window.AndroidMediaBridge.onImageLongPressed(imgUrl);
                        }
                    }
                }, true);
            })();
        """

        fun showImageOptionsDialog(context: Context, rawUrl: String) {
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
                val uri = Uri.parse(url)
                val format = uri.getQueryParameter("format") ?: "jpg"
                val mediaId = uri.lastPathSegment ?: System.currentTimeMillis().toString()
                val fileName = "twitter_${mediaId}_orig.$format"

                val request = DownloadManager.Request(uri).apply {
                    setTitle("Saving $fileName")
                    setDescription("Downloading original image from X")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "PolyTwi/$fileName")

                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (!cookies.isNullOrBlank()) {
                        addRequestHeader("Cookie", cookies)
                    }
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
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Image Link", url))
            Toast.makeText(context, "Image link copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        private fun openExternal(context: Context, url: String) {
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
