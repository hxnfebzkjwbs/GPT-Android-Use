package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object WebViewOverlayHost {
    private val lock = Any()
    private var windowManager: WindowManager? = null
    private var hostedWebView: WebView? = null
    private var overlayRoot: FrameLayout? = null
    private var overlayAttached = false
    private var captureHidden = false
    private var backgroundWidth = 0
    private var backgroundHeight = 0
    private var floatingButton: FloatingStatusView? = null
    private var floatingStatus = ""
    private var overlayX = -1
    private var overlayY = -1
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
                    floatingButton?.let { applyFloatingStatus(it) }
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
                webView.layoutParams =
                    backgroundWebViewLayoutParams(
                        screenWidth,
                        screenHeight
                    )
                root.addView(webView)

                val bubble = FloatingStatusView(appContext).apply {
                    isClickable = true
                    isFocusable = false
                    setIconStyle(OverlaySettings.getIconStyle(appContext))
                    setTaskStatus(floatingStatus)
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
                        createMicroWindowLayoutParams(
                            hostSide,
                            screenWidth,
                            screenHeight
                        )
                    )

                    windowManager = wm
                    hostedWebView = webView
                    overlayRoot = root
                    floatingButton = bubble
                    overlayAttached = true
                    installFloatingButtonTouch(
                        appContext,
                        wm,
                        root,
                        bubble,
                        screenWidth,
                        screenHeight,
                        hostSide
                    )
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
                    floatingButton = null
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

    fun updateTaskStatus(status: String) {
        synchronized(lock) {
            floatingStatus = status
        }
        mainHandler.post {
            synchronized(lock) {
                floatingButton?.let { applyFloatingStatus(it) }
            }
        }
    }

    private fun applyFloatingStatus(button: FloatingStatusView) {
        button.setTaskStatus(floatingStatus)
    }

    fun refreshFloatingStyle(context: Context) {
        val style = OverlaySettings.getIconStyle(context.applicationContext)
        mainHandler.post {
            synchronized(lock) {
                floatingButton?.setIconStyle(style)
            }
        }
    }

    private fun installFloatingButtonTouch(
        context: Context,
        wm: WindowManager,
        root: FrameLayout,
        bubble: FloatingStatusView,
        screenWidth: Int,
        screenHeight: Int,
        hostSide: Int
    ) {
        var pointerDown = false
        var dragUnlocked = false
        var downAt = 0L
        var latestRawX = 0f
        var latestRawY = 0f

        bubble.isHapticFeedbackEnabled = true

        fun unlockDrag(rawX: Float, rawY: Float) {
            if (!pointerDown || dragUnlocked) return

            dragUnlocked = true
            bubble.alpha = 1f

            val hapticDone =
                bubble.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            if (!hapticDone) {
                vibrateForDrag(context)
            }

            // At the unlock moment, immediately center the icon under the
            // finger instead of preserving any press-point offset.
            val params =
                root.layoutParams as? WindowManager.LayoutParams
                    ?: return
            moveFloatingIconToFinger(
                wm,
                root,
                params,
                rawX,
                rawY,
                screenWidth,
                screenHeight,
                hostSide
            )

            AppLog.add(
                "MICRO_OVERLAY",
                "drag unlocked after " +
                    (SystemClock.uptimeMillis() - downAt) +
                    "ms"
            )
        }

        val longPressRunnable = Runnable {
            unlockDrag(latestRawX, latestRawY)
        }

        bubble.setOnTouchListener { _, event ->
            val params =
                root.layoutParams as? WindowManager.LayoutParams
                    ?: return@setOnTouchListener false

            latestRawX = event.rawX
            latestRawY = event.rawY

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerDown = true
                    dragUnlocked = false
                    downAt = SystemClock.uptimeMillis()
                    bubble.alpha = 0.72f

                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(
                        longPressRunnable,
                        FLOATING_DRAG_LONG_PRESS_MS
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (
                        !dragUnlocked &&
                        pointerDown &&
                        SystemClock.uptimeMillis() - downAt >=
                            FLOATING_DRAG_LONG_PRESS_MS
                    ) {
                        unlockDrag(event.rawX, event.rawY)
                    }

                    if (dragUnlocked) {
                        moveFloatingIconToFinger(
                            wm,
                            root,
                            params,
                            event.rawX,
                            event.rawY,
                            screenWidth,
                            screenHeight,
                            hostSide
                        )
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val heldLongEnough =
                        SystemClock.uptimeMillis() - downAt >=
                            FLOATING_DRAG_LONG_PRESS_MS

                    if (!dragUnlocked && heldLongEnough) {
                        unlockDrag(event.rawX, event.rawY)
                    }

                    val wasDragging = dragUnlocked
                    pointerDown = false
                    dragUnlocked = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    bubble.alpha = 1f

                    if (!wasDragging) {
                        openMainActivity(context)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    pointerDown = false
                    dragUnlocked = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    bubble.alpha = 1f
                    true
                }

                else -> true
            }
        }
    }

    private fun moveFloatingIconToFinger(
        wm: WindowManager,
        root: FrameLayout,
        params: WindowManager.LayoutParams,
        rawX: Float,
        rawY: Float,
        screenWidth: Int,
        screenHeight: Int,
        hostSide: Int
    ) {
        val maxX =
            (screenWidth - hostSide)
                .coerceAtLeast(0)
        val maxY =
            (screenHeight - hostSide)
                .coerceAtLeast(0)

        params.x =
            (rawX - hostSide / 2f)
                .roundToInt()
                .coerceIn(0, maxX)
        params.y =
            (rawY - hostSide / 2f)
                .roundToInt()
                .coerceIn(0, maxY)

        overlayX = params.x
        overlayY = params.y

        runCatching {
            wm.updateViewLayout(root, params)
        }
    }

    private fun vibrateForDrag(context: Context) {
        val vibrator: Vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }

        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                FLOATING_DRAG_VIBRATION_MS,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
    }

    private fun openMainActivity(context: Context) {
        AppLog.add("MICRO_OVERLAY", "floating button clicked")
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
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

        webView.layoutParams =
            backgroundWebViewLayoutParams(
                width.coerceAtLeast(1),
                height.coerceAtLeast(1)
            )
        backgroundWidth = width.coerceAtLeast(1)
        backgroundHeight = height.coerceAtLeast(1)
        webView.requestLayout()
    }

    private fun backgroundWebViewLayoutParams(
        width: Int,
        height: Int
    ): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1)
        ).apply {
            // Keep the full-size WebView attached and running, but move its
            // entire rendered surface to the left of the tiny overlay host.
            // The parent clips children, so no ChatGPT pixels are visible
            // behind the floating control.
            leftMargin = -width.coerceAtLeast(1) - 1
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
            floatingButton = null
            backgroundWidth = 0
            backgroundHeight = 0
            hostedWebView = webView

            if (webView.parent !== container) {
                container.addView(
                    webView,
                    0,
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
            floatingButton = null
            backgroundWidth = 0
            backgroundHeight = 0
        }
    }

    private fun createMicroWindowLayoutParams(
        hostSide: Int,
        screenWidth: Int,
        screenHeight: Int
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
            gravity = Gravity.TOP or Gravity.START

            if (overlayX < 0 || overlayY < 0) {
                overlayX =
                    (screenWidth - hostSide)
                        .coerceAtLeast(0)
                overlayY =
                    (screenHeight - hostSide)
                        .coerceAtLeast(0)
            }

            x = overlayX.coerceIn(
                0,
                (screenWidth - hostSide).coerceAtLeast(0)
            )
            y = overlayY.coerceIn(
                0,
                (screenHeight - hostSide).coerceAtLeast(0)
            )
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
    private const val FLOATING_DRAG_LONG_PRESS_MS = 2_000L
    private const val FLOATING_DRAG_VIBRATION_MS = 45L
}
