package net.kgtkr.polytwi.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.kgtkr.polytwi.PolyTwiApp

class PushForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "autopush_service_channel"

        fun start(context: Context) {
            val intent = Intent(context, PushForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        val app = application as PolyTwiApp
        app.autopushClient.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PolyTwiApp
        app.autopushClient.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Push Service (Ongoing Notification)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the push service running in the background. To hide the status bar notification, disable only this notification category."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("X Push Service Active")
            .setContentText("Listening for incoming notifications (can be hidden in settings)")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .setOngoing(true)
            .build()
    }
}
