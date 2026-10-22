package net.kgtkr.polytwi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import net.kgtkr.polytwi.injector.CpftReleaseInfo
import net.kgtkr.polytwi.model.Account
import net.kgtkr.polytwi.ui.AccountTabBar
import net.kgtkr.polytwi.ui.CpftSettingsDialog
import net.kgtkr.polytwi.ui.CpftUpdateDialog

class MainActivity : ComponentActivity() {
    private val app by lazy { application as PolyTwiApp }

    // File chooser callback for <input type="file">
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val clipData = result.data?.clipData
            val dataUri = result.data?.data
            val results = when {
                clipData != null -> {
                    (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }.toTypedArray()
                }
                dataUri != null -> arrayOf(dataUri)
                else -> null
            }
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // Handle back button for WebView navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val activeId = app.accountManager.activeAccountId.value
                val account = app.accountManager.accounts.value.find { it.id == activeId }
                if (account != null) {
                    val activeWebView = app.webViewPool.getOrCreateWebView(this@MainActivity, account, createChromeClient())
                    if (activeWebView.canGoBack()) {
                        activeWebView.goBack()
                        return
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        handleIntent(intent)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private val pendingDeepLinkUrl = mutableStateOf<String?>(null)

    private fun handleIntent(intent: Intent?) {
        val accountId = intent?.getStringExtra("EXTRA_ACCOUNT_ID")
        val targetUrl = intent?.getStringExtra("EXTRA_TARGET_URL")

        if (accountId != null) {
            app.accountManager.setActiveAccount(accountId)
            val account = app.accountManager.getAccountById(accountId)
            if (account != null && targetUrl != null) {
                val resolvedUrl = resolveTargetUrl(targetUrl)
                Log.d("MainActivity", "Opening notification URL: $resolvedUrl for account ${account.name}")
                val webView = app.webViewPool.getOrCreateWebView(this@MainActivity, account, createChromeClient())
                webView.loadUrl(resolvedUrl)
            }
            return
        }

        // Deep link from external apps or browser
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val deepLinkUrl = intent.dataString
            if (!deepLinkUrl.isNullOrBlank()) {
                val accounts = app.accountManager.accounts.value
                if (accounts.size <= 1) {
                    val targetAccount = accounts.firstOrNull() ?: app.accountManager.activeAccountId.value?.let { app.accountManager.getAccountById(it) }
                    if (targetAccount != null) {
                        app.accountManager.setActiveAccount(targetAccount.id)
                        val webView = app.webViewPool.getOrCreateWebView(this@MainActivity, targetAccount, createChromeClient())
                        webView.loadUrl(deepLinkUrl)
                    }
                } else {
                    // Prompt user which account to open with
                    pendingDeepLinkUrl.value = deepLinkUrl
                }
            }
        }
    }

    private fun resolveTargetUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://x.com$trimmed"
            else -> "https://x.com/$trimmed"
        }
    }

    private fun copyCurrentUrl(activeAccountId: String?, accounts: List<Account>) {
        val account = accounts.find { it.id == activeAccountId } ?: return
        val webView = app.webViewPool.getOrCreateWebView(this, account, createChromeClient())
        val currentUrl = webView.url ?: "https://x.com"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("URL", currentUrl)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this, "Copied URL to clipboard:\n$currentUrl", Toast.LENGTH_SHORT).show()
    }

    private fun reloadCurrentPage(activeAccountId: String?, accounts: List<Account>) {
        val account = accounts.find { it.id == activeAccountId } ?: return
        val webView = app.webViewPool.getOrCreateWebView(this, account, createChromeClient())
        webView.reload()
        Toast.makeText(this, "Page reloaded", Toast.LENGTH_SHORT).show()
    }

    private fun createChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.d(
                        "WebViewConsole",
                        "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    )
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }
        }
    }

    @Composable
    private fun MainScreen() {
        val accounts by app.accountManager.accounts.collectAsState()
        val activeAccountId by app.accountManager.activeAccountId.collectAsState()
        val unreadAccountIds by app.accountManager.unreadAccountIds.collectAsState()

        var showAddDialog by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf<Account?>(null) }
        var showDeleteDialog by remember { mutableStateOf<Account?>(null) }
        var showCpftSettingsDialog by remember { mutableStateOf(false) }
        var showNavigateDialog by remember { mutableStateOf(false) }
        var pendingCpftUpdate by remember { mutableStateOf<CpftReleaseInfo?>(null) }
        var isApplyingCpftUpdate by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current
        val pendingDeepLink by pendingDeepLinkUrl

        LaunchedEffect(Unit) {
            val update = app.controlPanelInjector.checkForUpdates(force = false)
            if (update != null) {
                pendingCpftUpdate = update
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AccountTabBar(
                accounts = accounts,
                activeAccountId = activeAccountId,
                unreadAccountIds = unreadAccountIds,
                onSelectAccount = { id -> app.accountManager.setActiveAccount(id) },
                onAddAccount = { showAddDialog = true },
                onRenameAccount = { id -> showRenameDialog = accounts.find { it.id == id } },
                onDeleteAccount = { id -> showDeleteDialog = accounts.find { it.id == id } },
                onCopyUrl = { copyCurrentUrl(activeAccountId, accounts) },
                onNavigateToUrl = { showNavigateDialog = true },
                onReload = { reloadCurrentPage(activeAccountId, accounts) },
                onOpenCpftSettings = { showCpftSettingsDialog = true }
            )

            // WebView Pool Container
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FrameLayout(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { container ->
                        // Synchronize child views with WebViewPool
                        accounts.forEach { account ->
                            val wv = app.webViewPool.getOrCreateWebView(this@MainActivity, account, createChromeClient())
                            if (wv.parent !== container) {
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                container.addView(wv)
                            }
                            wv.visibility = if (account.id == activeAccountId) View.VISIBLE else View.GONE
                        }
                    }
                )
            }
        }

        // Control Panel for Twitter Settings Dialog
        if (showCpftSettingsDialog) {
            CpftSettingsDialog(
                controlPanelInjector = app.controlPanelInjector,
                cpftConfigBridge = app.cpftConfigBridge,
                onDismiss = { showCpftSettingsDialog = false }
            )
        }

        // CPFT Update Confirmation Dialog
        pendingCpftUpdate?.let { release ->
            CpftUpdateDialog(
                releaseInfo = release,
                currentVersion = app.controlPanelInjector.getCurrentVersion(),
                isUpdating = isApplyingCpftUpdate,
                onConfirmUpdate = {
                    isApplyingCpftUpdate = true
                    coroutineScope.launch {
                        val success = app.controlPanelInjector.installRelease(release.tagName)
                        isApplyingCpftUpdate = false
                        if (success) {
                            Toast.makeText(
                                context,
                                "Updated Control Panel for Twitter to ${release.tagName}",
                                Toast.LENGTH_SHORT
                            ).show()
                            pendingCpftUpdate = null
                        } else {
                            Toast.makeText(
                                context,
                                "Failed to download update",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onDismiss = {
                    if (!isApplyingCpftUpdate) pendingCpftUpdate = null
                }
            )
        }

        // Add Account Dialog
        if (showAddDialog) {
            var accountName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Account") },
                text = {
                    OutlinedTextField(
                        value = accountName,
                        onValueChange = { accountName = it },
                        label = { Text("Account Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (accountName.isNotBlank()) {
                                app.accountManager.addAccount(accountName.trim())
                                showAddDialog = false
                            }
                        }
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Rename Dialog
        showRenameDialog?.let { target ->
            var newName by remember { mutableStateOf(target.name) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = null },
                title = { Text("Rename Account") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                app.accountManager.updateAccountName(target.id, newName.trim())
                                showRenameDialog = null
                            }
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete Dialog
        showDeleteDialog?.let { target ->
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text("Delete Account") },
                text = { Text("Are you sure you want to delete ${target.name}?") },
                confirmButton = {
                    Button(
                        onClick = {
                            app.webViewPool.removeWebView(target.id)
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                                ProfileStore.getInstance().deleteProfile(target.profileName)
                            }
                            app.accountManager.removeAccount(target.id)
                            showDeleteDialog = null
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Navigate to URL Dialog
        if (showNavigateDialog) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            val defaultText = if (clipText.startsWith("http://") || clipText.startsWith("https://")) clipText else ""
            var inputUrl by remember { mutableStateOf(defaultText) }

            AlertDialog(
                onDismissRequest = { showNavigateDialog = false },
                title = { Text("Go to URL") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            label = { Text("URL or path (e.g. /i/bookmarks)") },
                            placeholder = { Text("https://x.com/...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (clipText.isNotBlank() && clipText != inputUrl) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { inputUrl = clipText }) {
                                Text("Paste from clipboard")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val target = inputUrl.trim()
                            if (target.isNotBlank()) {
                                val resolved = when {
                                    target.startsWith("http://") || target.startsWith("https://") -> target
                                    target.startsWith("/") -> "https://x.com$target"
                                    else -> "https://x.com/$target"
                                }
                                showNavigateDialog = false
                                val account = accounts.find { it.id == activeAccountId }
                                if (account != null) {
                                    val webView = app.webViewPool.getOrCreateWebView(this@MainActivity, account, createChromeClient())
                                    webView.loadUrl(resolved)
                                }
                            }
                        }
                    ) {
                        Text("Go")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNavigateDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Deep Link: Select Account Dialog
        pendingDeepLink?.let { targetUrl ->
            AlertDialog(
                onDismissRequest = { pendingDeepLinkUrl.value = null },
                title = { Text("Select Account for Link") },
                text = {
                    Column {
                        Text(
                            text = targetUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Which account would you like to open this link with?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        accounts.forEach { account ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pendingDeepLinkUrl.value = null
                                        app.accountManager.setActiveAccount(account.id)
                                        val webView = app.webViewPool.getOrCreateWebView(
                                            this@MainActivity,
                                            account,
                                            createChromeClient()
                                        )
                                        webView.loadUrl(targetUrl)
                                    }
                                    .padding(vertical = 4.dp),
                                shape = MaterialTheme.shapes.small,
                                color = if (account.id == activeAccountId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = account.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (account.id == activeAccountId) {
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = "Current account",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { pendingDeepLinkUrl.value = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            app.webViewPool.destroyAll()
        }
    }
}
