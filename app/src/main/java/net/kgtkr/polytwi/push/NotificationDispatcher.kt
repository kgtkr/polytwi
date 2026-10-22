package net.kgtkr.polytwi.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kgtkr.polytwi.R
import net.kgtkr.polytwi.account.AccountManager
import net.kgtkr.polytwi.model.Account
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL

class NotificationDispatcher(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val accountManager: AccountManager? = null
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_GROUP_ID = "x_accounts_group"
    }

    fun syncChannels(accounts: List<Account>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val activeChannelIds = accounts.map { "channel_account_${it.id}" }.toSet()

        // 1. Ensure/update notification channel for each active account
        for (account in accounts) {
            getOrCreateChannel(account.id, account.name)
        }

        // 2. Clean up orphaned channels from deleted profiles
        try {
            val existingChannels = notificationManager.notificationChannels ?: return
            for (channel in existingChannels) {
                if (channel.id.startsWith("channel_account_") && channel.id !in activeChannelIds) {
                    notificationManager.deleteNotificationChannel(channel.id)
                    android.util.Log.i("NotificationDispatcher", "Deleted orphaned notification channel: ${channel.id}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NotificationDispatcher", "Failed to cleanup notification channels", e)
        }
    }

    fun getOrCreateChannel(accountId: String, accountName: String): String {
        val channelId = "channel_account_$accountId"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "X Notifications: $accountName",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Push notifications for account $accountName (mentions, DMs, etc.)"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        return channelId
    }

    suspend fun showNotification(
        accountId: String,
        accountName: String,
        payload: TwitterNotificationPayload
    ) = withContext(Dispatchers.IO) {
        val channelId = getOrCreateChannel(accountId, accountName)

        val rawTargetUrl = payload.data?.url ?: payload.data?.uri ?: "https://x.com/notifications"
        val targetUrl = resolveUrl(rawTargetUrl)
        val intent = Intent().apply {
            setClassName(context.packageName, "net.kgtkr.polytwi.MainActivity")
            putExtra("EXTRA_ACCOUNT_ID", accountId)
            putExtra("EXTRA_TARGET_URL", targetUrl)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (accountId.hashCode() xor targetUrl.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconBitmap = payload.icon?.let { fetchImageBitmap(it) }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(payload.title ?: "X ($accountName)")
            .setContentText(payload.body ?: "New notification")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (iconBitmap != null) {
            builder.setLargeIcon(iconBitmap)
        }

        val notificationId = (accountId.hashCode() * 31 + (payload.tag?.hashCode() ?: System.currentTimeMillis().toInt())) and 0x7FFFFFFF
        notificationManager.notify(notificationId, builder.build())
        accountManager?.markAccountUnread(accountId)
        android.util.Log.i("NotificationDispatcher", "Notification successfully posted to Android system: ID=$notificationId, title=${payload.title}")
    }

    private fun fetchImageBitmap(urlStr: String): Bitmap? {
        return try {
            val request = Request.Builder().url(urlStr).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://x.com$trimmed"
            else -> "https://x.com/$trimmed"
        }
    }
}
