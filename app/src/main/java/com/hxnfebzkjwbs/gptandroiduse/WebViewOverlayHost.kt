package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WebViewOverlayHost {
    private val lock = Any()
    private var windowManager: WindowManager? = null
    private var hostedWebView: WebView? = null
    private var overlayRoot: FrameLayout? = null
    private var overlayAttached = false
    private var captureHidden = false
    private var backgroundWidth = 0
    private var backgroundHeight = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    fun moveToBackground(context: Context, webView: WebView): Boolean =
        runOnMainBlocking(1_200L) {
            synchronized(lock) {
                if (!Settings.canDrawOverlays(context)) {
                    AppLog.add(
                        "MICRO_OVERLAY",
                        "permission missing; cannot keep WebView attached"
                    )
                    return@synchronized false
                }

                val appContext = context.applicationContext
                val wm = appContext.getSystemService(WindowManager::class.java)
                val bounds = wm.currentWindowMetrics.bounds
                val screenWidth = bounds.width().coerceAtLeast(1)
                val screenHeight = bounds.height().coerceAtLeast(1)
                val density = appContext.resources.displayMetrics.density
                val hostSide =
                    (FLOATING_BUTTON_DP * density + 0.5f)
                        .toInt()
                        .coerceAtLeast(1)

                if (overlayAttached && hostedWebView === webView) {
                    resizeBackgroundWebView(
                        webView,
                        screenWidth,
                        screenHeight
                    )
                    webView.resumeTimers()
                    webView.onResume()
                    return@synchronized true
                }

                (webView.parent as? ViewGroup)?.removeView(webView)

                val root = FrameLayout(appContext).apply {
                    clipChildren = true
                    clipToPadding = true
                    importantForAccessibility =
                        FrameLayout.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    alpha = 1f
                }

                webView.importantForAccessibility =
                    WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                webView.visibility = WebView.VISIBLE
                webView.layoutParams = FrameLayout.LayoutParams(
                    screenWidth,
                    screenHeight
                )
                root.addView(webView)

                val bubble = TextView(appContext).apply {
                    text = "GPT"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = false
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(230, 38, 38, 38))
                    }
                    setOnClickListener {
                        AppLog.add("MICRO_OVERLAY", "floating button clicked")
                        runCatching {
                            appContext.startActivity(
                                Intent(appContext, MainActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                }
                            )
                        }.onFailure {
                            AppLog.add(
                                "MICRO_OVERLAY",
                                "foreground launch failed: " +
                                    (it.message ?: it.javaClass.simpleName)
                            )
                        }
                    }
                }

                root.addView(
                    bubble,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                return@synchronized runCatching {
                    wm.addView(
                        root,
                        createMicroWindowLayoutParams(hostSide)
                    )

                    windowManager = wm
                    hostedWebView = webView
                    overlayRoot = root
                    overlayAttached = true
                    captureHidden = false
                    backgroundWidth = screenWidth
                    backgroundHeight = screenHeight

                    webView.resumeTimers()
                    webView.onResume()
                    webView.requestLayout()
                    webView.invalidate()

                    AppLog.add(
                        "MICRO_OVERLAY",
                        "host=" + hostSide + "x" + hostSide +
                            " webview=" + screenWidth + "x" + screenHeight +
                            " button_dp=" + FLOATING_BUTTON_DP
                    )
                    true
                }.getOrElse {
                    root.removeView(webView)
                    overlayRoot = null
                    hostedWebView = webView
                    overlayAttached = false
                    AppLog.add(
                        "MICRO_OVERLAY",
                        "attach failed: " +
                            (it.message ?: it.javaClass.simpleName)
                    )
                    false
                }
            }
        }

    private fun resizeBackgroundWebView(
        webView: WebView,
        width: Int,
        height: Int
    ) {
        if (
            width == backgroundWidth &&
            height == backgroundHeight
        ) {
            return
        }

        webView.layoutParams = FrameLayout.LayoutParams(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1)
        )
        backgroundWidth = width.coerceAtLeast(1)
        backgroundHeight = height.coerceAtLeast(1)
        webView.requestLayout()
    }

    fun setHiddenForScreenshot(
        context: Context,
        hidden: Boolean,
        timeoutMs: Long = 1_000L
    ): Boolean = runOnMainBlocking(timeoutMs) {
        synchronized(lock) {
            if (!overlayAttached) return@synchronized false
            val root = overlayRoot ?: return@synchronized false
            val wm = windowManager ?: return@synchronized false
            val params =
                root.layoutParams as? WindowManager.LayoutParams
                    ?: return@synchronized false

            val newAlpha = if (hidden) 0f else 1f
            if (params.alpha == newAlpha && captureHidden == hidden) {
                return@synchronized true
            }

            params.alpha = newAlpha
            root.alpha = newAlpha

            runCatching {
                wm.updateViewLayout(root, params)
                captureHidden = hidden
                AppLog.add(
                    "MICRO_OVERLAY",
                    if (hidden) {
                        "hidden for screenshot"
                    } else {
                        "restored after screenshot"
                    }
                )
                true
            }.getOrElse {
                AppLog.add(
                    "MICRO_OVERLAY",
                    "alpha update failed: " +
                        (it.message ?: it.javaClass.simpleName)
                )
                false
            }
        }
    }

    fun keepAliveTick(): Boolean = runOnMainBlocking(900L) {
        synchronized(lock) {
            val webView = hostedWebView ?: return@synchronized false

            runCatching {
                webView.resumeTimers()
                webView.onResume()
                webView.evaluateJavascript(
                    "window.__gptAndroidUseBackgroundTick && " +
                        "window.__gptAndroidUseBackgroundTick();"
                ) { value ->
                    AppLog.add(
                        "JS_KEEPALIVE",
                        "callback=" + value.take(96) +
                            " mode=" +
                            if (overlayAttached) "micro-overlay"
                            else "activity"
                    )
                }
                true
            }.getOrElse {
                AppLog.add(
                    "WEBVIEW_KEEPALIVE",
                    "failed: " +
                        (it.message ?: it.javaClass.simpleName)
                )
                false
            }
        }
    }

    fun updateOpacity(context: Context) {
        // 0.9.2 uses a fixed near-invisible micro window. Kept as no-op
        // so older service/UI calls remain binary-compatible.
    }

    fun restore(webView: WebView, container: FrameLayout) {
        synchronized(lock) {
            if (overlayAttached && hostedWebView === webView) {
                val root = overlayRoot
                if (root != null) {
                    runCatching {
                        windowManager?.removeViewImmediate(root)
                    }
                    runCatching { root.removeView(webView) }
                }
            } else {
                (webView.parent as? ViewGroup)?.let { parent ->
                    if (parent !== container) {
                        parent.removeView(webView)
                    }
                }
            }

            overlayAttached = false
            captureHidden = false
            overlayRoot = null
            backgroundWidth = 0
            backgroundHeight = 0
            hostedWebView = webView

            if (webView.parent !== container) {
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else {
                webView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            webView.importantForAccessibility =
                WebView.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            webView.visibility = WebView.VISIBLE
            webView.resumeTimers()
            webView.onResume()
            webView.post {
                webView.requestLayout()
                webView.invalidate()
            }

            AppLog.add("MICRO_OVERLAY", "restored to activity")
        }
    }

    fun release(webView: WebView) {
        synchronized(lock) {
            if (overlayAttached && hostedWebView === webView) {
                overlayRoot?.let { root ->
                    runCatching {
                        windowManager?.removeViewImmediate(root)
                    }
                    runCatching { root.removeView(webView) }
                }
            }

            if (hostedWebView === webView) {
                hostedWebView = null
            }

            overlayAttached = false
            captureHidden = false
            overlayRoot = null
            backgroundWidth = 0
            backgroundHeight = 0
        }
    }

    private fun createMicroWindowLayoutParams(
        hostSide: Int
    ): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            hostSide,
            hostSide,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 0
            y = 0
            alpha = 1f
        }

    private fun runOnMainBlocking(
        timeoutMs: Long,
        action: () -> Boolean
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action()
        }

        val latch = CountDownLatch(1)
        var result = false

        mainHandler.post {
            try {
                result = action()
            } finally {
                latch.countDown()
            }
        }

        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && result
    }

    private const val FLOATING_BUTTON_DP = 48f
}
