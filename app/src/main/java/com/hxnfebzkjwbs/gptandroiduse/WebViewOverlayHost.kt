package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WebViewOverlayHost {
    private val lock = Any()
    private var windowManager: WindowManager? = null
    private var hostedWebView: WebView? = null
    private var overlayAttached = false
    private var backgroundDetached = false
    private var captureHidden = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun moveToBackground(context: Context, webView: WebView): Boolean =
        runOnMainBlocking(1_000L) {
            synchronized(lock) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                hostedWebView = webView

                val useOverlay =
                    OverlaySettings.isCompatibilityOverlayEnabled(context) &&
                        Settings.canDrawOverlays(context)

                if (useOverlay) {
                    val wm = context.applicationContext
                        .getSystemService(WindowManager::class.java)

                    return@synchronized runCatching {
                        if (overlayAttached) {
                            runCatching {
                                windowManager?.removeViewImmediate(webView)
                            }
                        }

                        webView.importantForAccessibility =
                            WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        wm.addView(webView, createLayoutParams(context))

                        windowManager = wm
                        overlayAttached = true
                        backgroundDetached = false
                        captureHidden = false

                        webView.visibility = WebView.VISIBLE
                        webView.resumeTimers()
                        webView.onResume()
                        AppLog.add("BACKGROUND_MODE", "overlay")
                        true
                    }.getOrElse {
                        overlayAttached = false
                        backgroundDetached = true
                        AppLog.add(
                            "BACKGROUND_MODE",
                            "overlay failed, falling back to detached: " +
                                (it.message ?: it.javaClass.simpleName)
                        )
                        prepareDetached(webView)
                        true
                    }
                }

                if (overlayAttached) {
                    runCatching {
                        windowManager?.removeViewImmediate(webView)
                    }
                    overlayAttached = false
                }

                backgroundDetached = true
                captureHidden = false
                prepareDetached(webView)
                AppLog.add("BACKGROUND_MODE", "detached")
                true
            }
        }

    private fun prepareDetached(webView: WebView) {
        webView.importantForAccessibility =
            WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webView.visibility = WebView.VISIBLE
        webView.resumeTimers()
        webView.onResume()
    }

    fun setHiddenForScreenshot(
        context: Context,
        hidden: Boolean,
        timeoutMs: Long = 1_000L
    ): Boolean = runOnMainBlocking(timeoutMs) {
        synchronized(lock) {
            if (!overlayAttached) return@synchronized false
            val webView = hostedWebView ?: return@synchronized false
            val wm = windowManager ?: return@synchronized false
            val params =
                webView.layoutParams as? WindowManager.LayoutParams
                    ?: return@synchronized false

            val newAlpha =
                if (hidden) 0f else OverlaySettings.getOpacity(context)

            params.alpha = newAlpha
            runCatching {
                wm.updateViewLayout(webView, params)
                captureHidden = hidden
                true
            }.getOrElse {
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
                            if (overlayAttached) "overlay" else
                                if (backgroundDetached) "detached" else "activity"
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
        synchronized(lock) {
            if (!overlayAttached || captureHidden) return
            val webView = hostedWebView ?: return
            val wm = windowManager ?: return
            val params =
                webView.layoutParams as? WindowManager.LayoutParams ?: return
            params.alpha = OverlaySettings.getOpacity(context)
            runCatching { wm.updateViewLayout(webView, params) }
        }
    }

    fun restore(webView: WebView, container: FrameLayout) {
        synchronized(lock) {
            if (overlayAttached && hostedWebView === webView) {
                runCatching { windowManager?.removeViewImmediate(webView) }
            } else {
                (webView.parent as? ViewGroup)?.let { parent ->
                    if (parent !== container) parent.removeView(webView)
                }
            }

            overlayAttached = false
            backgroundDetached = false
            captureHidden = false
            hostedWebView = webView

            if (webView.parent !== container) {
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
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
            AppLog.add("BACKGROUND_MODE", "activity")
        }
    }

    fun release(webView: WebView) {
        synchronized(lock) {
            if (overlayAttached && hostedWebView === webView) {
                runCatching { windowManager?.removeViewImmediate(webView) }
            }
            if (hostedWebView === webView) hostedWebView = null
            overlayAttached = false
            backgroundDetached = false
            captureHidden = false
        }
    }

    private fun createLayoutParams(
        context: Context
    ): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = OverlaySettings.getOpacity(context)
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
}
