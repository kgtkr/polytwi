package net.kgtkr.polytwi

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.kgtkr.polytwi.account.AccountManager
import net.kgtkr.polytwi.injector.ControlPanelInjector
import net.kgtkr.polytwi.injector.CpftConfigBridge
import net.kgtkr.polytwi.push.NotificationDispatcher
import net.kgtkr.polytwi.push.PushBridgeClient
import net.kgtkr.polytwi.transfer.SessionTransferManager
import net.kgtkr.polytwi.webview.WebViewPool
import okhttp3.OkHttpClient

class PolyTwiApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val okHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    val accountManager by lazy {
        AccountManager(this)
    }

    val notificationDispatcher by lazy {
        NotificationDispatcher(this, okHttpClient, accountManager)
    }

    val cpftConfigBridge by lazy {
        CpftConfigBridge(this)
    }

    val controlPanelInjector by lazy {
        ControlPanelInjector(this, okHttpClient, appScope)
    }

    val pushBridgeClient by lazy {
        PushBridgeClient(this, okHttpClient, accountManager, notificationDispatcher, appScope)
    }

    val webViewPool by lazy {
        WebViewPool(accountManager, controlPanelInjector, cpftConfigBridge, pushBridgeClient, notificationDispatcher, appScope)
    }

    val sessionTransferManager by lazy {
        SessionTransferManager(
            context = this,
            accountManager = accountManager,
            cpftConfigBridge = cpftConfigBridge,
            pushBridgeClient = pushBridgeClient,
            webViewPool = webViewPool
        )
    }

    override fun onCreate() {
        super.onCreate()

        // Sync existing push channels and FCM token with autopush-fcm-bridge on startup
        pushBridgeClient.syncChannels()

        // Automatically sync notification channels on startup and whenever accounts are added/renamed/deleted
        appScope.launch {
            accountManager.accounts.collect { accounts ->
                notificationDispatcher.syncChannels(accounts)
            }
        }
    }
}
