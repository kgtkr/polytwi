package net.kgtkr.polytwi.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.kgtkr.polytwi.PolyTwiApp

class PolyTwiFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "PolyTwiFCMService"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed. Syncing channels with bridge...")
        val app = application as? PolyTwiApp ?: return
        app.pushBridgeClient.syncChannels(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val payload = message.data
        if (payload.isEmpty()) return

        val app = application as? PolyTwiApp ?: return
        when (payload["type"]) {
            "notification" -> {
                runBlocking(Dispatchers.IO) {
                    app.pushBridgeClient.handleNotificationPayload(payload)
                }
            }
            "channel_expired" -> {
                val channelId = payload["channel_id"]
                if (!channelId.isNullOrBlank()) {
                    app.pushBridgeClient.handleChannelExpired(channelId)
                }
            }
            else -> {
                Log.d(TAG, "Ignored FCM message of type: ${payload["type"]}")
            }
        }
    }
}
