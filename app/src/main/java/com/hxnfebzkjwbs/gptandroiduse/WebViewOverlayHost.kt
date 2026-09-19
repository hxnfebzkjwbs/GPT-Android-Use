package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout

object WebViewOverlayHost {
    private val lock = Any()
    private var windowManager: WindowManager? = null
    private var hostedWebView: WebView? = null
    private var overlayAttached = false

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
            val params = WindowManager.LayoutParams(
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
                alpha = 0.01f
            }

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
                webView.visibility = WebView.VISIBLE
                webView.resumeTimers()
                webView.post {
                    webView.requestLayout()
                    webView.invalidate()
                }
                true
            }.getOrElse {
                overlayAttached = false
                hostedWebView = null
                false
            }
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
        }
    }
}
