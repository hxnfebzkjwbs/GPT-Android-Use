package com.hxnfebzkjwbs.gptandroiduse

import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hxnfebzkjwbs.gptandroiduse.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pageAdbBridge: WebAdbBridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var overlayPromptShown = false
    private var webDebugMode = false
    private var adbReady = false
    private var bridgeReady = false
    private var taskRunning = false
    private var adbCheckInFlight = false
    private var bridgeCheckInFlight = false
    private var webPageLoading = false
    private var webPageLoaded = false
    private var bridgeReloadInFlight = false
    private var bridgeHydrationStartedAt = 0L
    private val readinessHandler = Handler(Looper.getMainLooper())
    private val readinessRetryRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (!adbReady) checkAdbReadiness()
        if (!bridgeReady) recoverBridgeByReload()
        scheduleReadinessRetry()
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val action =
                result.data?.getStringExtra(SettingsActivity.EXTRA_ACTION)
                    ?: return@registerForActivityResult
            when (action) {
                SettingsActivity.ACTION_WEB_DEBUG -> {
                    webDebugMode = true
                    applyChatMode()
                }
                SettingsActivity.ACTION_RECHECK -> {
                    adbReady = false
                    bridgeReady = false
                    updateConnectionStatus()
                    checkAdbReadiness()
                    recoverBridgeByReload()
                    scheduleReadinessRetry()
                }
                SettingsActivity.ACTION_RELOAD_WEB -> {
                    bridgeReady = false
                    webPageLoaded = false
                    bridgeReloadInFlight = true
                    updateConnectionStatus()
                    binding.chatWebView.reload()
                    scheduleReadinessRetry()
                }
                SettingsActivity.ACTION_ADB_TEST -> {
                    pageAdbBridge.sendOneTapAdbRequest()
                }
                SettingsActivity.ACTION_BRIDGE_TEST -> {
                    pageAdbBridge.runSelfTest()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(this, binding.root)
        supportActionBar?.hide()

        pageAdbBridge = WebAdbBridge(
            applicationContext,
            binding.chatWebView,
            onStatus = { status ->
                AppLog.add("STATUS", status)
            },
            requestCommandApproval = { command, reason, complete ->
                if (isFinishing || isDestroyed) {
                    complete(false)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Allow ADB command once?")
                        .setMessage(command + "\n\n" + reason)
                        .setPositiveButton("Allow once") { _, _ -> complete(true) }
                        .setNegativeButton("Deny") { _, _ -> complete(false) }
                        .setOnCancelListener { complete(false) }
                        .show()
                }
            },
            onNativeAssistantMessage = { text ->
                addNativeMessage("assistant", text)
            },
            onNativeStep = { step ->
                taskRunning = true
                updateComposerEnabled()
                updateConnectionStatus()
                addNativeMessage(
                    "step",
                    "步骤：" + step
                )
            },
            onNativeTaskStatus = { status ->
                runOnUiThread {
                    taskRunning = status == "进行中"
                    updateComposerEnabled()
                    updateConnectionStatus(status)
                }
            },
            onNativeSendState = { state, detail ->
                when (state) {
                    "sent" -> {
                        taskRunning = true
                        updateComposerEnabled()
                        updateConnectionStatus("进行中")
                    }
                    "failed" -> {
                        taskRunning = false
                        updateComposerEnabled()
                        updateConnectionStatus("失败")
                        addNativeMessage(
                            "system",
                            "发送失败：" + detail
                        )
                    }
                }
            }
        )

        configureWebView()
        configureNativeChat()
        applyChatMode()
        pageAdbBridge.setEnabled(true)
        setInitialReadiness()
        checkAdbReadiness()
        ensureOverlayCapability()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webDebugMode) {
                    webDebugMode = false
                    applyChatMode()
                    return
                }
                finish()
            }
        })

        if (savedInstanceState == null) {
            binding.chatWebView.loadUrl(CHATGPT_URL)
        } else {
            binding.chatWebView.restoreState(savedInstanceState)
        }
    }

    private fun configureNativeChat() {
        binding.settingsButton.setOnClickListener {
            openSettingsPage()
        }

        binding.nativeSendButton.setOnClickListener {
            sendNativeChatMessage()
        }

        binding.nativeStopButton.setOnClickListener {
            taskRunning = false
            updateComposerEnabled()
            updateConnectionStatus("失败")
            pageAdbBridge.stopAutomation { result ->
                runOnUiThread {
                    addNativeMessage(
                        "system",
                        "已停止当前生成与 ADB 自动化。" +
                            if (result.isBlank()) "" else " (" + result + ")"
                    )
                }
            }
        }

        binding.nativeStopButton.isEnabled = false

        binding.nativeMessageInput.setOnEditorActionListener { _, actionId, event ->
            val send =
                actionId == EditorInfo.IME_ACTION_SEND ||
                    (
                        event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN &&
                            !event.isShiftPressed
                    )
            if (send) {
                sendNativeChatMessage()
                true
            } else {
                false
            }
        }

    }

    private fun applyChatMode() {
        binding.nativeChatRoot.visibility =
            if (webDebugMode) View.GONE else View.VISIBLE
    }

    private fun sendNativeChatMessage() {
        if (!adbReady || !bridgeReady || taskRunning) {
            updateConnectionStatus()
            return
        }

        val text = binding.nativeMessageInput.text
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) return

        binding.nativeMessageInput.setText("")
        taskRunning = true
        updateComposerEnabled()
        updateConnectionStatus("进行中")
        addNativeMessage("user", text)

        pageAdbBridge.installForCurrentPage()
        pageAdbBridge.sendNativeMessage(text) { result ->
            runOnUiThread {
                when (result) {
                    "queued" -> Unit
                    "sent" -> Unit
                    "not-ready", "composer-missing" -> {
                        taskRunning = false
                        bridgeReady = false
                        updateComposerEnabled()
                        updateConnectionStatus("失败")
                        scheduleReadinessRetry()
                        addNativeMessage(
                            "system",
                            "发送失败：ChatGPT Web 传输层尚未就绪。"
                        )
                    }
                    else -> {
                        taskRunning = false
                        updateComposerEnabled()
                        updateConnectionStatus("失败")
                        addNativeMessage(
                            "system",
                            "发送失败：" + result
                        )
                    }
                }
            }
        }
    }

    private fun setInitialReadiness() {
        adbReady = false
        bridgeReady = false
        taskRunning = false
        updateComposerEnabled()
        updateConnectionStatus()
        scheduleReadinessRetry()
    }

    private fun updateComposerEnabled() {
        val ready = adbReady && bridgeReady && !taskRunning
        binding.nativeMessageInput.isEnabled = ready
        binding.nativeSendButton.isEnabled = ready
        binding.nativeStopButton.visibility =
            if (taskRunning) View.VISIBLE else View.GONE
        binding.nativeMessageInput.hint =
            if (ready) "消息"
            else if (taskRunning) "任务进行中…"
            else "连接中…"
    }

    private fun updateConnectionStatus(
        taskStatus: String? = null
    ) {
        val text = when {
            taskStatus == "失败" -> "失败"
            taskStatus == "成功" -> "成功"
            taskRunning || taskStatus == "进行中" -> "进行中"
            !adbReady || !bridgeReady -> "连接中…"
            else -> ""
        }

        binding.activityStatusText.text = text
        binding.activityStatusText.visibility =
            if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun checkAdbReadiness() {
        if (adbReady || adbCheckInFlight) return
        adbCheckInFlight = true

        pageAdbBridge.checkAdbReady { ok, _ ->
            runOnUiThread {
                adbCheckInFlight = false
                adbReady = ok
                updateComposerEnabled()
                updateConnectionStatus()
                if (!ok) scheduleReadinessRetry()
            }
        }
    }

    private fun checkBridgeReadinessAfterPageLoad() {
        if (bridgeReady || bridgeCheckInFlight) return
        if (webPageLoading || !webPageLoaded) return

        bridgeCheckInFlight = true
        pageAdbBridge.checkBridgeReady { ok, detail ->
            runOnUiThread {
                bridgeCheckInFlight = false
                bridgeReady = ok
                updateComposerEnabled()
                updateConnectionStatus()

                if (ok) {
                    AppLog.add(
                        "BRIDGE_READY",
                        "composer ready after " +
                            (SystemClock.elapsedRealtime() -
                                bridgeHydrationStartedAt) +
                            "ms"
                    )
                    return@runOnUiThread
                }

                val elapsed =
                    SystemClock.elapsedRealtime() -
                        bridgeHydrationStartedAt

                if (
                    bridgeHydrationStartedAt > 0L &&
                    elapsed < BRIDGE_HYDRATION_GRACE_MS
                ) {
                    AppLog.add(
                        "BRIDGE_WAIT",
                        "health=" + detail +
                            " elapsed_ms=" + elapsed +
                            "; waiting for ChatGPT hydration"
                    )
                    readinessHandler.postDelayed(
                        {
                            checkBridgeReadinessAfterPageLoad()
                        },
                        BRIDGE_HEALTH_POLL_MS
                    )
                } else {
                    AppLog.add(
                        "BRIDGE_WAIT",
                        "hydration timeout health=" + detail +
                            " elapsed_ms=" + elapsed
                    )
                    scheduleReadinessRetry()
                }
            }
        }
    }

    private fun recoverBridgeByReload() {
        if (bridgeReady) return
        if (webPageLoading || bridgeReloadInFlight) return

        if (webPageLoaded && bridgeHydrationStartedAt > 0L) {
            val elapsed =
                SystemClock.elapsedRealtime() -
                    bridgeHydrationStartedAt
            if (elapsed < BRIDGE_HYDRATION_GRACE_MS) {
                checkBridgeReadinessAfterPageLoad()
                return
            }
        }

        val currentUrl = binding.chatWebView.url.orEmpty()
        if (currentUrl.isBlank() || currentUrl == "about:blank") {
            return
        }

        bridgeReloadInFlight = true
        webPageLoaded = false
        bridgeHydrationStartedAt = 0L
        bridgeCheckInFlight = false
        AppLog.add(
            "BRIDGE_RECOVERY",
            "hydration timed out; reloading WebView and waiting for onPageFinished"
        )
        binding.chatWebView.reload()
    }

    private fun scheduleReadinessRetry() {
        readinessHandler.removeCallbacks(readinessRetryRunnable)
        if (adbReady && bridgeReady) return
        readinessHandler.postDelayed(
            readinessRetryRunnable,
            READINESS_RETRY_MS
        )
    }

    private fun openSettingsPage() {
        settingsLauncher.launch(
            Intent(this, SettingsActivity::class.java).apply {
                putExtra(
                    SettingsActivity.EXTRA_ADB_READY,
                    adbReady
                )
                putExtra(
                    SettingsActivity.EXTRA_BRIDGE_READY,
                    bridgeReady
                )
            }
        )
    }

    private fun addNativeMessage(role: String, text: String) {
        if (!::binding.isInitialized || text.isBlank()) return

        runOnUiThread {
            val density = resources.displayMetrics.density
            val bubble = TextView(this).apply {
                this.text = text
                maxWidth =
                    (resources.displayMetrics.widthPixels * 0.82f).toInt()
                setTextIsSelectable(true)
                textSize =
                    if (role == "system" || role == "step") 12.5f else 15.5f
                setTextColor(
                    if (role == "user") Color.WHITE
                    else Color.rgb(30, 30, 30)
                )
                setPadding(
                    (12 * density).toInt(),
                    (9 * density).toInt(),
                    (12 * density).toInt(),
                    (9 * density).toInt()
                )
                background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(
                        when (role) {
                            "user" -> Color.rgb(54, 92, 205)
                            "system" -> Color.rgb(232, 235, 240)
                            "step" -> Color.rgb(226, 237, 255)
                            else -> Color.WHITE
                        }
                    )
                }
            }

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity =
                    if (role == "user") Gravity.END else Gravity.START
                val margin = (6 * density).toInt()
                setMargins(
                    if (role == "user") (36 * density).toInt() else margin,
                    margin,
                    if (role == "user") margin else (36 * density).toInt(),
                    margin
                )
            }

            binding.nativeMessageList.addView(bubble, params)
            binding.nativeMessageScroll.post {
                binding.nativeMessageScroll.fullScroll(
                    View.FOCUS_DOWN
                )
            }
        }
    }

    private fun ensureOverlayCapability() {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
            return
        }

        if (overlayPromptShown) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle("需要“显示在其他应用上层”权限")
            .setMessage(
                "后台模式只使用一个按当前设备尺寸动态计算的微型窗口；" +
                    "ChatGPT WebView 自身仍保持当前设备的完整屏幕尺寸，" +
                    "不会再把网页压成小窗口。"
            )
            .setPositiveButton("打开设置") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + packageName)
                    )
                )
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayKeepAliveService::class.java)
        )
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            WebViewOverlayHost.restore(
                binding.chatWebView,
                binding.webViewContainer
            )
            binding.chatWebView.resumeTimers()
            if (webPageLoaded && !webPageLoading) {
                pageAdbBridge.installForCurrentPage()
            }
        }
        checkAdbReadiness()
        if (!bridgeReady) recoverBridgeByReload()
        scheduleReadinessRetry()
        ensureOverlayCapability()
        startOverlayService()
    }

    override fun onStop() {
        readinessHandler.removeCallbacks(readinessRetryRunnable)
        if (!isFinishing && ::binding.isInitialized) {
            startOverlayService()
            val backgroundAttached =
                WebViewOverlayHost.moveToBackground(
                    applicationContext,
                    binding.chatWebView
                )
            AppLog.add(
                "MICRO_OVERLAY",
                "onStop attached=" + backgroundAttached
            )
        }
        super.onStop()
    }

    private fun configureWebView() {
        val webView = binding.chatWebView
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = false
            offscreenPreRaster = true
        }

        webView.setRendererPriorityPolicy(
            WebView.RENDERER_PRIORITY_IMPORTANT,
            false
        )
        AppLog.add(
            "WEBVIEW",
            "renderer_priority=IMPORTANT waivedWhenNotVisible=false offscreenPreRaster=true"
        )

        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )

        webView.addJavascriptInterface(pageAdbBridge, "GPTAndroidUseNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (request.isForMainFrame) {
                    pageAdbBridge.onTopLevelUrlChanged(uri.toString())
                }
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    else -> {
                        openExternalBrowser(uri)
                        true
                    }
                }
            }

            override fun onPageStarted(
                view: WebView,
                url: String,
                favicon: android.graphics.Bitmap?
            ) {
                webPageLoading = true
                webPageLoaded = false
                bridgeHydrationStartedAt = 0L
                bridgeReady = false
                bridgeCheckInFlight = false
                pageAdbBridge.onTopLevelUrlChanged(url)
                updateComposerEnabled()
                updateConnectionStatus()
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                webPageLoading = false
                webPageLoaded = true
                bridgeReloadInFlight = false
                bridgeReady = false
                bridgeHydrationStartedAt =
                    SystemClock.elapsedRealtime()
                pageAdbBridge.onTopLevelUrlChanged(url)

                // Inject once after the document load completes. ChatGPT is a
                // SPA, so onPageFinished can occur before React has created the
                // composer. Give hydration time before considering a reload.
                pageAdbBridge.installForCurrentPage()
                readinessHandler.postDelayed(
                    {
                        checkBridgeReadinessAfterPageLoad()
                    },
                    BRIDGE_INITIAL_HEALTH_DELAY_MS
                )
                scheduleReadinessRetry()
                super.onPageFinished(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.pageProgress.progress = newProgress
                binding.pageProgress.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val chooserIntent = runCatching {
                    fileChooserParams?.createIntent()
                }.getOrNull() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                return runCatching {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                }.getOrElse {
                    this@MainActivity.filePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .addRequestHeader("Cookie", cookies.getCookie(url).orEmpty())
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType)
                    )
                getSystemService(DownloadManager::class.java).enqueue(request)
            }.onFailure {
                openExternalBrowser(Uri.parse(url))
            }
        }
    }

    private fun openExternalBrowser(uri: Uri) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.chatWebView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        readinessHandler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            WebViewOverlayHost.release(binding.chatWebView)
            pageAdbBridge.shutdown()
            stopService(Intent(this, OverlayKeepAliveService::class.java))
            CookieManager.getInstance().flush()
        }
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        super.onDestroy()
    }

    companion object {
        private const val CHATGPT_URL = "https://chatgpt.com/"
        private const val READINESS_RETRY_MS = 2_000L
        private const val BRIDGE_INITIAL_HEALTH_DELAY_MS = 750L
        private const val BRIDGE_HEALTH_POLL_MS = 750L
        private const val BRIDGE_HYDRATION_GRACE_MS = 10_000L
    }
}
