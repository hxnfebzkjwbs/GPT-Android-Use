package com.hxnfebzkjwbs.gptandroiduse

import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
    private var readinessGeneration = 0

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        pageAdbBridge = WebAdbBridge(
            applicationContext,
            binding.chatWebView,
            onStatus = { status ->
                AppLog.add("STATUS", status)
                binding.bridgeStatusText.text = status
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
                binding.bridgeStatusText.text = "AI：已回复"
            },
            onNativeStep = { step ->
                taskRunning = true
                updateComposerEnabled()
                binding.nativeStopButton.isEnabled = true
                addNativeMessage(
                    "step",
                    "步骤：" + step
                )
            },
            onNativeTaskStatus = { status ->
                runOnUiThread {
                    binding.taskStatusText.text = "任务 · " + status
                    taskRunning = status == "进行中"
                    if (!taskRunning) {
                        binding.nativeStopButton.isEnabled = false
                    }
                    updateComposerEnabled()
                }
            },
            onNativeSendState = { state, detail ->
                when (state) {
                    "sent" -> {
                        taskRunning = true
                        updateComposerEnabled()
                        binding.nativeStopButton.isEnabled = true
                        binding.bridgeStatusText.text = "AI：等待回复…"
                    }
                    "failed" -> {
                        taskRunning = false
                        updateComposerEnabled()
                        binding.nativeStopButton.isEnabled = false
                        binding.taskStatusText.text = "任务 · 失败"
                        binding.bridgeStatusText.text =
                            "发送失败：" + detail
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
            showSettingsDialog()
        }

        binding.nativeSendButton.setOnClickListener {
            sendNativeChatMessage()
        }

        binding.nativeStopButton.setOnClickListener {
            binding.nativeStopButton.isEnabled = false
            taskRunning = false
            binding.taskStatusText.text = "任务 · 失败"
            updateComposerEnabled()
            binding.bridgeStatusText.text = "正在停止…"
            pageAdbBridge.stopAutomation { result ->
                runOnUiThread {
                    binding.bridgeStatusText.text = "已停止"
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

        addNativeMessage(
            "system",
            "原生聊天界面已启动。Web 页面仅作为登录、传输和调试层。"
        )
    }

    private fun applyChatMode() {
        binding.nativeChatRoot.visibility =
            if (webDebugMode) View.GONE else View.VISIBLE
        binding.bridgeStatusText.text =
            if (webDebugMode) "模式：Web 调试" else "模式：原生聊天"
    }

    private fun sendNativeChatMessage() {
        if (!adbReady || !bridgeReady || taskRunning) {
            binding.bridgeStatusText.text =
                "ADB / Bridge 未就绪或已有任务进行中"
            return
        }

        val text = binding.nativeMessageInput.text
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) return

        binding.nativeMessageInput.setText("")
        taskRunning = true
        binding.taskStatusText.text = "任务 · 进行中"
        updateComposerEnabled()
        binding.nativeStopButton.isEnabled = true
        addNativeMessage("user", text)
        binding.bridgeStatusText.text = "AI：发送中…"

        pageAdbBridge.installForCurrentPage()
        pageAdbBridge.sendNativeMessage(text) { result ->
            runOnUiThread {
                when (result) {
                    "queued" -> {
                        binding.bridgeStatusText.text =
                            "AI：正在提交到 Web 传输层…"
                    }
                    "sent" -> {
                        binding.nativeSendButton.isEnabled = true
                        binding.bridgeStatusText.text = "AI：等待回复…"
                    }
                    "not-ready", "composer-missing" -> {
                        taskRunning = false
                        updateComposerEnabled()
                        binding.nativeStopButton.isEnabled = false
                        binding.taskStatusText.text = "任务 · 失败"
                        binding.bridgeStatusText.text =
                            "Web 传输层尚未就绪，请稍后重试或打开 Web 调试"
                        addNativeMessage(
                            "system",
                            "发送失败：ChatGPT Web 传输层尚未就绪。"
                        )
                    }
                    else -> {
                        taskRunning = false
                        updateComposerEnabled()
                        binding.nativeStopButton.isEnabled = false
                        binding.taskStatusText.text = "任务 · 失败"
                        binding.bridgeStatusText.text =
                            "发送失败：" + result
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
        binding.adbReadyText.text = "ADB · 检查中"
        binding.bridgeReadyText.text = "Bridge · 检查中"
        binding.taskStatusText.text = "任务 · 空闲"
        binding.bridgeStatusText.text = "启动检查中…"
        updateComposerEnabled()
    }

    private fun updateComposerEnabled() {
        val ready = adbReady && bridgeReady && !taskRunning
        binding.nativeMessageInput.isEnabled = ready
        binding.nativeSendButton.isEnabled = ready
        binding.nativeMessageInput.hint =
            if (ready) "输入消息"
            else if (!adbReady || !bridgeReady) "等待 ADB / Bridge 就绪…"
            else "当前任务进行中…"
    }

    private fun checkAdbReadiness() {
        val generation = ++readinessGeneration
        adbReady = false
        binding.adbReadyText.text = "ADB · 检查中"
        updateComposerEnabled()

        pageAdbBridge.checkAdbReady { ok, detail ->
            runOnUiThread {
                if (generation != readinessGeneration) return@runOnUiThread
                adbReady = ok
                binding.adbReadyText.text =
                    if (ok) "ADB · Ready" else "ADB · 未就绪"
                if (!ok) {
                    binding.bridgeStatusText.text =
                        "ADB 未就绪：" + detail
                } else if (bridgeReady) {
                    binding.bridgeStatusText.text =
                        "已就绪，可以发送消息"
                }
                updateComposerEnabled()
            }
        }
    }

    private fun checkBridgeReadiness() {
        val generation = readinessGeneration
        bridgeReady = false
        binding.bridgeReadyText.text = "Bridge · 检查中"
        updateComposerEnabled()

        pageAdbBridge.checkBridgeReady { ok, detail ->
            runOnUiThread {
                if (generation != readinessGeneration) return@runOnUiThread
                bridgeReady = ok
                binding.bridgeReadyText.text =
                    if (ok) "Bridge · Ready" else "Bridge · 未就绪"
                binding.bridgeStatusText.text =
                    if (ok && adbReady) {
                        "已就绪，可以发送消息"
                    } else if (!ok) {
                        "Bridge 未就绪：" + detail
                    } else {
                        "等待 ADB…"
                    }
                updateComposerEnabled()
            }
        }
    }

    private fun recheckReadiness() {
        readinessGeneration += 1
        adbReady = false
        bridgeReady = false
        binding.adbReadyText.text = "ADB · 检查中"
        binding.bridgeReadyText.text = "Bridge · 检查中"
        binding.bridgeStatusText.text = "重新检查中…"
        updateComposerEnabled()
        checkAdbReadiness()
        binding.chatWebView.postDelayed({
            checkBridgeReadiness()
        }, 350)
    }

    private fun showSettingsDialog() {
        val version =
            runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty()

        val items = arrayOf(
            if (webDebugMode) "返回原生聊天" else "Web 调试",
            "重新检查 ADB / Bridge",
            "ADB 设置",
            if (TextInputAccessibilityService.isConnected()) {
                "无障碍输入（已启用）"
            } else {
                "无障碍输入"
            },
            "日志",
            "重新加载 Web",
            "ADB 测试",
            "Bridge 测试",
            "版本 " + version
        )

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> {
                        webDebugMode = !webDebugMode
                        applyChatMode()
                    }
                    1 -> recheckReadiness()
                    2 -> startActivity(
                        Intent(this, AdbSetupActivity::class.java)
                    )
                    3 -> startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                    4 -> startActivity(
                        Intent(this, LogActivity::class.java)
                    )
                    5 -> {
                        bridgeReady = false
                        updateComposerEnabled()
                        binding.chatWebView.reload()
                    }
                    6 -> {
                        binding.bridgeStatusText.text = "ADB 测试中…"
                        pageAdbBridge.sendOneTapAdbRequest()
                    }
                    7 -> {
                        binding.bridgeStatusText.text = "Bridge 测试中…"
                        pageAdbBridge.runSelfTest()
                    }
                    8 -> Unit
                }
                dialog.dismiss()
            }
            .setNegativeButton("关闭", null)
            .show()
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
            pageAdbBridge.installForCurrentPage()
        }
        ensureOverlayCapability()
        startOverlayService()
    }

    override fun onStop() {
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
                pageAdbBridge.onTopLevelUrlChanged(url)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageAdbBridge.onTopLevelUrlChanged(url)
                pageAdbBridge.installForCurrentPage()
                binding.bridgeStatusText.text =
                    if (webDebugMode) "Web 调试已就绪"
                    else "正在检查 Bridge…"
                checkBridgeReadiness()
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
    }
}
