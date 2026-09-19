package com.hxnfebzkjwbs.gptandroiduse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TextInputAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        AppLog.add("ACCESSIBILITY", "device control service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        AppLog.add("ACCESSIBILITY", "device control service interrupted")
    }

    override fun onDestroy() {
        if (current === this) {
            current = null
        }
        AppLog.add("ACCESSIBILITY", "device control service disconnected")
        super.onDestroy()
    }

    private fun setTextInternal(text: String): Result<String> = runCatching {
        val root = rootInActiveWindow
            ?: error("no active accessibility window")

        val node =
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { supportsSetText(it) }
                ?: findBestEditableNode(root)
                ?: error("no editable node supporting ACTION_SET_TEXT")

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        check(
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )
        ) {
            "ACTION_SET_TEXT returned false"
        }

        val label = sequenceOf(
            node.viewIdResourceName,
            node.contentDescription?.toString(),
            node.className?.toString()
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        "node=" + label.take(160)
    }

    private fun tapInternal(x: Float, y: Float): Result<String> {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGestureBlocking(
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        80L
                    )
                )
                .build(),
            "tap"
        )
    }

    private fun swipeInternal(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Result<String> {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchGestureBlocking(
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        durationMs.coerceIn(100L, 5_000L)
                    )
                )
                .build(),
            "swipe"
        )
    }

    private fun dispatchGestureBlocking(
        gesture: GestureDescription,
        label: String
    ): Result<String> = runCatching {
        val latch = CountDownLatch(1)
        val completed = AtomicReference<Boolean?>(null)

        check(
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(
                        gestureDescription: GestureDescription
                    ) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(
                        gestureDescription: GestureDescription
                    ) {
                        completed.set(false)
                        latch.countDown()
                    }
                },
                null
            )
        ) {
            "dispatchGesture returned false"
        }

        check(latch.await(3L, TimeUnit.SECONDS)) {
            label + " gesture timed out"
        }
        check(completed.get() == true) {
            label + " gesture was cancelled"
        }
        label + " completed"
    }

    private fun globalActionInternal(action: Int): Result<String> =
        runCatching {
            check(performGlobalAction(action)) {
                "performGlobalAction returned false"
            }
            "global_action=" + action
        }

    private fun currentPackageInternal(): String =
        rootInActiveWindow
            ?.packageName
            ?.toString()
            .orEmpty()

    private fun snapshotUiInternal(): Result<String> = runCatching {
        val root = rootInActiveWindow
            ?: error("no active accessibility window")

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val lines = ArrayList<String>()
        var rawNodes = 0
        var meaningfulNodes = 0
        var clickableNodes = 0
        val packages = linkedSetOf<String>()

        while (queue.isNotEmpty() && rawNodes < MAX_SNAPSHOT_NODES) {
            val node = queue.removeFirst()
            rawNodes += 1

            val text = node.text?.toString().orEmpty().trim()
            val desc =
                node.contentDescription?.toString().orEmpty().trim()
            val id = node.viewIdResourceName.orEmpty().trim()
            val className = node.className?.toString().orEmpty()
            val packageName = node.packageName?.toString().orEmpty()
            if (packageName.isNotBlank()) packages += packageName

            if (node.isClickable && node.isEnabled) {
                clickableNodes += 1
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val meaningful =
                text.isNotBlank() ||
                    desc.isNotBlank() ||
                    id.isNotBlank() ||
                    node.isClickable

            if (meaningful) {
                meaningfulNodes += 1
                lines += buildString {
                    append("node")
                    if (text.isNotBlank()) {
                        append(" text=").append(quoteUi(text))
                    }
                    if (desc.isNotBlank()) {
                        append(" desc=").append(quoteUi(desc))
                    }
                    if (id.isNotBlank()) {
                        append(" id=").append(id)
                    }
                    if (className.isNotBlank()) {
                        append(" class=")
                        append(className.substringAfterLast('.'))
                    }
                    if (packageName.isNotBlank()) {
                        append(" package=").append(packageName)
                    }
                    append(" clickable=").append(node.isClickable)
                    append(" enabled=").append(node.isEnabled)
                    append(" bounds=[")
                    append(bounds.left).append(",").append(bounds.top)
                    append("][")
                    append(bounds.right).append(",").append(bounds.bottom)
                    append("]")
                }.take(420)
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        buildString {
            appendLine("UI_SNAPSHOT")
            appendLine("format: accessibility-summary-v1")
            appendLine("raw_nodes: " + rawNodes)
            appendLine("meaningful_nodes: " + meaningfulNodes)
            appendLine("clickable_nodes: " + clickableNodes)
            appendLine(
                "packages: " +
                    if (packages.isEmpty()) "(none)"
                    else packages.joinToString(",")
            )
            lines.take(MAX_OUTPUT_NODES).forEach {
                appendLine(it)
            }
            if (lines.size > MAX_OUTPUT_NODES) {
                appendLine("truncated: true")
            }
        }.take(10_000)
    }

    private fun screenshotInternal(): Result<ScreenshotAttachment> =
        runCatching {
            val latch = CountDownLatch(1)
            val resultRef =
                AtomicReference<AccessibilityService.ScreenshotResult?>(null)
            val errorRef = AtomicInteger(0)

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(
                        screenshot: AccessibilityService.ScreenshotResult
                    ) {
                        resultRef.set(screenshot)
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        errorRef.set(errorCode)
                        latch.countDown()
                    }
                }
            )

            check(latch.await(4L, TimeUnit.SECONDS)) {
                "accessibility screenshot timed out"
            }
            check(errorRef.get() == 0) {
                "accessibility screenshot failed code=" +
                    errorRef.get()
            }

            val result =
                resultRef.get()
                    ?: error("accessibility screenshot missing")
            val hardware = result.hardwareBuffer
            val hardwareBitmap =
                Bitmap.wrapHardwareBuffer(
                    hardware,
                    result.colorSpace
                ) ?: error(
                    "could not wrap screenshot hardware buffer"
                )

            val bitmap = try {
                hardwareBitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                ) ?: error(
                    "could not copy screenshot bitmap"
                )
            } finally {
                hardware.close()
            }

            try {
                val out = ByteArrayOutputStream()
                check(
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        52,
                        out
                    )
                ) {
                    "could not encode screenshot JPEG"
                }
                val jpeg = out.toByteArray()
                ScreenshotAttachment(
                    base64Jpeg = Base64.encodeToString(
                        jpeg,
                        Base64.NO_WRAP
                    ),
                    width = bitmap.width,
                    height = bitmap.height
                )
            } finally {
                bitmap.recycle()
            }
        }

    private fun findBestEditableNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var firstEditable: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (supportsSetText(node)) {
                if (node.isFocused || node.isAccessibilityFocused) {
                    return node
                }
                if (firstEditable == null) {
                    firstEditable = node
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        return firstEditable
    }

    private fun supportsSetText(
        node: AccessibilityNodeInfo
    ): Boolean =
        node.isEditable &&
            node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
            }

    private fun quoteUi(value: String): String =
        """ + value
            .replace("\\", "\\\\")
            .replace(""", "\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .take(160) + """

    companion object {
        @Volatile
        private var current: TextInputAccessibilityService? = null

        private const val MAX_SNAPSHOT_NODES = 240
        private const val MAX_OUTPUT_NODES = 120

        fun isConnected(): Boolean = current != null

        fun isEnabled(context: Context): Boolean {
            if (isConnected()) return true
            val manager =
                context.getSystemService(
                    AccessibilityManager::class.java
                ) ?: return false

            return manager
                .getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                .any { service ->
                    val info = service.resolveInfo?.serviceInfo
                    if (info?.packageName != context.packageName) {
                        false
                    } else {
                        val name = info.name.orEmpty()
                        name ==
                            TextInputAccessibilityService::class.java.name ||
                            name.endsWith(
                                ".TextInputAccessibilityService"
                            )
                    }
                }
        }

        fun setTextNow(text: String): Result<String> =
            current?.setTextInternal(text)
                ?: Result.failure(
                    IllegalStateException(
                        "accessibility service is not connected"
                    )
                )

        fun tapNow(
            x: Float,
            y: Float
        ): Result<String> =
            current?.tapInternal(x, y)
                ?: Result.failure(
                    IllegalStateException(
                        "accessibility service is not connected"
                    )
                )

        fun swipeNow(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long
        ): Result<String> =
            current?.swipeInternal(
                x1,
                y1,
                x2,
                y2,
                durationMs
            ) ?: Result.failure(
                IllegalStateException(
                    "accessibility service is not connected"
                )
            )

        fun performKeyEventNow(
            keyCode: Int
        ): Result<String> {
            val action = when (keyCode) {
                4 -> GLOBAL_ACTION_BACK
                3 -> GLOBAL_ACTION_HOME
                187 -> GLOBAL_ACTION_RECENTS
                else -> return Result.failure(
                    IllegalArgumentException(
                        "unsupported accessibility keyevent: " +
                            keyCode
                    )
                )
            }

            return current?.globalActionInternal(action)
                ?: Result.failure(
                    IllegalStateException(
                        "accessibility service is not connected"
                    )
                )
        }

        fun currentPackageName(): String =
            current?.currentPackageInternal().orEmpty()

        fun snapshotUiNow(): Result<String> =
            current?.snapshotUiInternal()
                ?: Result.failure(
                    IllegalStateException(
                        "accessibility service is not connected"
                    )
                )

        fun takeScreenshotNow(): Result<ScreenshotAttachment> =
            current?.screenshotInternal()
                ?: Result.failure(
                    IllegalStateException(
                        "accessibility service is not connected"
                    )
                )
    }
}
