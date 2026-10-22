package net.kgtkr.polytwi.push

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.util.Log
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kgtkr.polytwi.account.AccountManager
import net.kgtkr.polytwi.crypto.Rfc8291Decryptor
import net.kgtkr.polytwi.crypto.WebPushCryptoManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AutopushClient(
    private val context: Context,
    private val accountManager: AccountManager,
    private val notificationDispatcher: NotificationDispatcher,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AutopushClient"
        private const val AUTOPUSH_URL = "wss://push.services.mozilla.com/"
        private const val PREFS_NAME = "autopush_pref"
        private const val KEY_UAID = "uaid"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectJob: Job? = null
    private var retryDelayMs = 2000L

    // Pending registration requests: channelID -> Continuation<pushEndpoint>
    private val pendingRegistrations = ConcurrentHashMap<String, (String?) -> Unit>()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var isNetworkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network became available. Checking WebSocket connection...")
            scope.launch {
                // If not connected or reconnecting, immediately reconnect
                if (!isConnected || webSocket == null) {
                    retryDelayMs = 2000L
                    reconnectJob?.cancel()
                    connect()
                }
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network connection lost.")
            isConnected = false
        }
    }

    fun start() {
        registerNetworkCallback()
        if (webSocket != null) return
        connect()
    }

    fun stop() {
        unregisterNetworkCallback()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnected = false
    }

    private fun registerNetworkCallback() {
        if (!isNetworkCallbackRegistered && connectivityManager != null) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
                isNetworkCallbackRegistered = true
                Log.d(TAG, "Registered default network callback")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register network callback", e)
            }
        }
    }

    private fun unregisterNetworkCallback() {
        if (isNetworkCallbackRegistered && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                isNetworkCallbackRegistered = false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback", e)
            }
        }
    }

    private fun connect() {
        val request = Request.Builder().url(AUTOPUSH_URL).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to Autopush WebSocket")
                isConnected = true
                retryDelayMs = 2000L

                val savedUaid = prefs.getString(KEY_UAID, "") ?: ""
                val helloMsg = buildMap {
                    put("messageType", "hello")
                    put("use_webpush", true)
                    if (savedUaid.isNotBlank()) {
                        put("uaid", savedUaid)
                    }
                }
                // manual JSON creation for hello
                val helloJson = if (savedUaid.isNotBlank()) {
                    """{"messageType":"hello","use_webpush":true,"uaid":"$savedUaid"}"""
                } else {
                    """{"messageType":"hello","use_webpush":true}"""
                }
                ws.send(helloJson)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch(Dispatchers.IO) {
                    handleIncomingMessage(ws, text)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected = false
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                isConnected = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        webSocket = null
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(60000L)
            Log.d(TAG, "Attempting reconnect to Autopush...")
            connect()
        }
    }

    private suspend fun handleIncomingMessage(ws: WebSocket, text: String) {
        try {
            val msg = json.decodeFromString<AutopushMessage>(text)
            when (msg.messageType) {
                "hello" -> {
                    val receivedUaid = msg.uaid
                    if (receivedUaid != null) {
                        val currentUaid = prefs.getString(KEY_UAID, null)
                        if (currentUaid != null && currentUaid != receivedUaid) {
                            Log.w(TAG, "UAID changed from $currentUaid to $receivedUaid. Channels expired.")
                            handleUaidExpired()
                        }
                        prefs.edit().putString(KEY_UAID, receivedUaid).apply()
                        Log.d(TAG, "UAID confirmed: $receivedUaid")
                    }
                }
                "register" -> {
                    val channelId = msg.channelID
                    val pushEndpoint = msg.pushEndpoint
                    if (channelId != null) {
                        val callback = pendingRegistrations.remove(channelId)
                        callback?.invoke(pushEndpoint)
                    }
                }
                "notification" -> {
                    Log.i(TAG, "Incoming push notification for channel: ${msg.channelID}")
                    // Send ACK immediately
                    val channelId = msg.channelID
                    val version = msg.version
                    if (channelId != null && version != null) {
                        val ackMsg = """{"messageType":"ack","updates":[{"channelID":"$channelId","version":"$version"}]}"""
                        ws.send(ackMsg)
                    }

                    // Process notification
                    handleNotification(msg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling autopush message: $text", e)
        }
    }

    private suspend fun handleNotification(msg: AutopushMessage) {
        val channelId = msg.channelID ?: return
        val rawData = msg.data ?: return
        val account = accountManager.getAccountByChannelId(channelId) ?: run {
            Log.w(TAG, "Account not found for channelId: $channelId")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PolyTwi:PushProcessing"
        )?.apply {
            acquire(5000L) // Safety timeout: auto-release after at most 5 seconds
        }

        try {
            val privateKeyPkcs8 = account.privateKeyPkcs8Base64 ?: return
            val localPrivateKey = WebPushCryptoManager.decodePrivateKeyFromPkcs8(privateKeyPkcs8)
            val localPublicKeyBytes = Base64.getUrlDecoder().decode(account.p256dhBase64)
            val authSecret = Base64.getUrlDecoder().decode(account.authBase64)

            val encryptedPayload = Base64.getUrlDecoder().decode(rawData)
            fun getHeader(name: String): String? {
                val target = name.replace("-", "").replace("_", "").lowercase()
                return msg.headers?.entries?.firstOrNull {
                    it.key.replace("-", "").replace("_", "").lowercase() == target
                }?.value
            }

            val encoding = getHeader("contentencoding") ?: getHeader("encoding")
            val encryptionHeader = getHeader("encryption")
            val cryptoKeyHeader = getHeader("cryptokey")

            Log.d(TAG, "Notification received for ${account.name}. Processing decryption...")

            val decryptedBytes = Rfc8291Decryptor.decrypt(
                encryptedPayload = encryptedPayload,
                encoding = encoding,
                localPrivateKey = localPrivateKey,
                localPublicKeyBytes = localPublicKeyBytes,
                authSecret = authSecret,
                encryptionHeader = encryptionHeader,
                cryptoKeyHeader = cryptoKeyHeader
            )

            val decryptedJson = String(decryptedBytes, Charsets.UTF_8)
            Log.i(TAG, "Notification successfully decrypted for ${account.name}")

            val notificationPayload = json.decodeFromString<TwitterNotificationPayload>(decryptedJson)
            notificationDispatcher.showNotification(
                accountId = account.id,
                accountName = account.name,
                payload = notificationPayload
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt notification for account ${account.name}", e)
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (ignored: Exception) {}
        }
    }

    suspend fun registerChannel(appServerKeyBase64Url: String): Triple<String, String, String>? {
        if (webSocket == null || !isConnected) {
            start()
            // Wait up to 5 seconds for connection
            var waited = 0L
            while (!isConnected && waited < 5000L) {
                delay(100L)
                waited += 100L
            }
        }
        val ws = webSocket
        if (ws == null || !isConnected) {
            return null
        }

        val channelId = UUID.randomUUID().toString()
        return kotlinx.coroutines.withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { cont ->
                val regJson = """{"messageType":"register","channelID":"$channelId","key":"$appServerKeyBase64Url"}"""

                pendingRegistrations[channelId] = { endpoint ->
                    if (cont.isActive) {
                        if (endpoint != null) {
                            cont.resume(Triple(channelId, endpoint, appServerKeyBase64Url))
                        } else {
                            cont.resume(null)
                        }
                    }
                }

                cont.invokeOnCancellation {
                    pendingRegistrations.remove(channelId)
                }

                val sent = ws.send(regJson)
                if (!sent) {
                    pendingRegistrations.remove(channelId)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    fun unregisterChannel(channelId: String) {
        val ws = webSocket ?: return
        val unregJson = """{"messageType":"unregister","channelID":"$channelId"}"""
        ws.send(unregJson)
    }

    private fun handleUaidExpired() {
        Log.i(TAG, "UAID expired. Resetting push endpoints across all accounts so WebView will re-subscribe.")
        val accounts = accountManager.accounts.value
        accounts.forEach { account ->
            if (account.channelId != null || account.endpoint != null) {
                accountManager.updateAccount(account.copy(channelId = null, endpoint = null))
            }
        }
    }
}
