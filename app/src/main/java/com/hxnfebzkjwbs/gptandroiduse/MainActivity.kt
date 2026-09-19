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

        @Suppress("DEPRECATION")
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        binding.versionText.text = if (versionName.isBlank()) "v?" else "v$versionName"

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
            }
        )

        configureWebView()
        configureNativeChat()
        applyChatMode()
        pageAdbBridge.setEnabled(true)
        binding.bridgeStatusText.text = "Bridge: ON"
        ensureOverlayCapability()

        if (savedInstanceState == null) {
            pageAdbBridge.checkAdbOnStartup()
        }

        binding.adbRunButton.setOnClickListener {
            binding.bridgeStatusText.text = "ADB Run: sending request…"
            pageAdbBridge.installForCurrentPage()
            binding.chatWebView.postDelayed({
                pageAdbBridge.sendOneTapAdbRequest()
            }, 250)
        }

        binding.bridgeTestButton.setOnClickListener {
            binding.bridgeStatusText.text = "Bridge test: starting…"
            pageAdbBridge.installForCurrentPage()
            binding.chatWebView.postDelayed({
                pageAdbBridge.runSelfTest()
            }, 350)
        }

        binding.adbSetupButton.setOnClickListener {
            startActivity(Intent(this, AdbSetupActivity::class.java))
        }

        binding.logsButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.reloadButton.setOnClickListener {
            binding.chatWebView.reload()
        }

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
        binding.chatModeButton.setOnClickListener {
            webDebugMode = !webDebugMode
            applyChatMode()
        }

        binding.nativeSendButton.setOnClickListener {
            sendNativeChatMessage()
        }

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
        binding.chatModeButton.text =
            if (webDebugMode) "返回原生聊天" else "Web 调试"
        binding.bridgeStatusText.text =
            if (webDebugMode) "模式：Web 调试" else "模式：原生聊天"
    }

    private fun sendNativeChatMessage() {
        val text = binding.nativeMessageInput.text
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) return

        binding.nativeMessageInput.setText("")
        binding.nativeSendButton.isEnabled = false
        addNativeMessage("user", text)
        binding.bridgeStatusText.text = "AI：发送中…"

        pageAdbBridge.installForCurrentPage()
        pageAdbBridge.sendNativeMessage(text) { result ->
            runOnUiThread {
                binding.nativeSendButton.isEnabled = true
                when (result) {
                    "sent" -> {
                        binding.bridgeStatusText.text = "AI：等待回复…"
                    }
                    "not-ready", "composer-missing" -> {
                        binding.bridgeStatusText.text =
                            "Web 传输层尚未就绪，请稍后重试或打开 Web 调试"
                        addNativeMessage(
                            "system",
                            "发送失败：ChatGPT Web 传输层尚未就绪。"
                        )
                    }
                    else -> {
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

    private fun addNativeMessage(role: String, text: String) {
        if (!::binding.isInitialized || text.isBlank()) return

        runOnUiThread {
            val density = resources.displayMetrics.density
            val bubble = TextView(this).apply {
                this.text = text
                textSize = if (role == "system") 12f else 15f
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
                            "user" -> Color.rgb(55, 95, 210)
                            "system" -> Color.rgb(235, 235, 235)
                            else -> Color.rgb(245, 245, 245)
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
                    else "原生聊天已就绪"
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
