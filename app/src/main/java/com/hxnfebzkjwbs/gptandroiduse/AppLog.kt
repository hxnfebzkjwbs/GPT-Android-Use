package com.hxnfebzkjwbs.gptandroiduse

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_ENTRIES = 500
    private const val MAX_MESSAGE_CHARS = 4_000
    private val entries = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(category: String, message: String) {
        val timestamp = formatter.format(Date())
        val clean = message.replace("\u0000", "").take(MAX_MESSAGE_CHARS)
        entries.addLast("[" + timestamp + "] [" + category + "] " + clean)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): String =
        if (entries.isEmpty()) {
            "(no log entries)"
        } else {
            entries.toList()
                .asReversed()
                .joinToString("\n\n")
        }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
