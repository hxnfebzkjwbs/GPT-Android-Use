package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WebAdbBridge(
    context: Context,
    private val webView: WebView,
    private val onStatus: (String) -> Unit,
    private val requestCommandApproval: (
        command: String,
        reason: String,
        complete: (Boolean) -> Unit
    ) -> Unit,
    private val onNativeAssistantMessage: (String) -> Unit = {},
    private val onNativeSendState: (String, String) -> Unit = { _, _ -> },
    private val onNativeStep: (String) -> Unit = {},
    private val onNativeTaskStatus: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val adb = AndroidAdbBridge(appContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val cancellationExecutor =
        Executors.newSingleThreadExecutor()
    private val sessionToken = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val completedRequestIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val stopGeneration = AtomicLong(0L)

    @Volatile
    private var enabled = false

    @Volatile
    private var trustedTopPage = false

    fun onTopLevelUrlChanged(url: String?) {
        trustedTopPage = isTrustedChatGptUrl(url)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        webView.post {
            if (value) {
                if (trustedTopPage) {
                    webView.evaluateJavascript(installScript(), null)
                }
            } else {
                webView.evaluateJavascript(
                    "window.__gptAndroidUseSetBridgeEnabled && window.__gptAndroidUseSetBridgeEnabled(false);",
                    null
                )
            }
        }
    }

    fun bootstrapConversation() {
        if (!enabled || !trustedTopPage) return
        webView.post {
            webView.evaluateJavascript(
                "window.__gptAndroidUseBridgeBootstrap && window.__gptAndroidUseBridgeBootstrap();",
                null
            )
        }
    }

    fun sendNativeMessage(
        text: String,
        onComplete: (String) -> Unit = {}
    ) {
        val message = text.trim()
        if (!enabled) {
            onComplete("disabled")
            return
        }
        if (!trustedTopPage) {
            onComplete("not-trusted")
            return
        }
        if (message.isBlank()) {
            onComplete("empty")
            return
        }

        webView.post {
            val js =
                "window.__gptAndroidUseNativeSend ? " +
                    "window.__gptAndroidUseNativeSend(" +
                    JSONObject.quote(message) +
                    ") : 'not-ready';"
            webView.evaluateJavascript(js) { raw ->
                val result = raw
                    ?.trim()
                    ?.removePrefix("\"")
                    ?.removeSuffix("\"")
                    ?.replace("\\\"", "\"")
                    ?: "unknown"
                onComplete(result)
            }
        }
    }

    fun stopAutomation(
        onComplete: (String) -> Unit = {}
    ) {
        stopGeneration.incrementAndGet()
        AppLog.add("STOP", "native stop requested")

        cancellationExecutor.execute {
            runCatching {
                adb.disconnect()
            }.onFailure {
                AppLog.add(
                    "STOP",
                    "backend interrupt failed: " +
                        (it.message ?: it.javaClass.simpleName)
                )
            }
        }

        webView.post {
            val js =
                "window.__gptAndroidUseStopAutomation ? " +
                    "window.__gptAndroidUseStopAutomation() : 'not-ready';"
            webView.evaluateJavascript(js) { raw ->
                val result = raw
                    ?.trim()
                    ?.removePrefix("\"")
                    ?.removeSuffix("\"")
                    ?: "unknown"
                onComplete(result)
            }
        }
    }

    fun sendOneTapAdbRequest() {
        if (!enabled) {
            postStatus("ADB Run: turn Bridge ON first")
            return
        }
        if (!trustedTopPage) {
            postStatus("ADB Run: current page is not chatgpt.com")
            return
        }
        webView.post {
            webView.evaluateJavascript(
                "window.__gptAndroidUseOneTapAdbRun && window.__gptAndroidUseOneTapAdbRun();",
                null
            )
        }
    }

    fun installForCurrentPage() {
        if (!enabled || !trustedTopPage) return
        webView.evaluateJavascript(installScript(), null)
    }

    fun checkAdbOnStartup() {
        checkAdbReady { _, _ -> }
    }

    fun checkAdbReady(
        onComplete: (Boolean, String) -> Unit
    ) {
        executor.execute {
            postStatus(
                if (BuildConfig.USE_ACCESSIBILITY_BACKEND) {
                    "Accessibility: checking service…"
                } else {
                    "ADB: checking connection…"
                }
            )
            val result = ensureAdbReady()
            if (result.isSuccess) {
                postStatus(
                    if (BuildConfig.USE_ACCESSIBILITY_BACKEND) {
                        "Accessibility: ready"
                    } else {
                        "ADB: connected"
                    }
                )
                webView.post { onComplete(true, "connected") }
            } else {
                val message =
                    result.exceptionOrNull()?.message ?: "unknown error"
                postStatus(
                    (
                        if (BuildConfig.USE_ACCESSIBILITY_BACKEND) {
                            "Accessibility: not ready · "
                        } else {
                            "ADB: reconnect failed · "
                        }
                    ) + message
                )
                webView.post {
                    onComplete(false, message.take(300))
                }
            }
        }
    }

    fun checkBridgeReady(
        onComplete: (Boolean, String) -> Unit
    ) {
        if (!enabled) {
            onComplete(false, "bridge disabled")
            return
        }
        if (!trustedTopPage) {
            onComplete(false, "ChatGPT page not ready")
            return
        }

        webView.post {
            webView.evaluateJavascript(
                "window.__gptAndroidUseBridgeHealth ? " +
                    "window.__gptAndroidUseBridgeHealth() : 'not-ready';"
            ) { raw ->
                val result = raw
                    ?.trim()
                    ?.removePrefix("\"")
                    ?.removeSuffix("\"")
                    ?: "unknown"
                onComplete(result == "ready", result)
            }
        }
    }

    fun runSelfTest() {
        if (!enabled) {
            postStatus("Bridge test: turn Bridge ON first")
            return
        }
        if (!trustedTopPage) {
            postStatus("Bridge test: current page is not chatgpt.com")
            return
        }

        webView.post {
            webView.evaluateJavascript(
                "window.__gptAndroidUseSelfCheck && window.__gptAndroidUseSelfCheck();" +
                    "window.__gptAndroidUseScanNow && window.__gptAndroidUseScanNow();",
                null
            )
        }

        executor.execute {
            try {
                if (BuildConfig.USE_ACCESSIBILITY_BACKEND) {
                    postStatus(
                        "Bridge test: checking Accessibility…"
                    )
                    ensureAdbReady().getOrThrow()
                    postStatus(
                        "Bridge test: Accessibility OK"
                    )
                    return@execute
                }

                postStatus("Bridge test: connecting Wireless ADB…")
                ensureAdbReady().getOrThrow()
                val value = adb.execute(
                    "settings get global development_settings_enabled"
                ).getOrThrow().trim()
                postStatus(
                    "Bridge test: ADB OK · dev_settings=" +
                        value
                )
            } catch (t: Throwable) {
                val report = buildFailureReport(
                    requestId = "bridge-test",
                    phase = "bridge_test",
                    command = "settings get global development_settings_enabled",
                    throwable = t
                )
                postStatus("Bridge test: ADB ERROR · " + report.take(220))
            }
        }
    }

    @JavascriptInterface
    fun reportStatus(token: String, stage: String, detail: String) {
        if (!enabled || token != sessionToken || !trustedTopPage) return
        postStatus("Bridge page: " + stage.take(40) + " · " + detail.take(160))
    }

    @JavascriptInterface
    fun nativeTaskStatus(
        token: String,
        status: String
    ) {
        if (!enabled || token != sessionToken || !trustedTopPage) return
        val safeStatus = status.trim().take(20)
        if (
            safeStatus != STATUS_IN_PROGRESS &&
            safeStatus != STATUS_SUCCESS &&
            safeStatus != STATUS_FAILURE
        ) {
            return
        }
        AppLog.add("TASK_STATUS", safeStatus)
        webView.post { onNativeTaskStatus(safeStatus) }
    }

    @JavascriptInterface
    fun nativeSendState(
        token: String,
        state: String,
        detail: String
    ) {
        if (!enabled || token != sessionToken || !trustedTopPage) return
        val safeState = state.take(40)
        val safeDetail = detail.take(300)
        AppLog.add(
            "NATIVE_CHAT",
            "send_state=" + safeState + " detail=" + safeDetail
        )
        webView.post {
            onNativeSendState(safeState, safeDetail)
        }
    }

    @JavascriptInterface
    fun nativeAssistantMessage(
        token: String,
        key: String,
        text: String
    ) {
        if (!enabled || token != sessionToken || !trustedTopPage) return
        val safe = text
            .replace("\r", "")
            .trim()
            .take(MAX_NATIVE_MESSAGE_CHARS)
        if (safe.isBlank()) return

        AppLog.add(
            "NATIVE_CHAT",
            "assistant key=" + key.take(120) + "\n" + safe.take(500)
        )
        webView.post {
            onNativeAssistantMessage(safe)
        }
    }

    @JavascriptInterface
    fun executeBlock(token: String, requestId: String, payload: String) {
        if (!enabled || token != sessionToken) return
        if (!trustedTopPage) {
            postResult(requestId, false, "Bridge blocked: current page is not chatgpt.com")
            return
        }
        if (!requestId.matches(Regex("[A-Za-z0-9._:-]{1,180}"))) {
            postResult("invalid", false, "Bridge blocked: invalid request id")
            return
        }
        if (completedRequestIds.contains(requestId)) {
            postStatus("Bridge: duplicate request ignored")
            return
        }
        if (!inFlight.add(requestId)) return
        postStatus("Bridge: Native received ADB command")
        val executionGeneration = stopGeneration.get()

        executor.execute {
            var failurePhase = "parse"
            var activeCommand = ""
            try {
                val taskStatus = parseTaskStatus(payload)
                if (taskStatus == null) {
                    postResult(
                        requestId,
                        false,
                        "Device command rejected: missing or invalid STATUS: 进行中."
                    )
                    return@execute
                }
                webView.post { onNativeTaskStatus(taskStatus) }

                val stepDescription = parseStepDescription(payload)
                if (stepDescription == null) {
                    postResult(
                        requestId,
                        false,
                        "Device command rejected: missing required STEP description.\n" +
                            "Use exactly:\nSTATUS: 进行中\nSTEP: 用一句中文说明这一步做什么以及依据\nADB: <one adb shell command>"
                    )
                    return@execute
                }

                postStatus("步骤：" + stepDescription)
                AppLog.add("STEP", stepDescription)
                webView.post {
                    onNativeStep(stepDescription)
                }

                if (stopGeneration.get() != executionGeneration) {
                    postStatus("Bridge: stopped before parsing command")
                    return@execute
                }

                val commands = parseCommands(payload)
                if (commands.isEmpty()) {
                    postResult(requestId, false, "Device reply contains no ADB: command")
                    return@execute
                }
                if (commands.size > MAX_COMMANDS_PER_BLOCK) {
                    postResult(
                        requestId,
                        false,
                        "Device reply exceeds the limit of $MAX_COMMANDS_PER_BLOCK ADB: commands"
                    )
                    return@execute
                }

                if (commands.size > 1) {
                    postResult(
                        requestId,
                        false,
                        "Only one ADB: command is allowed per assistant step. " +
                            "Send one step, wait for ADB_RESULT, then decide the next step."
                    )
                    return@execute
                }

                val command = commands.single()
                activeCommand = command
                val validation = CommandPolicy.validate(command)
                val userApproved = when (validation.decision) {
                    CommandDecision.ALLOW -> false
                    CommandDecision.DENY -> {
                        postResult(
                            requestId,
                            false,
                            "Blocked command: " + command + "\nReason: " + validation.reason
                        )
                        return@execute
                    }
                    CommandDecision.REQUIRE_CONFIRMATION -> {
                        failurePhase = "user_approval"
                        postStatus("Bridge: waiting for user approval")
                        val approved = requestApprovalBlocking(command, validation.reason)
                        if (!approved) {
                            postResult(
                                requestId,
                                false,
                                "Command not executed: user denied or approval timed out.\n" +
                                    "Command: " + command
                            )
                            return@execute
                        }
                        true
                    }
                }

                if (stopGeneration.get() != executionGeneration) {
                    postStatus("Bridge: stopped before execution")
                    return@execute
                }

                val totalStartedAt = SystemClock.elapsedRealtime()

                failurePhase = "adb_prepare"
                postStatus("Bridge: connecting ADB…")
                val prepareStartedAt = SystemClock.elapsedRealtime()
                ensureAdbReady().getOrThrow()
                val prepareMs = SystemClock.elapsedRealtime() - prepareStartedAt

                failurePhase = "command_execute_1"
                postStatus("Bridge: executing 1/1")
                val commandStartedAt = SystemClock.elapsedRealtime()
                val result = adb.execute(command, userApproved).getOrThrow()
                val commandMs = SystemClock.elapsedRealtime() - commandStartedAt

                if (stopGeneration.get() != executionGeneration) {
                    postStatus("Bridge: stopped after current command")
                    return@execute
                }

                val observeAfter = shouldObserveAfter(command)
                var observeMs = 0L
                val uiSnapshot = if (observeAfter) {
                    postStatus("Bridge: observing UI")
                    val observeStartedAt = SystemClock.elapsedRealtime()
                    val snapshot = adb.observeUi(false).getOrElse {
                        "UI_SNAPSHOT_ERROR: " + (it.message ?: it.javaClass.simpleName)
                    }
                    observeMs = SystemClock.elapsedRealtime() - observeStartedAt
                    snapshot
                } else {
                    ""
                }

                val totalMs = SystemClock.elapsedRealtime() - totalStartedAt
                val output = buildString {
                    append("[1] $ ")
                    append(command)
                    append("\n")
                    append(result.take(MAX_SINGLE_RESULT_CHARS))
                    if (uiSnapshot.isNotBlank() &&
                        !result.startsWith("UI_SNAPSHOT")
                    ) {
                        append("\n\n")
                        append(uiSnapshot)
                    }
                    append("\n\ntiming_ms: prepare=")
                    append(prepareMs)
                    append(" command=")
                    append(commandMs)
                    append(" observe=")
                    append(observeMs)
                    append(" total=")
                    append(totalMs)
                }.take(MAX_RESULT_CHARS)

                if (stopGeneration.get() != executionGeneration) {
                    postStatus("Bridge: stopped before result delivery")
                    return@execute
                }

                postStatus("Bridge: ready")
                postResult(requestId, true, output)
            } catch (t: Throwable) {
                if (stopGeneration.get() != executionGeneration) {
                    postStatus(
                        "Bridge: execution interrupted by user stop"
                    )
                    return@execute
                }

                val report = buildFailureReport(
                    requestId = requestId,
                    phase = failurePhase,
                    command = activeCommand,
                    throwable = t
                )
                postStatus(
                    "Bridge: error · " +
                        (t.message ?: t.javaClass.simpleName)
                )
                postResult(requestId, false, report)
            } finally {
                inFlight.remove(requestId)
                completedRequestIds.add(requestId)
            }
        }
    }

    private fun ensureAdbReady(): Result<Unit> {
        if (BuildConfig.USE_ACCESSIBILITY_BACKEND) {
            return adb.probe()
        }

        if (adb.isSessionReady()) {
            return Result.success(Unit)
        }

        if (adb.isConnected()) {
            val probe = adb.probe()
            if (probe.isSuccess) return Result.success(Unit)
            postStatus("Bridge: ADB session invalid · reconnecting…")
        }

        val reconnect = adb.autoConnect()
        if (reconnect.isSuccess) {
            val probe = adb.probe()
            if (probe.isSuccess) return Result.success(Unit)
            return probe
        }

        val canSelfHeal =
            AdbSelfHeal.isEnabled(appContext) &&
            adb.hasSelfHealPermission() &&
            adb.isWifiConnected()

        if (!canSelfHeal) return reconnect
        if (adb.isWirelessDebuggingEnabled()) return reconnect

        return runCatching {
            postStatus("Bridge: re-enabling Wireless ADB…")
            adb.enableWirelessDebugging().getOrThrow()
            Thread.sleep(WIRELESS_ADB_RESTART_DELAY_MS)
            adb.autoConnect().getOrThrow()
            adb.probe().getOrThrow()
        }
    }

    fun shutdown() {
        enabled = false
        executor.shutdownNow()
        cancellationExecutor.shutdownNow()
        adb.disconnect()
    }

    private fun shouldObserveAfter(command: String): Boolean {
        val normalized = command.trim().replace(Regex("\\s+"), " ").lowercase()
        return normalized.startsWith("input ") ||
            normalized.startsWith("am start ") ||
            normalized.startsWith("monkey ")
    }

    private fun requestApprovalBlocking(command: String, reason: String): Boolean {
        val latch = CountDownLatch(1)
        val approved = AtomicBoolean(false)
        val completed = AtomicBoolean(false)

        webView.post {
            requestCommandApproval(command, reason) { allow ->
                if (completed.compareAndSet(false, true)) {
                    approved.set(allow)
                    latch.countDown()
                }
            }
        }

        val responded = latch.await(COMMAND_APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!responded) completed.set(true)
        return responded && approved.get()
    }

    private fun buildFailureReport(
        requestId: String,
        phase: String,
        command: String,
        throwable: Throwable
    ): String {
        return buildString {
            appendLine("ADB bridge failure diagnostics")
            appendLine("request_id: " + requestId)
            appendLine("phase: " + phase)
            appendLine("failed_command: " + command.ifBlank { "(none)" })
            appendLine("exception_chain: " + throwableChain(throwable))
            appendLine()
            appendLine("connection_state:")
            append(adb.diagnostics())
            appendLine()
            appendLine()
            append("Do not retry the same device command automatically. ")
            append("Use these diagnostics to determine the failure cause before issuing another ADB: command.")
        }.take(MAX_RESULT_CHARS)
    }

    private fun throwableChain(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 6) {
            val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
            val message = current.message?.take(500).orEmpty()
            parts += if (message.isBlank()) name else name + ": " + message
            current = current.cause
            depth += 1
        }
        return parts.joinToString(" <- ")
    }

    private fun normalizedBlockLines(
        payload: String
    ): List<String> {
        if (payload.length > MAX_BLOCK_CHARS) return emptyList()
        return payload
            .replace("\r", "")
            .lines()
            .map { line ->
                line.trim()
                    .removePrefix("```")
                    .trim()
            }
            .filter {
                it.isNotBlank() &&
                    it != "```" &&
                    !it.startsWith("#")
            }
    }

    private fun parseTaskStatus(payload: String): String? {
        val lines = normalizedBlockLines(payload)
        val statusLine = lines.firstOrNull {
            it.startsWith(STATUS_MARKER, ignoreCase = true)
        } ?: return null

        return statusLine.substringAfter(":").trim()
            .takeIf { it == STATUS_IN_PROGRESS }
    }

    private fun parseStepDescription(payload: String): String? {
        val lines = normalizedBlockLines(payload)
        val stepLine = lines.firstOrNull {
            it.startsWith(STEP_MARKER, ignoreCase = true)
        } ?: return null

        return stepLine.substringAfter(":").trim()
            .takeIf { it.length >= MIN_STEP_DESCRIPTION_CHARS }
    }

    private fun parseCommands(payload: String): List<String> {
        val lines = normalizedBlockLines(payload)
        return lines
            .filter {
                it.startsWith(ADB_COMMAND_MARKER, ignoreCase = true)
            }
            .map {
                it.substringAfter(":").trim()
            }
            .filter { it.isNotBlank() }
    }

    private fun postResult(requestId: String, ok: Boolean, output: String) {
        val safeOutput = output.take(MAX_RESULT_CHARS)
        val screenshot = adb.consumePendingScreenshot()

        AppLog.add(
            "ADB_RESULT",
            "id=" + requestId + " status=" + (if (ok) "OK" else "ERROR") +
                "\n" + safeOutput +
                if (screenshot != null) {
                    "\n[image attachment " +
                        screenshot.width + "x" + screenshot.height + "]"
                } else {
                    ""
                }
        )

        webView.post {
            if (screenshot != null) {
                val startJs =
                    "window.__gptAndroidUseBridgeImageStart && " +
                    "window.__gptAndroidUseBridgeImageStart(" +
                    JSONObject.quote(requestId) + "," +
                    JSONObject.quote(screenshot.mimeType) + "," +
                    JSONObject.quote(screenshot.fileName) + "," +
                    screenshot.width + "," +
                    screenshot.height + ");"
                webView.evaluateJavascript(startJs, null)

                screenshot.base64Jpeg
                    .chunked(IMAGE_JS_CHUNK_CHARS)
                    .forEach { chunk ->
                        val chunkJs =
                            "window.__gptAndroidUseBridgeImageChunk && " +
                            "window.__gptAndroidUseBridgeImageChunk(" +
                            JSONObject.quote(requestId) + "," +
                            JSONObject.quote(chunk) + ");"
                        webView.evaluateJavascript(chunkJs, null)
                    }

                val endJs =
                    "window.__gptAndroidUseBridgeImageEnd && " +
                    "window.__gptAndroidUseBridgeImageEnd(" +
                    JSONObject.quote(requestId) + ");"
                webView.evaluateJavascript(endJs, null)
            }

            val js = "window.__gptAndroidUseBridgeResult && " +
                "window.__gptAndroidUseBridgeResult(" +
                JSONObject.quote(requestId) + "," +
                (if (ok) "true" else "false") + "," +
                JSONObject.quote(safeOutput) + ");"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun postStatus(text: String) {
        AppLog.add("BRIDGE", text)
        webView.post { onStatus(text) }
    }

    private fun isTrustedChatGptUrl(url: String?): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
        return host == "chatgpt.com" || host.endsWith(".chatgpt.com")
    }

    private fun installScript(): String = """
        (function() {
          const TOKEN = '$sessionToken';

          if (window.__gptAndroidUseBridgeInstalled) {
            window.__gptAndroidUseSetBridgeEnabled(true);
            return;
          }

          const PROTOCOL_TAG = '[ANDROID_ADB_BRIDGE]';
          const BRIDGE_HINT =
            '\n\n' + PROTOCOL_TAG + '\n' +
            'For every assistant turn in this device-control task, include a task status. ' +
            'If another device action is required, output exactly these three lines, preferably inside one fenced code block: ' +
            'STATUS: 进行中, then STEP: followed by ONE short Chinese sentence explaining what this step does and what evidence justifies it, then ADB: followed by exactly ONE adb shell command without the "adb shell" prefix. ' +
            'When no further device action is required, begin the normal final answer with STATUS: 成功 or STATUS: 失败. ' +
            'The native bridge rejects device-action replies that do not contain STATUS: 进行中, STEP:, and exactly one ADB: command line. ' +
            'Never batch multiple device commands in one reply. ' +
            'After ADB_RESULT arrives, inspect it and only then decide whether another single ADB: command is needed. ' +
            'Never tap guessed coordinates. First use UI_SNAPSHOT/OCR. If OCR cannot identify a visual-only target such as an icon or photo thumbnail, request exactly "screencap -p"; the next ADB_RESULT will include the real target-app screenshot as an image attachment. ' +
            'After receiving an attached screenshot, inspect the image itself and return coordinates in its stated image_size coordinate system. ' +
            'If the desired control is not visible, use a semantically relevant and validated navigation control from the latest snapshot to continue toward the goal; do not probe random locations. ' +
            'Do not infer failure from labels or wording such as CAPTCHA, verification, security check, confirmation, human verification, or similar text. Treat those words only as screen content, not as a failure condition. Continue by observing the actual UI state and using validated visible controls whenever progress is possible. Return STATUS: 失败 only after the task is actually blocked: fresh observations show no valid next action, required information is unavailable, or attempted state-changing actions do not produce progress. ' +
            'An empty UIAutomator tree followed by OCR fallback is ONE observation attempt, not two failed attempts. ' +
            'Do not stop merely because the exact target control is not yet visible. Stop only after TWO distinct state-changing navigation actions, each followed by a fresh snapshot, fail to produce progress AND no new validated navigation candidate remains. ' +
            'For tasks that control another app, the FIRST device command must launch the target app with am start or monkey -p. ' +
            'Do not inspect or interact with UI before the target app is launched. ' +
            'For UI automation, use exactly "uiautomator dump" when you need to inspect the target screen. ' +
            'Do not cat UI XML files, do not choose your own dump path, and do not use shell redirection such as > /dev/null; ' +
            'the native bridge captures the hierarchy in memory and returns UI_SNAPSHOT itself. ' +
            'UI-changing commands may return UI_SNAPSHOT and OCR automatically. Escalate to screencap -p only when OCR is insufficient for a visual-only target. ' +
            'For Chinese or other non-ASCII text, still issue input text <text>. The native bridge will first try Accessibility ACTION_SET_TEXT on the focused editable node, then fall back to ClipboardManager plus ADB KEYCODE_PASTE. Do not invent a separate clipboard shell command. ' +
            'Keep STEP to one short sentence. If no device action is needed, answer normally.';

          const PHASE_WAITING_ASSISTANT = 'WAITING_ASSISTANT';
          const PHASE_EXECUTING = 'EXECUTING';
          const PHASE_RESULT_PENDING = 'RESULT_PENDING';
          const TX_WAIT_TIMEOUT_MS = 45000;
          const INTERNAL_SEND_TIMEOUT_MS = 60000;
          const NATIVE_SEND_INITIAL_DELAY_MS = 120;
          const NATIVE_SEND_RETRY_MS = 150;
          const NATIVE_SEND_MAX_ATTEMPTS = 20;

          let enabled = true;
          let bypassNextSend = false;
          let internalSendTimer = null;
          let internalSendInProgress = false;
          let transactionTimer = null;
          let oneTapPending = false;
          let oneTapCounter = 0;
          let transactionCounter = 0;
          let activeTransaction = null;
          let lastNativeAssistantKey = '';
          let nativeSendPending = false;
          let chatExperienceSelected = false;
          let chatExperienceLastAttemptAt = 0;
          let userStopRequested = false;
          let stopRetryTimer = null;

          const internalQueue = [];
          const sentInternalIds = new Set();
          const bridgeImages = new Map();

          function nativeStatus(stage, detail) {
            try {
              window.GPTAndroidUseNative.reportStatus(
                TOKEN,
                String(stage || ''),
                String(detail || '')
              );
            } catch (_) {}
          }

          function firstMatch(selectors) {
            for (const selector of selectors) {
              const el = document.querySelector(selector);
              if (el) return el;
            }
            return null;
          }

          function taskStatusFromText(text) {
            const match = String(text || '').match(
              /(?:^|\n)\s*STATUS:\s*(进行中|成功|失败)\s*(?:\n|$)/
            );
            return match ? match[1] : '';
          }

          function stripTaskStatus(text) {
            return String(text || '')
              .replace(
                /(?:^|\n)\s*STATUS:\s*(进行中|成功|失败)\s*(?=\n|$)/,
                ''
              )
              .trim();
          }

          function reportTaskStatus(status) {
            if (!status) return;
            try {
              window.GPTAndroidUseNative.nativeTaskStatus(
                TOKEN,
                String(status)
              );
            } catch (_) {}
          }

          function hashText(text) {
            let h = 2166136261;
            const value = String(text || '');
            for (let i = 0; i < value.length; i++) {
              h ^= value.charCodeAt(i);
              h = Math.imul(h, 16777619);
            }
            return (h >>> 0).toString(16);
          }

          function uniqueElements(items) {
            return Array.from(new Set(items.filter(Boolean)));
          }

          function sortDocumentOrder(items) {
            return uniqueElements(items).sort((a, b) => {
              if (a === b) return 0;
              const pos = a.compareDocumentPosition(b);
              if (pos & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
              if (pos & Node.DOCUMENT_POSITION_PRECEDING) return 1;
              return 0;
            });
          }

          function turnBoundary(node) {
            if (!node || !node.closest) return node;
            return node.closest(
              '[data-testid^="conversation-turn-"],section[data-turn],article[data-turn]'
            ) || node;
          }

          function isBeforeNode(candidate, node) {
            if (!candidate || !node || candidate === node || candidate.contains(node)) return false;
            return !!(candidate.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_FOLLOWING);
          }

          function isUserOrComposerNode(node) {
            if (!node || !node.closest) return false;
            return !!node.closest(
              '#prompt-textarea,' +
              'form[data-type="unified-composer"],' +
              '[data-message-author-role="user"],' +
              'section[data-turn="user"],' +
              '[data-turn="user"],' +
              '[data-role="user"],' +
              '[data-message-author="user"],' +
              '.user-turn'
            );
          }

          function userSurfaces() {
            const out = [];
            [
              '[data-message-author-role="user"]',
              'section[data-turn="user"]',
              '[data-turn="user"]',
              '[data-role="user"]',
              '[data-message-author="user"]',
              '.user-turn'
            ].forEach(selector => {
              document.querySelectorAll(selector).forEach(el => out.push(turnBoundary(el)));
            });
            return sortDocumentOrder(out);
          }

          function assistantSurfaces() {
            const out = [];
            [
              '[data-message-author-role="assistant"]',
              'section[data-turn="assistant"]',
              '[data-turn="assistant"]',
              '[data-role="assistant"]',
              '[data-message-author="assistant"]',
              '.agent-turn'
            ].forEach(selector => {
              document.querySelectorAll(selector).forEach(el => out.push(turnBoundary(el)));
            });
            return sortDocumentOrder(out);
          }

          function latestAssistantSurface() {
            const surfaces = assistantSurfaces();
            if (surfaces.length) return surfaces[surfaces.length - 1];

            const genericTurns = sortDocumentOrder(Array.from(document.querySelectorAll(
              '[data-testid^="conversation-turn-"],section[data-turn],article[data-turn]'
            ))).filter(turn => !isUserOrComposerNode(turn));
            if (genericTurns.length) return genericTurns[genericTurns.length - 1];

            const codeNodes = sortDocumentOrder(Array.from(document.querySelectorAll(
              'main pre code, main pre, main code'
            )).filter(node => !isUserOrComposerNode(node)));
            if (!codeNodes.length) return null;
            return turnBoundary(codeNodes[codeNodes.length - 1]);
          }

          function previousUserContextKey(node) {
            let latestText = '';
            userSurfaces().forEach(user => {
              if (!isBeforeNode(user, node)) return;
              latestText = (user.innerText || user.textContent || '').trim();
            });
            return hashText(latestText);
          }

          function assistantSnapshot() {
            const surface = latestAssistantSurface();
            if (!surface) return null;
            const text = (surface.innerText || surface.textContent || '').trim();
            if (!text) return null;
            return {
              surface: surface,
              text: text,
              key: previousUserContextKey(surface) + ':' + hashText(text)
            };
          }

          function currentAssistantKey() {
            const snapshot = assistantSnapshot();
            return snapshot ? snapshot.key : '';
          }

          function isVisible(el) {
            if (!el) return false;
            const style = window.getComputedStyle(el);
            if (!style || style.display === 'none' || style.visibility === 'hidden') return false;
            if (el.hidden || el.getAttribute('aria-hidden') === 'true') return false;
            const rect = el.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0;
          }

          function normalizedModeLabel(value) {
            return String(value || '')
              .replace(/\s+/g, ' ')
              .trim()
              .toLowerCase();
          }

          function controlHasModeLabel(el, labels) {
            if (!el) return false;
            const values = [
              el.getAttribute('aria-label'),
              el.getAttribute('title'),
              el.innerText,
              el.textContent
            ].map(normalizedModeLabel);
            return values.some(value => labels.includes(value));
          }

          function isSelectedModeControl(el) {
            if (!el) return false;
            return el.getAttribute('aria-selected') === 'true' ||
              el.getAttribute('aria-pressed') === 'true' ||
              el.getAttribute('aria-current') === 'true' ||
              el.getAttribute('data-state') === 'active' ||
              el.getAttribute('data-selected') === 'true';
          }

          function selectChatExperience() {
            if (chatExperienceSelected) return true;

            const candidates = uniqueElements(Array.from(
              document.querySelectorAll(
                'button,[role="button"],[role="tab"],[role="menuitem"],[role="option"]'
              )
            )).filter(isVisible);

            const chatLabels = ['chat', '聊天', '对话'];
            const workLabels = ['work', '工作'];
            const chatControls = candidates.filter(el =>
              controlHasModeLabel(el, chatLabels)
            );
            const workControls = candidates.filter(el =>
              controlHasModeLabel(el, workLabels)
            );

            const selectedChat = chatControls.find(isSelectedModeControl);
            if (selectedChat) {
              chatExperienceSelected = true;
              return true;
            }

            if (chatControls.length && workControls.length) {
              chatControls[0].click();
              chatExperienceSelected = true;
              nativeStatus('CHAT_MODE_SELECTED', 'Chat');
              return true;
            }

            if (chatControls.length && !workControls.length) {
              chatExperienceSelected = true;
              return true;
            }

            if (workControls.length) {
              const now = Date.now();
              if (now - chatExperienceLastAttemptAt >= 800) {
                chatExperienceLastAttemptAt = now;
                workControls[0].click();
                nativeStatus('CHAT_MODE_SWITCH', 'opened Work selector');
              }
              return false;
            }

            return true;
          }

          function isStopActionButton(button) {
            if (!button) return false;
            const testId =
              (button.getAttribute('data-testid') || '').toLowerCase();
            const aria =
              (button.getAttribute('aria-label') || '').toLowerCase();
            const title =
              (button.getAttribute('title') || '').toLowerCase();
            const text =
              (button.innerText || button.textContent || '')
                .trim()
                .toLowerCase();
            const child =
              button.querySelector(
                '[data-testid*="stop" i],' +
                '[aria-label*="stop" i],' +
                '[aria-label*="停止"],' +
                '[title*="stop" i],' +
                '[title*="停止"]'
              );

            return testId === 'stop-button' ||
              testId.includes('stop') ||
              aria.includes('stop') ||
              aria.includes('停止') ||
              title.includes('stop') ||
              title.includes('停止') ||
              text === 'stop' ||
              text === '停止' ||
              !!child;
          }

          function isSendActionButton(button) {
            if (!button || isStopActionButton(button)) return false;
            const testId = (button.getAttribute('data-testid') || '').toLowerCase();
            const aria = (button.getAttribute('aria-label') || '').toLowerCase();
            const title = (button.getAttribute('title') || '').toLowerCase();

            if (testId === 'send-button' || testId === 'composer-submit-button') return true;
            if (aria.includes('send') || aria.includes('submit') ||
                aria.includes('发送') || aria.includes('提交')) return true;
            if (title.includes('send') || title.includes('submit') ||
                title.includes('发送') || title.includes('提交')) return true;
            return button.id === 'composer-submit-button';
          }

          function stopButtons() {
            return uniqueElements(
              Array.from(
                document.querySelectorAll(
                  'button,' +
                  '[role="button"],' +
                  '#composer-submit-button'
                )
              )
            ).filter(button =>
              isVisible(button) &&
              !button.disabled &&
              isStopActionButton(button)
            );
          }

          function tryStopGeneration() {
            const button = stopButtons()[0] || null;
            if (!button) return false;

            bypassNextSend = true;
            try {
              button.dispatchEvent(
                new PointerEvent(
                  'pointerdown',
                  { bubbles: true, cancelable: true }
                )
              );
              button.dispatchEvent(
                new PointerEvent(
                  'pointerup',
                  { bubbles: true, cancelable: true }
                )
              );
            } catch (_) {}

            try {
              button.click();
            } catch (_) {
              try {
                button.dispatchEvent(
                  new MouseEvent(
                    'click',
                    { bubbles: true, cancelable: true }
                  )
                );
              } catch (_) {}
            }

            nativeStatus(
              'USER_STOP_CLICK',
              button.id ||
                button.getAttribute('data-testid') ||
                button.getAttribute('aria-label') ||
                'button'
            );
            return true;
          }

          function scheduleStopRetry() {
            if (stopRetryTimer) {
              clearTimeout(stopRetryTimer);
            }
            const startedAt = Date.now();

            function retry() {
              stopRetryTimer = null;
              if (!userStopRequested) return;

              if (tryStopGeneration()) {
                return;
              }

              if (Date.now() - startedAt < 2500) {
                stopRetryTimer = setTimeout(retry, 120);
              }
            }

            retry();
          }

          function isStreaming() {
            return stopButtons().some(button => isVisible(button) && !button.disabled);
          }

          function normalizeProtocolLine(line) {
            return String(line || '')
              .trim()
              .replace(/^```[A-Za-z0-9_-]*\s*/, '')
              .replace(/```$/, '')
              .trim();
          }

          function extractPayload(node) {
            const raw =
              (node.innerText || node.textContent || '')
                .replace(/\r/g, '');
            const lines = raw
              .split('\n')
              .map(normalizeProtocolLine)
              .filter(line =>
                !!line &&
                line !== '```' &&
                !/^(copy code|copy)$/i.test(line)
              );

            const statusLine = lines.find(line =>
              /^STATUS:\s*进行中\s*$/i.test(line)
            ) || '';
            const stepLine = lines.find(line =>
              /^STEP:\s*.+/i.test(line)
            ) || '';
            const adbLines = lines.filter(line =>
              /^ADB:\s*\S.+/i.test(line)
            );

            if (!statusLine || !stepLine || adbLines.length !== 1) {
              return '';
            }

            return [
              statusLine,
              stepLine,
              adbLines[0]
            ].join('\n');
          }

          function commandPayloads(surface) {
            if (!surface) return [];

            const candidates = [];
            Array.from(surface.querySelectorAll('pre code, pre, code'))
              .forEach(node => candidates.push(node));

            // Always include the whole assistant turn. This makes protocol
            // detection independent of how ChatGPT rendered markdown/code.
            candidates.push(surface);

            const payloads = uniqueElements(candidates)
              .map(node => extractPayload(node))
              .filter(payload => !!payload);

            if (!payloads.length) {
              const wholeText =
                (surface.innerText || surface.textContent || '');
              if (/^|\n\s*ADB:\s*\S/im.test(wholeText)) {
                nativeStatus(
                  'ADB_COMMAND_EXTRACT_FAILED',
                  wholeText.slice(0, 500)
                );
              }
            }

            return Array.from(new Set(payloads));
          }

          function scheduleTransactionAdvance(delayMs) {
            if (transactionTimer) clearTimeout(transactionTimer);
            transactionTimer = setTimeout(() => {
              transactionTimer = null;
              advanceTransaction(false);
            }, Math.max(150, delayMs || 350));
          }

          function beginTransaction(source) {
            if (activeTransaction) {
              if (
                activeTransaction.phase === PHASE_EXECUTING ||
                activeTransaction.phase === PHASE_RESULT_PENDING
              ) {
                nativeStatus(
                  'TX_BUSY',
                  activeTransaction.id + ':' + activeTransaction.phase
                );
                return false;
              }
              finishTransaction('superseded-by-new-user-message');
            }

            transactionCounter += 1;
            const now = Date.now();
            activeTransaction = {
              id: 'tx-' + transactionCounter,
              source: source || 'user',
              phase: PHASE_WAITING_ASSISTANT,
              baselineKey: currentAssistantKey(),
              candidateKey: '',
              candidateSince: 0,
              commandIndex: 0,
              currentRequestId: null,
              startedAt: now,
              lastProgressAt: now
            };
            nativeStatus(
              'TX_BEGIN',
              activeTransaction.id + ':' + activeTransaction.source
            );
            scheduleTransactionAdvance(250);
            return true;
          }

          function finishTransaction(reason) {
            if (!activeTransaction) return;
            nativeStatus(
              'TX_DONE',
              activeTransaction.id + ':' + String(reason || 'complete')
            );
            activeTransaction = null;
            if (transactionTimer) {
              clearTimeout(transactionTimer);
              transactionTimer = null;
            }
          }

          function advanceTransaction(force) {
            if (!enabled || !activeTransaction) return;
            const tx = activeTransaction;
            if (tx.phase !== PHASE_WAITING_ASSISTANT) return;

            const now = Date.now();
            if (!force && now - tx.lastProgressAt > TX_WAIT_TIMEOUT_MS) {
              finishTransaction('assistant-timeout');
              return;
            }

            const snapshot = assistantSnapshot();
            if (!snapshot) {
              scheduleTransactionAdvance(400);
              return;
            }

            if (!force && snapshot.key === tx.baselineKey) {
              scheduleTransactionAdvance(400);
              return;
            }

            // Never parse or execute a partial assistant reply. The Stop state
            // is the transaction boundary: wait until streaming has fully ended.
            if (isStreaming()) {
              tx.candidateKey = snapshot.key;
              tx.candidateSince = now;
              tx.lastProgressAt = now;
              scheduleTransactionAdvance(300);
              return;
            }

            if (tx.candidateKey !== snapshot.key) {
              tx.candidateKey = snapshot.key;
              tx.candidateSince = now;
              tx.lastProgressAt = now;
              if (!force) {
                scheduleTransactionAdvance(450);
                return;
              }
            }

            const stableFor = now - tx.candidateSince;
            if (!force && stableFor < 650) {
              scheduleTransactionAdvance(300);
              return;
            }

            const payloads = commandPayloads(snapshot.surface);
            if (!payloads.length) {
              tx.baselineKey = snapshot.key;
              tx.candidateKey = '';
              tx.candidateSince = 0;

              if (snapshot.key !== lastNativeAssistantKey) {
                const nativeText =
                  (snapshot.surface.innerText ||
                   snapshot.surface.textContent ||
                   '').trim();
                if (nativeText) {
                  try {
                    const taskStatus = taskStatusFromText(nativeText);
                    if (taskStatus) reportTaskStatus(taskStatus);
                    const displayText = stripTaskStatus(nativeText);
                    window.GPTAndroidUseNative.nativeAssistantMessage(
                      TOKEN,
                      snapshot.key,
                      displayText || nativeText
                    );
                    lastNativeAssistantKey = snapshot.key;
                  } catch (e) {
                    nativeStatus(
                      'NATIVE_CHAT_CALLBACK_ERROR',
                      String(e)
                    );
                  }
                }
              }

              finishTransaction('assistant-final');
              return;
            }

            tx.baselineKey = snapshot.key;
            tx.candidateKey = '';
            tx.candidateSince = 0;

            if (payloads.length > 1) {
              nativeStatus('TX_MULTIPLE_BLOCKS', tx.id + ':' + payloads.length);
            }

            const payload = payloads[0];
            tx.commandIndex += 1;
            const requestId =
              tx.id + ':' + tx.commandIndex + ':' + hashText(payload);
            tx.currentRequestId = requestId;
            tx.phase = PHASE_EXECUTING;
            tx.lastProgressAt = Date.now();

            nativeStatus('TX_EXECUTE', requestId);
            try {
              window.GPTAndroidUseNative.executeBlock(TOKEN, requestId, payload);
            } catch (e) {
              nativeStatus('NATIVE_CALL_ERROR', String(e));
              finishTransaction('native-call-error');
            }
          }

          function findComposer() {
            return firstMatch([
              'form[data-type="unified-composer"] #prompt-textarea[contenteditable="true"]',
              '#prompt-textarea.ProseMirror[contenteditable="true"]',
              '#prompt-textarea',
              '#mobile-composer-prompt',
              'textarea[data-testid="prompt-textarea"]',
              '[contenteditable="true"][data-testid="prompt-textarea"]',
              'main form div[contenteditable="true"]'
            ]);
          }

          function setComposerText(editor, text) {
            editor.focus();
            if (editor.tagName === 'TEXTAREA') {
              const setter = Object.getOwnPropertyDescriptor(
                HTMLTextAreaElement.prototype, 'value'
              )?.set;
              if (setter) setter.call(editor, text); else editor.value = text;
              editor.dispatchEvent(new Event('input', { bubbles: true }));
              return true;
            }

            try {
              const selection = window.getSelection();
              const range = document.createRange();
              range.selectNodeContents(editor);
              selection.removeAllRanges();
              selection.addRange(range);
              document.execCommand('delete', false);
              document.execCommand('insertText', false, text);
              editor.dispatchEvent(new InputEvent('input', {
                bubbles: true,
                inputType: 'insertText',
                data: text
              }));
              return true;
            } catch (_) {
              editor.textContent = text;
              editor.dispatchEvent(new Event('input', { bubbles: true }));
              return true;
            }
          }

          function composerText(editor) {
            if (!editor) return '';
            if (editor.tagName === 'TEXTAREA') return editor.value || '';
            return editor.innerText || editor.textContent || '';
          }

          function attachProtocolToComposer() {
            const editor = findComposer();
            if (!editor) {
              nativeStatus('COMPOSER_MISSING', 'while attaching protocol');
              return false;
            }
            const current = composerText(editor).trimEnd();
            if (!current) return false;
            if (current.includes(PROTOCOL_TAG)) return true;
            const ok = setComposerText(editor, current + BRIDGE_HINT);
            nativeStatus(
              ok ? 'PROTOCOL_ATTACHED' : 'PROTOCOL_ATTACH_FAILED',
              editor.id || editor.tagName
            );
            return ok;
          }

          function sendButtonFromTarget(target) {
            if (!target || !target.closest) return null;
            const button = target.closest(
              '#composer-submit-button,' +
              'button[data-testid="send-button"],' +
              'button[data-testid="composer-submit-button"],' +
              'button[aria-label*="Send" i],' +
              'button[aria-label*="Submit" i],' +
              'button[aria-label*="发送"],' +
              'button[aria-label*="提交"]'
            );
            return isSendActionButton(button) ? button : null;
          }

          function findSendButton() {
            const candidates = Array.from(document.querySelectorAll(
              '#composer-submit-button,' +
              'button[data-testid="send-button"],' +
              'button[data-testid="composer-submit-button"],' +
              'button[aria-label*="Send" i],' +
              'button[aria-label*="Submit" i],' +
              'button[aria-label*="发送"],' +
              'button[aria-label*="提交"]'
            ));
            return candidates.find(button =>
              isVisible(button) && isSendActionButton(button)
            ) || null;
          }

          function clickSend(source) {
            const button = findSendButton();
            if (!button) {
              const stop = stopButtons().find(isVisible);
              nativeStatus(
                stop ? 'SEND_BLOCKED_STOP_ACTIVE' : 'SEND_BUTTON_MISSING',
                stop ? (stop.id || stop.getAttribute('data-testid') || 'stop') : location.pathname
              );
              return false;
            }
            if (button.disabled) {
              nativeStatus(
                'SEND_BUTTON_DISABLED',
                button.id || button.getAttribute('data-testid') || ''
              );
              return false;
            }

            if (source && !beginTransaction(source)) return false;
            bypassNextSend = true;
            button.click();
            nativeStatus(
              'MESSAGE_SENT',
              (button.id || '') + ':' + (button.getAttribute('data-testid') || '')
            );
            return true;
          }

          function scheduleInternalFlush(delayMs) {
            if (internalSendTimer) clearTimeout(internalSendTimer);
            internalSendTimer = setTimeout(() => {
              internalSendTimer = null;
              flushInternalQueue();
            }, Math.max(100, delayMs || 250));
          }

          function enqueueInternalMessage(id, text, kind, transactionId, image) {
            if (!enabled || !id || !text) return false;
            if (sentInternalIds.has(id) || internalQueue.some(item => item.id === id)) {
              nativeStatus('INTERNAL_DUPLICATE_IGNORED', id);
              return false;
            }
            internalQueue.push({
              id: id,
              text: text,
              kind: kind || 'internal',
              transactionId: transactionId || '',
              image: image || null,
              imageAttached: !image,
              imageAttachAttempts: 0,
              prepared: false,
              queuedAt: Date.now()
            });
            nativeStatus('INTERNAL_QUEUED', id);
            scheduleInternalFlush(50);
            return true;
          }

          function finishInternalItem(item) {
            sentInternalIds.add(item.id);
            const index = internalQueue.findIndex(candidate => candidate.id === item.id);
            if (index >= 0) internalQueue.splice(index, 1);

            if (item.kind === 'oneTap') {
              oneTapPending = false;
              beginTransaction('oneTap');
            } else if (
              item.kind === 'result' &&
              activeTransaction &&
              activeTransaction.id === item.transactionId
            ) {
              activeTransaction.baselineKey = currentAssistantKey();
              activeTransaction.candidateKey = '';
              activeTransaction.candidateSince = 0;
              activeTransaction.currentRequestId = null;
              activeTransaction.phase = PHASE_WAITING_ASSISTANT;
              activeTransaction.lastProgressAt = Date.now();
              nativeStatus('TX_RESULT_SENT', activeTransaction.id);
              scheduleTransactionAdvance(250);
            }
          }

          function bridgeImageToFile(image) {
            if (!image || !image.base64) return null;
            try {
              const binary = atob(image.base64);
              const bytes = new Uint8Array(binary.length);
              for (let i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
              }
              return new File(
                [bytes],
                image.name || 'device-screen.jpg',
                { type: image.mime || 'image/jpeg' }
              );
            } catch (_) {
              return null;
            }
          }

          function attachBridgeImage(image) {
            const file = bridgeImageToFile(image);
            if (!file) return false;

            const editor = findComposer();
            if (editor) {
              try {
                const transfer = new DataTransfer();
                transfer.items.add(file);
                editor.dispatchEvent(
                  new ClipboardEvent('paste', {
                    bubbles: true,
                    cancelable: true,
                    clipboardData: transfer
                  })
                );
                nativeStatus('INTERNAL_IMAGE_ATTACH_METHOD', 'paste');
                return true;
              } catch (_) {}
            }

            const inputs = Array.from(
              document.querySelectorAll('input[type="file"]')
            );
            const input =
              inputs.find(el =>
                String(el.accept || '').toLowerCase().includes('image')
              ) ||
              inputs[0] ||
              null;

            if (input) {
              try {
                const transfer = new DataTransfer();
                transfer.items.add(file);
                input.files = transfer.files;
                input.dispatchEvent(
                  new Event('input', { bubbles: true })
                );
                input.dispatchEvent(
                  new Event('change', { bubbles: true })
                );
                nativeStatus('INTERNAL_IMAGE_ATTACH_METHOD', 'file-input');
                return true;
              } catch (_) {}
            }

            if (!editor) return false;

            try {
              const transfer = new DataTransfer();
              transfer.items.add(file);
              ['dragenter', 'dragover', 'drop'].forEach(type => {
                editor.dispatchEvent(
                  new DragEvent(type, {
                    bubbles: true,
                    cancelable: true,
                    dataTransfer: transfer
                  })
                );
              });
              nativeStatus('INTERNAL_IMAGE_ATTACH_METHOD', 'drag-drop');
              return true;
            } catch (_) {
              return false;
            }
          }

          function flushInternalQueue() {
            if (!enabled || internalSendInProgress || !internalQueue.length) return;

            const item = internalQueue[0];

            if (Date.now() - item.queuedAt > INTERNAL_SEND_TIMEOUT_MS) {
              nativeStatus('INTERNAL_SEND_TIMEOUT', item.id);
              const timedOutIndex = internalQueue.findIndex(candidate => candidate.id === item.id);
              if (timedOutIndex >= 0) internalQueue.splice(timedOutIndex, 1);
              if (item.kind === 'oneTap') oneTapPending = false;
              if (
                item.kind === 'result' &&
                activeTransaction &&
                activeTransaction.id === item.transactionId
              ) {
                finishTransaction('result-send-timeout');
              }
              if (internalQueue.length) scheduleInternalFlush(100);
              return;
            }

            if (isStreaming()) {
              nativeStatus(
                'INTERNAL_WAIT_STOP',
                item.id + ':visibleStop=' + stopButtons().filter(isVisible).length
              );
              scheduleInternalFlush(400);
              return;
            }

            if (item.image && !item.imageAttached) {
              const attached = attachBridgeImage(item.image);
              item.imageAttachAttempts = (item.imageAttachAttempts || 0) + 1;

              if (!attached) {
                if (item.imageAttachAttempts >= MAX_IMAGE_ATTACH_ATTEMPTS) {
                  nativeStatus('INTERNAL_IMAGE_ATTACH_FAILED', item.id);
                  item.text =
                    item.text +
                    '\n\nSCREENSHOT_ATTACHMENT_FAILED: the device screenshot could not be attached to this message.';
                  item.image = null;
                  item.imageAttached = true;
                  scheduleInternalFlush(100);
                  return;
                }

                nativeStatus(
                  'INTERNAL_WAIT_IMAGE_ATTACH',
                  item.id + ':' + item.imageAttachAttempts
                );
                scheduleInternalFlush(500);
                return;
              }

              item.imageAttached = true;
              nativeStatus('INTERNAL_IMAGE_ATTACHED', item.id);
              scheduleInternalFlush(900);
              return;
            }

            const editor = findComposer();
            if (!editor) {
              nativeStatus('INTERNAL_WAIT_COMPOSER', item.id);
              scheduleInternalFlush(500);
              return;
            }

            const current = composerText(editor);
            if (!item.prepared) {
              if (current.trim()) {
                nativeStatus('INTERNAL_WAIT_USER_DRAFT', item.id);
                scheduleInternalFlush(700);
                return;
              }

              const inserted = setComposerText(editor, item.text);
              if (!inserted) {
                nativeStatus('INTERNAL_WRITE_FAILED', item.id);
                return;
              }
              item.prepared = true;
              nativeStatus('INTERNAL_PREPARED', item.id);
              scheduleInternalFlush(140);
              return;
            }

            const currentPrepared = composerText(editor);
            if (!currentPrepared.trim()) {
              item.prepared = false;
              scheduleInternalFlush(150);
              return;
            }

            const button = findSendButton();
            if (!button || button.disabled || isStopActionButton(button)) {
              nativeStatus('INTERNAL_WAIT_SEND', item.id);
              scheduleInternalFlush(300);
              return;
            }

            internalSendInProgress = true;
            bypassNextSend = true;
            button.click();
            finishInternalItem(item);
            internalSendInProgress = false;

            nativeStatus(
              'INTERNAL_SENT',
              item.id + ':' + (button.getAttribute('data-testid') || button.id || 'button')
            );

            if (internalQueue.length) scheduleInternalFlush(250);
          }

          window.__gptAndroidUseBridgeImageStart =
            function(id, mime, name, width, height) {
              bridgeImages.set(id, {
                mime: mime || 'image/jpeg',
                name: name || 'device-screen.jpg',
                width: Number(width || 0),
                height: Number(height || 0),
                base64: ''
              });
            };

          window.__gptAndroidUseBridgeImageChunk = function(id, chunk) {
            const image = bridgeImages.get(id);
            if (!image) return;
            image.base64 += String(chunk || '');
          };

          window.__gptAndroidUseBridgeImageEnd = function(id) {
            const image = bridgeImages.get(id);
            if (!image) return;
            nativeStatus(
              'BRIDGE_IMAGE_READY',
              id + ':' + image.width + 'x' + image.height
            );
          };

          window.__gptAndroidUseBridgeResult = function(id, ok, output) {
            if (
              !activeTransaction ||
              activeTransaction.phase !== PHASE_EXECUTING ||
              activeTransaction.currentRequestId !== id
            ) {
              nativeStatus('STALE_RESULT_IGNORED', id);
              return;
            }

            activeTransaction.phase = PHASE_RESULT_PENDING;
            const txId = activeTransaction.id;
            const message =
              'ADB_RESULT ' + id + '\n' +
              'status: ' + (ok ? 'OK' : 'ERROR') + '\n' +
              output + '\n\n' +
              'The device command has finished. Inspect this result before deciding the next action. ' +
              'If the original request still needs device work, the next reply MUST contain: STATUS: 进行中, then STEP: <one short Chinese sentence>, then exactly ONE ADB: <command> line. ' +
              'If no further device action is needed, begin the final answer with STATUS: 成功 or STATUS: 失败. ' +
              'If an error says no target app is established or the target is not foreground, launch/re-open the intended target app first. ' +
              'Do not batch multiple commands. Use UI/OCR first. If the needed target is visual-only and OCR cannot locate it, request screencap -p so the next ADB_RESULT includes the real screenshot image. ' +
              'Do not count repeated screen inspections as failed navigation. If the exact target is absent but validated navigation candidates remain, continue toward the goal. ' +
              'Stop only after two distinct state-changing navigation actions fail to make progress and no new validated navigation candidate remains. Do not treat words like CAPTCHA, verification, security check, or human verification as proof of failure; decide from the observed state and available validated actions. ' +
              'For screen inspection, use only "uiautomator dump"; never cat dump files or add shell redirection; the hierarchy is captured in memory. ' +
              'Otherwise answer normally.';

            const image = bridgeImages.get(id) || null;
            if (image) bridgeImages.delete(id);

            enqueueInternalMessage(
              'result:' + id,
              message,
              'result',
              txId,
              image
            );
          };

          window.__gptAndroidUseBridgeHealth = function() {
            if (!enabled) return 'disabled';
            if (!window.__gptAndroidUseBridgeInstalled) {
              return 'script-not-installed';
            }
            if (!selectChatExperience()) return 'switching-to-chat';
            const editor = findComposer();
            if (!editor) return 'composer-missing';
            return 'ready';
          };

          window.__gptAndroidUseStopAutomation = function() {
            try {
              userStopRequested = true;

              if (transactionTimer) {
                clearTimeout(transactionTimer);
                transactionTimer = null;
              }
              if (internalSendTimer) {
                clearTimeout(internalSendTimer);
                internalSendTimer = null;
              }

              internalQueue.splice(0, internalQueue.length);
              oneTapPending = false;
              nativeSendPending = false;
              internalSendInProgress = false;

              if (activeTransaction) {
                finishTransaction('user-stop');
              }

              const clicked = tryStopGeneration();
              if (!clicked) {
                scheduleStopRetry();
              }

              nativeStatus(
                'USER_STOP',
                clicked ?
                  'generation-and-automation' :
                  'automation-stop-pending'
              );
              return clicked ?
                'stopped-generation' :
                'stop-requested';
            } catch (e) {
              nativeStatus(
                'USER_STOP_ERROR',
                String(e)
              );
              return 'error:' + String(e);
            }
          };

          window.__gptAndroidUseBackgroundTick = function() {
            if (!enabled) return 'disabled';
            if (userStopRequested) {
              tryStopGeneration();
              return 'stopped';
            }

            try {
              if (activeTransaction) {
                advanceTransaction(false);
              }
              if (internalQueue.length) {
                flushInternalQueue();
              }
              return (
                'ok:' +
                (activeTransaction ? activeTransaction.phase : 'IDLE') +
                ':queue=' + internalQueue.length
              );
            } catch (e) {
              nativeStatus('BACKGROUND_TICK_ERROR', String(e));
              return 'error:' + String(e);
            }
          };

          window.__gptAndroidUseBridgeBootstrap = function() {
            nativeStatus('INLINE_PROTOCOL_READY', 'state-machine bridge ready');
          };

          window.__gptAndroidUseNativeSend = function(text) {
            if (!enabled) return 'disabled';

            userStopRequested = false;
            if (stopRetryTimer) {
              clearTimeout(stopRetryTimer);
              stopRetryTimer = null;
            }
            if (!selectChatExperience()) return 'switching-to-chat';
            if (isStreaming()) return 'streaming';
            if (nativeSendPending) return 'busy';

            const message = String(text || '').trim();
            if (!message) return 'empty';

            if (
              activeTransaction &&
              (activeTransaction.phase === PHASE_EXECUTING ||
               activeTransaction.phase === PHASE_RESULT_PENDING)
            ) {
              return 'busy';
            }

            if (activeTransaction) {
              finishTransaction('superseded-by-native-message');
            }

            const editor = findComposer();
            if (!editor) return 'composer-missing';

            const composed = message + BRIDGE_HINT;
            const inserted = setComposerText(editor, composed);
            if (!inserted) return 'write-failed';

            nativeSendPending = true;
            let attempt = 0;

            function reportNativeSendState(state, detail) {
              try {
                window.GPTAndroidUseNative.nativeSendState(
                  TOKEN,
                  String(state || ''),
                  String(detail || '')
                );
              } catch (_) {}
            }

            function failNativeSend(reason) {
              nativeSendPending = false;
              const currentEditor = findComposer();
              if (currentEditor) {
                const currentText = composerText(currentEditor);
                if (
                  currentText === composed ||
                  currentText.includes(message)
                ) {
                  setComposerText(currentEditor, '');
                }
              }
              nativeStatus('NATIVE_SEND_FAILED', reason);
              reportNativeSendState('failed', reason);
            }

            function tryNativeSend() {
              if (!enabled) {
                failNativeSend('bridge disabled before send');
                return;
              }

              if (isStreaming()) {
                attempt += 1;
              } else {
                const button = findSendButton();
                if (button && !button.disabled) {
                  const sent = clickSend('native');
                  if (sent) {
                    nativeSendPending = false;
                    nativeStatus(
                      'NATIVE_SEND_CONFIRMED',
                      'attempt=' + attempt
                    );
                    reportNativeSendState(
                      'sent',
                      'attempt=' + attempt
                    );
                    return;
                  }
                }
                attempt += 1;
              }

              if (attempt >= NATIVE_SEND_MAX_ATTEMPTS) {
                failNativeSend(
                  'send button unavailable after ' + attempt + ' attempts'
                );
                return;
              }

              setTimeout(
                tryNativeSend,
                NATIVE_SEND_RETRY_MS
              );
            }

            setTimeout(tryNativeSend, NATIVE_SEND_INITIAL_DELAY_MS);
            return 'queued';
          };

          window.__gptAndroidUseOneTapAdbRun = function() {
            if (!enabled) return;

            if (
              activeTransaction &&
              activeTransaction.phase === PHASE_WAITING_ASSISTANT
            ) {
              finishTransaction('superseded-by-one-tap');
            }

            if (
              oneTapPending ||
              (activeTransaction &&
                (activeTransaction.phase === PHASE_EXECUTING ||
                 activeTransaction.phase === PHASE_RESULT_PENDING)) ||
              isStreaming()
            ) {
              nativeStatus(
                'ADB_RUN_BUSY',
                oneTapPending ? 'pending' :
                  (activeTransaction ? activeTransaction.phase : 'streaming')
              );
              return;
            }

            const prompt =
              '请通过 Android ADB 读取当前手机电池状态。严格返回一个代码块：' +
              '第一行 STATUS: 进行中，第二行 STEP: 读取当前手机电池状态，第三行 ADB: dumpsys battery。' +
              BRIDGE_HINT;

            oneTapPending = true;
            oneTapCounter += 1;
            nativeStatus('ADB_RUN_BUTTON', 'queued battery-status request');
            enqueueInternalMessage(
              'one-tap:' + oneTapCounter,
              prompt,
              'oneTap',
              ''
            );
          };

          window.__gptAndroidUseSelfCheck = function() {
            const editor = findComposer();
            const snapshot = assistantSnapshot();
            nativeStatus(
              'SELF_CHECK',
              'editor=' + (!!editor) +
              ',assistant=' + assistantSurfaces().length +
              ',streaming=' + isStreaming() +
              ',sendState=' + (!!findSendButton()) +
              ',tx=' + (activeTransaction ? activeTransaction.phase : 'IDLE') +
              ',assistantKey=' + (snapshot ? snapshot.key : 'none')
            );
          };

          window.__gptAndroidUseScanNow = function() {
            if (!activeTransaction) {
              nativeStatus('FORCE_SCAN_IDLE', 'no active transaction');
              return;
            }
            nativeStatus('FORCE_SCAN_START', activeTransaction.id);
            advanceTransaction(true);
          };

          document.addEventListener('click', function(event) {
            if (!enabled) return;
            const button = sendButtonFromTarget(event.target);
            if (!button) return;

            if (bypassNextSend) {
              bypassNextSend = false;
              return;
            }

            const editor = findComposer();
            const text = composerText(editor);
            if (!text.trim()) return;

            if (text.includes(PROTOCOL_TAG)) {
              if (!beginTransaction('user')) {
                event.preventDefault();
                event.stopImmediatePropagation();
              }
              return;
            }

            event.preventDefault();
            event.stopImmediatePropagation();

            if (!attachProtocolToComposer()) return;

            setTimeout(() => {
              const freshButton = findSendButton();
              if (!freshButton) {
                nativeStatus('USER_SEND_ABORTED_NO_SEND', 'button state changed');
                return;
              }
              if (!beginTransaction('user')) {
                nativeStatus('USER_SEND_ABORTED_BUSY', 'click');
                return;
              }
              bypassNextSend = true;
              freshButton.click();
              nativeStatus(
                'USER_MESSAGE_SENT_WITH_PROTOCOL',
                (freshButton.id || '') + ':' +
                (freshButton.getAttribute('data-testid') || '')
              );
            }, 100);
          }, true);

          document.addEventListener('keydown', function(event) {
            if (!enabled || event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
            const editor = findComposer();
            if (!editor) return;
            if (!(event.target === editor || editor.contains(event.target))) return;

            const text = composerText(editor);
            if (!text.trim()) return;

            if (text.includes(PROTOCOL_TAG)) {
              if (!beginTransaction('user')) {
                event.preventDefault();
                event.stopImmediatePropagation();
              }
              return;
            }

            event.preventDefault();
            event.stopImmediatePropagation();

            if (!attachProtocolToComposer()) return;
            setTimeout(() => {
              if (!clickSend('user')) {
                nativeStatus('USER_SEND_ABORTED_NO_SEND', 'keydown');
              }
            }, 100);
          }, true);

          window.__gptAndroidUseSetBridgeEnabled = function(value) {
            enabled = !!value;
            if (!enabled) {
              if (transactionTimer) clearTimeout(transactionTimer);
              if (internalSendTimer) clearTimeout(internalSendTimer);
              return;
            }
            scheduleTransactionAdvance(250);
            scheduleInternalFlush(100);
          };

          const observer = new MutationObserver(() => {
            selectChatExperience();
            if (activeTransaction) scheduleTransactionAdvance(350);
            if (internalQueue.length) scheduleInternalFlush(120);
          });

          observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            characterData: true,
            attributes: true,
            attributeFilter: [
              'data-testid',
              'aria-label',
              'title',
              'disabled',
              'hidden',
              'aria-hidden'
            ]
          });

          window.__gptAndroidUseBridgeInstalled = true;
          nativeStatus('PAGE_INJECTED', location.pathname);
          window.__gptAndroidUseSetBridgeEnabled(true);
          setTimeout(selectChatExperience, 80);
          setTimeout(window.__gptAndroidUseSelfCheck, 250);
        })();
    """.trimIndent()

    companion object {
        private const val ADB_COMMAND_MARKER = "ADB:"
        private const val STATUS_MARKER = "STATUS:"
        private const val STEP_MARKER = "STEP:"
        private const val STATUS_IN_PROGRESS = "进行中"
        private const val STATUS_SUCCESS = "成功"
        private const val STATUS_FAILURE = "失败"
        private const val MIN_STEP_DESCRIPTION_CHARS = 4
        private const val MAX_COMMANDS_PER_BLOCK = 1
        private const val MAX_BLOCK_CHARS = 6_000
        private const val MAX_SINGLE_RESULT_CHARS = 6_000
        private const val MAX_RESULT_CHARS = 12_000
        private const val WIRELESS_ADB_RESTART_DELAY_MS = 1_500L
        private const val COMMAND_APPROVAL_TIMEOUT_SECONDS = 120L
        private const val IMAGE_JS_CHUNK_CHARS = 48_000
        private const val MAX_IMAGE_ATTACH_ATTEMPTS = 4
        private const val MAX_NATIVE_MESSAGE_CHARS = 24_000
    }
}
