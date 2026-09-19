package com.hxnfebzkjwbs.gptandroiduse

import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
            }
        )

        configureWebView()
        pageAdbBridge.setEnabled(true)
        binding.bridgeStatusText.text = "Bridge: ON"
        configureOverlayOpacity()
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
                if (binding.chatWebView.canGoBack()) {
                    binding.chatWebView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            binding.chatWebView.loadUrl(CHATGPT_URL)
        } else {
            binding.chatWebView.restoreState(savedInstanceState)
        }
    }

    private fun configureOverlayOpacity() {
        val initial = OverlaySettings.getOpacityPercent(this)
        val overlayEnabled =
            OverlaySettings.isCompatibilityOverlayEnabled(this)

        binding.compatibilityOverlaySwitch.isChecked = overlayEnabled
        binding.overlayOpacitySeek.isEnabled = overlayEnabled
        binding.overlayOpacityLabel.text =
            if (overlayEnabled) "Overlay " + initial + "%"
            else "Overlay 已关闭"
        binding.overlayOpacitySeek.progress = initial - 1

        binding.compatibilityOverlaySwitch.setOnCheckedChangeListener { _, checked ->
            OverlaySettings.setCompatibilityOverlayEnabled(
                this@MainActivity,
                checked
            )
            binding.overlayOpacitySeek.isEnabled = checked
            binding.overlayOpacityLabel.text =
                if (checked) {
                    "Overlay " +
                        OverlaySettings.getOpacityPercent(this@MainActivity) +
                        "%"
                } else {
                    "Overlay 已关闭"
                }

            if (checked) {
                ensureOverlayCapability()
            }
            AppLog.add(
                "BACKGROUND_MODE",
                "compatibility_overlay=" + checked
            )
        }

        binding.overlayOpacitySeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val percent = progress + 1
                    if (
                        OverlaySettings.isCompatibilityOverlayEnabled(
                            this@MainActivity
                        )
                    ) {
                        binding.overlayOpacityLabel.text =
                            "Overlay " + percent + "%"
                    }
                    if (!fromUser) return

                    OverlaySettings.setOpacityPercent(this@MainActivity, percent)
                    WebViewOverlayHost.updateOpacity(this@MainActivity)
                    if (Settings.canDrawOverlays(this@MainActivity)) {
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            Intent(
                                this@MainActivity,
                                OverlayKeepAliveService::class.java
                            ).setAction(OverlayKeepAliveService.ACTION_UPDATE_OPACITY)
                        )
                    }
                    AppLog.add("OVERLAY", "opacity=" + percent + "%")
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun ensureOverlayCapability() {
        if (!OverlaySettings.isCompatibilityOverlayEnabled(this)) {
            startOverlayService()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
            return
        }
        if (overlayPromptShown) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle("Allow display over other apps")
            .setMessage(
                "This permission keeps the same ChatGPT WebView active while another app, such as WeChat, is in front. " +
                    "The overlay does not accept touch input."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + packageName)
                    )
                )
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun startOverlayService() {
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
        startOverlayService()
    }

    override fun onStop() {
        if (!isFinishing && ::binding.isInitialized) {
            startOverlayService()
            WebViewOverlayHost.moveToBackground(
                applicationContext,
                binding.chatWebView
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
