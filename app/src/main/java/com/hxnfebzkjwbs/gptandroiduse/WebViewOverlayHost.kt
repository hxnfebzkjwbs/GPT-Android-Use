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
    private var captureHidden = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun moveToOverlay(context: Context, webView: WebView): Boolean {
        synchronized(lock) {
            if (!Settings.canDrawOverlays(context)) return false
            if (overlayAttached && hostedWebView === webView) {
                webView.resumeTimers()
                return true
            }

            (webView.parent as? ViewGroup)?.removeView(webView)

            val wm = context.applicationContext
                .getSystemService(WindowManager::class.java)

            return runCatching {
                if (overlayAttached && hostedWebView != null) {
                    runCatching { windowManager?.removeViewImmediate(hostedWebView) }
                }

                webView.importantForAccessibility =
                    WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                wm.addView(webView, createLayoutParams(context))

                windowManager = wm
                hostedWebView = webView
                overlayAttached = true
                captureHidden = false

                webView.visibility = WebView.VISIBLE
                webView.resumeTimers()
                webView.post {
                    webView.requestLayout()
                    webView.invalidate()
                }

                AppLog.add("OVERLAY", "attached")
                true
            }.getOrElse {
                overlayAttached = false
                captureHidden = false
                hostedWebView = null
                AppLog.add(
                    "OVERLAY",
                    "attach failed: " + (it.message ?: it.javaClass.simpleName)
                )
                false
            }
        }
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

            val newAlpha = if (hidden) 0f else OverlaySettings.getOpacity(context)
            if (params.alpha == newAlpha && captureHidden == hidden) {
                return@synchronized true
            }

            params.alpha = newAlpha
            runCatching {
                wm.updateViewLayout(webView, params)
                captureHidden = hidden
                AppLog.add(
                    "OVERLAY",
                    if (hidden) "hidden for screenshot" else "restored after screenshot"
                )
                true
            }.getOrElse {
                AppLog.add(
                    "OVERLAY",
                    "alpha update failed: " + (it.message ?: it.javaClass.simpleName)
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
                    "(function(){return String(Date.now())})()"
                ) { value ->
                    AppLog.add(
                        "JS_KEEPALIVE",
                        "callback=" + value.take(64)
                    )
                }
                true
            }.getOrElse {
                AppLog.add(
                    "WEBVIEW_KEEPALIVE",
                    "failed: " + (it.message ?: it.javaClass.simpleName)
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
                overlayAttached = false
                captureHidden = false
            } else {
                (webView.parent as? ViewGroup)?.let { parent ->
                    if (parent !== container) parent.removeView(webView)
                }
            }

            if (webView.parent !== container) {
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            hostedWebView = webView
            webView.importantForAccessibility =
                WebView.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            webView.visibility = WebView.VISIBLE
            webView.resumeTimers()
            webView.post {
                webView.requestLayout()
                webView.invalidate()
            }
        }
    }

    fun release(webView: WebView) {
        synchronized(lock) {
            if (overlayAttached && hostedWebView === webView) {
                runCatching { windowManager?.removeViewImmediate(webView) }
            }
            if (hostedWebView === webView) hostedWebView = null
            overlayAttached = false
            captureHidden = false
        }
    }

    private fun createLayoutParams(context: Context): WindowManager.LayoutParams =
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
