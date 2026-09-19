package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.os.Handler
import android.os.Looper
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
    private var suspendedForUiInspection = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun moveToOverlay(context: Context, webView: WebView): Boolean {
        synchronized(lock) {
            if (!Settings.canDrawOverlays(context)) return false
            if (overlayAttached && hostedWebView === webView) {
                webView.resumeTimers()
                return true
            }

            val parent = webView.parent as? ViewGroup
            parent?.removeView(webView)

            val wm = context.applicationContext
                .getSystemService(WindowManager::class.java)
            val params = createLayoutParams(context)

            return runCatching {
                if (overlayAttached && hostedWebView != null) {
                    runCatching { windowManager?.removeViewImmediate(hostedWebView) }
                }
                webView.importantForAccessibility =
                    WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                wm.addView(webView, params)
                windowManager = wm
                hostedWebView = webView
                overlayAttached = true
                suspendedForUiInspection = false
                webView.visibility = WebView.VISIBLE
                webView.resumeTimers()
                webView.post {
                    webView.requestLayout()
                    webView.invalidate()
                }
                true
            }.getOrElse {
                overlayAttached = false
                suspendedForUiInspection = false
                hostedWebView = null
                false
            }
        }
    }

    fun suspendForUiInspection(timeoutMs: Long = 1_000L): Boolean =
        runOnMainBlocking(timeoutMs) {
            synchronized(lock) {
                if (!overlayAttached) return@synchronized false
                val webView = hostedWebView ?: return@synchronized false
                runCatching { windowManager?.removeViewImmediate(webView) }
                    .onFailure {
                        AppLog.add("OVERLAY", "suspend failed: " + (it.message ?: it.javaClass.simpleName))
                    }
                    .isSuccess
                    .also { success ->
                        if (success) {
                            overlayAttached = false
                            suspendedForUiInspection = true
                            AppLog.add("OVERLAY", "suspended for UI inspection")
                        }
                    }
            }
        }

    fun resumeAfterUiInspection(
        context: Context,
        timeoutMs: Long = 1_000L
    ): Boolean =
        runOnMainBlocking(timeoutMs) {
            synchronized(lock) {
                if (!suspendedForUiInspection) return@synchronized false
                val webView = hostedWebView ?: return@synchronized false
                val wm = windowManager ?: context.applicationContext
                    .getSystemService(WindowManager::class.java)

                runCatching {
                    webView.importantForAccessibility =
                        WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    wm.addView(webView, createLayoutParams(context))
                    windowManager = wm
                    overlayAttached = true
                    suspendedForUiInspection = false
                    webView.visibility = WebView.VISIBLE
                    webView.resumeTimers()
                    webView.requestLayout()
                    webView.invalidate()
                    AppLog.add("OVERLAY", "resumed after UI inspection")
                    true
                }.getOrElse {
                    overlayAttached = false
                    AppLog.add("OVERLAY", "resume failed: " + (it.message ?: it.javaClass.simpleName))
                    false
                }
            }
        }

    fun updateOpacity(context: Context) {
        synchronized(lock) {
            if (!overlayAttached) return
            val webView = hostedWebView ?: return
            val wm = windowManager ?: return
            val params = webView.layoutParams as? WindowManager.LayoutParams ?: return
            params.alpha = OverlaySettings.getOpacity(context)
            runCatching { wm.updateViewLayout(webView, params) }
        }
    }

    fun restore(webView: WebView, container: FrameLayout) {
        synchronized(lock) {
            if (overlayAttached && hostedWebView === webView) {
                runCatching { windowManager?.removeViewImmediate(webView) }
                overlayAttached = false
                hostedWebView = null
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
            webView.importantForAccessibility = WebView.IMPORTANT_FOR_ACCESSIBILITY_AUTO
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
            suspendedForUiInspection = false
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
