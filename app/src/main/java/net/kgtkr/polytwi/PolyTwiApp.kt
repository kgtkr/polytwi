package net.kgtkr.polytwi

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.kgtkr.polytwi.account.AccountManager
import net.kgtkr.polytwi.injector.ControlPanelInjector
import net.kgtkr.polytwi.injector.CpftConfigBridge
import net.kgtkr.polytwi.push.AutopushClient
import net.kgtkr.polytwi.push.NotificationDispatcher
import net.kgtkr.polytwi.push.PushForegroundService
import net.kgtkr.polytwi.webview.WebViewPool
import okhttp3.OkHttpClient

import kotlinx.coroutines.launch

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

    val autopushClient by lazy {
        AutopushClient(this, accountManager, notificationDispatcher, appScope)
    }

    val webViewPool by lazy {
        WebViewPool(accountManager, controlPanelInjector, cpftConfigBridge, autopushClient, notificationDispatcher, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        PushForegroundService.start(this)

        // Automatically sync notification channels on startup and whenever accounts are added/renamed/deleted
        appScope.launch {
            accountManager.accounts.collect { accounts ->
                notificationDispatcher.syncChannels(accounts)
            }
        }
    }
}
