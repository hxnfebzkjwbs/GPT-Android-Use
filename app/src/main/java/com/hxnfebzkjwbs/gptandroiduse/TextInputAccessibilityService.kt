package com.hxnfebzkjwbs.gptandroiduse

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TextInputAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        AppLog.add("ACCESSIBILITY", "text input service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        AppLog.add("ACCESSIBILITY", "text input service interrupted")
    }

    override fun onDestroy() {
        if (current === this) {
            current = null
        }
        AppLog.add("ACCESSIBILITY", "text input service disconnected")
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

    companion object {
        @Volatile
        private var current: TextInputAccessibilityService? = null

        fun isConnected(): Boolean = current != null

        fun setTextNow(text: String): Result<String> {
            val service = current
                ?: return Result.failure(
                    IllegalStateException(
                        "accessibility service is not enabled or connected"
                    )
                )

            return service.setTextInternal(text)
        }
    }
}
