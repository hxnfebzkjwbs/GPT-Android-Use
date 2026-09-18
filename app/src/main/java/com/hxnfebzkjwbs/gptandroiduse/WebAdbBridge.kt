package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors

class WebAdbBridge(
    context: Context,
    private val webView: WebView,
    private val onStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val adb = AndroidAdbBridge(appContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val sessionToken = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val completedRequestIds = Collections.synchronizedSet(mutableSetOf<String>())

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
                postStatus("Bridge test: connecting Wireless ADB…")
                adb.autoConnect().getOrThrow()
                val value = adb.execute("settings get global development_settings_enabled")
                    .getOrThrow()
                    .trim()
                postStatus("Bridge test: ADB OK · dev_settings=$value")
            } catch (t: Throwable) {
                postStatus("Bridge test: ADB ERROR · " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    @JavascriptInterface
    fun reportStatus(token: String, stage: String, detail: String) {
        if (!enabled || token != sessionToken || !trustedTopPage) return
        postStatus("Bridge page: " + stage.take(40) + " · " + detail.take(160))
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
        postStatus("Bridge: Native received ADB_EXEC")

        executor.execute {
            try {
                val commands = parseCommands(payload)
                if (commands.isEmpty()) {
                    postResult(requestId, false, "ADB_EXEC block contains no commands")
                    return@execute
                }
                if (commands.size > MAX_COMMANDS_PER_BLOCK) {
                    postResult(
                        requestId,
                        false,
                        "ADB_EXEC block exceeds the limit of $MAX_COMMANDS_PER_BLOCK commands"
                    )
                    return@execute
                }

                commands.forEach { command ->
                    val validation = CommandPolicy.validate(command)
                    if (!validation.allowed) {
                        postResult(
                            requestId,
                            false,
                            "Blocked command: $command\nReason: ${validation.reason}"
                        )
                        return@execute
                    }
                }

                postStatus("Bridge: connecting ADB…")
                ensureAdbReady().getOrThrow()

                val output = buildString {
                    commands.forEachIndexed { index, command ->
                        postStatus("Bridge: executing ${index + 1}/${commands.size}")
                        append("[")
                        append(index + 1)
                        append("] $ ")
                        append(command)
                        append("\n")
                        val result = adb.execute(command).getOrThrow()
                        append(result.take(MAX_SINGLE_RESULT_CHARS))
                        if (index != commands.lastIndex) append("\n\n")
                    }
                }.take(MAX_RESULT_CHARS)

                postStatus("Bridge: ready")
                postResult(requestId, true, output)
            } catch (t: Throwable) {
                adb.disconnect()
                postStatus("Bridge: error")
                postResult(requestId, false, t.message ?: t.javaClass.simpleName)
            } finally {
                inFlight.remove(requestId)
                completedRequestIds.add(requestId)
            }
        }
    }

    private fun ensureAdbReady(): Result<Unit> {
        val firstAttempt = adb.autoConnect()
        if (firstAttempt.isSuccess) return firstAttempt

        val canSelfHeal =
            AdbSelfHeal.isEnabled(appContext) &&
            adb.hasSelfHealPermission() &&
            adb.isWifiConnected()

        if (!canSelfHeal) return firstAttempt
        if (adb.isWirelessDebuggingEnabled()) return firstAttempt

        return runCatching {
            postStatus("Bridge: re-enabling Wireless ADB…")
            adb.enableWirelessDebugging().getOrThrow()
            Thread.sleep(WIRELESS_ADB_RESTART_DELAY_MS)
            adb.disconnect()
            adb.autoConnect().getOrThrow()
        }
    }

    fun shutdown() {
        enabled = false
        executor.shutdownNow()
        adb.disconnect()
    }

    private fun parseCommands(payload: String): List<String> {
        if (payload.length > MAX_BLOCK_CHARS) return emptyList()
        val lines = payload.replace("\r", "").lines()
        if (lines.isEmpty() || lines.first().trim() != EXEC_MARKER) return emptyList()
        return lines.drop(1)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun postResult(requestId: String, ok: Boolean, output: String) {
        val safeOutput = output.take(MAX_RESULT_CHARS)
        webView.post {
            val js = "window.__gptAndroidUseBridgeResult && " +
                "window.__gptAndroidUseBridgeResult(" +
                JSONObject.quote(requestId) + "," +
                (if (ok) "true" else "false") + "," +
                JSONObject.quote(safeOutput) + ");"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun postStatus(text: String) {
        webView.post { onStatus(text) }
    }

    private fun isTrustedChatGptUrl(url: String?): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
        return host == "chatgpt.com" || host.endsWith(".chatgpt.com")
    }

    private fun installScript(): String = """
        (function() {
          const TOKEN = '$sessionToken';
          const MARKER = '$EXEC_MARKER';

          if (window.__gptAndroidUseBridgeInstalled) {
            window.__gptAndroidUseSetBridgeEnabled(true);
            return;
          }

          const PROTOCOL_TAG = '[ANDROID_ADB_BRIDGE]';
          const BRIDGE_HINT =
            '\n\n' + PROTOCOL_TAG + '\n' +
            'If this request requires Android device access, reply with exactly one fenced code block. ' +
            'The first line inside the block must be ADB_EXEC. Each following line must be one allowed ' +
            'adb shell command without the "adb shell" prefix. Do not add prose outside the block. ' +
            'If no device action is needed, answer normally.';

          const PHASE_WAITING_ASSISTANT = 'WAITING_ASSISTANT';
          const PHASE_EXECUTING = 'EXECUTING';
          const PHASE_RESULT_PENDING = 'RESULT_PENDING';
          const TX_WAIT_TIMEOUT_MS = 45000;
          const INTERNAL_SEND_TIMEOUT_MS = 60000;

          let enabled = true;
          let bypassNextSend = false;
          let internalSendTimer = null;
          let internalSendInProgress = false;
          let transactionTimer = null;
          let oneTapPending = false;
          let oneTapCounter = 0;
          let transactionCounter = 0;
          let activeTransaction = null;

          const internalQueue = [];
          const sentInternalIds = new Set();

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

          function isStopActionButton(button) {
            if (!button) return false;
            const testId = (button.getAttribute('data-testid') || '').toLowerCase();
            const aria = (button.getAttribute('aria-label') || '').toLowerCase();
            const title = (button.getAttribute('title') || '').toLowerCase();
            return testId === 'stop-button' ||
              testId.includes('stop') ||
              aria.includes('stop') ||
              aria.includes('停止') ||
              title.includes('stop') ||
              title.includes('停止');
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
            return Array.from(document.querySelectorAll(
              '#composer-submit-button,' +
              'button[data-testid="stop-button"],' +
              'button[aria-label*="Stop" i],' +
              'button[aria-label*="停止"]'
            )).filter(isStopActionButton);
          }

          function isStreaming() {
            return stopButtons().some(button => isVisible(button) && !button.disabled);
          }

          function extractPayload(node) {
            const raw = (node.innerText || node.textContent || '').replace(/\r/g, '');
            const lines = raw.split('\n');
            const markerIndex = lines.findIndex(line => line.trim() === MARKER);
            if (markerIndex < 0) return '';

            const payload = [MARKER];
            for (let i = markerIndex + 1; i < lines.length && payload.length <= 9; i++) {
              const line = lines[i].trim();
              if (!line && payload.length > 1) break;
              if (!line) continue;
              if (/^(copy code|copy)$/i.test(line)) continue;
              payload.push(line);
            }
            return payload.join('\n').trim();
          }

          function commandPayloads(surface) {
            if (!surface) return [];
            const out = [];
            const nestedCode = Array.from(surface.querySelectorAll('pre code'));
            const preBlocks = Array.from(surface.querySelectorAll('pre'));
            const looseCode = Array.from(surface.querySelectorAll('code'))
              .filter(code => !code.closest('pre'));

            if (nestedCode.length) {
              nestedCode.forEach(code => out.push(code));
            } else if (preBlocks.length) {
              preBlocks.forEach(pre => out.push(pre));
            }
            looseCode.forEach(code => out.push(code));

            if (!out.length) {
              const wholeText = (surface.innerText || surface.textContent || '');
              if (wholeText.includes(MARKER)) out.push(surface);
            }

            return uniqueElements(out)
              .map(node => extractPayload(node))
              .filter(payload => !!payload);
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

            if (!force && isStreaming() && stableFor < 1800) {
              scheduleTransactionAdvance(350);
              return;
            }

            tx.baselineKey = snapshot.key;
            tx.candidateKey = '';
            tx.candidateSince = 0;

            const payloads = commandPayloads(snapshot.surface);
            if (!payloads.length) {
              finishTransaction('assistant-final');
              return;
            }

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

          function enqueueInternalMessage(id, text, kind, transactionId) {
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
              'The device command has finished. Do not issue another ADB_EXEC unless ' +
              'the original user request still requires an additional distinct device action.';

            enqueueInternalMessage(
              'result:' + id,
              message,
              'result',
              txId
            );
          };

          window.__gptAndroidUseBridgeBootstrap = function() {
            nativeStatus('INLINE_PROTOCOL_READY', 'state-machine bridge ready');
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
              '请通过 Android ADB 读取当前手机电池状态。不要解释，不要回复 understood。' +
              '请严格只返回一个代码块，第一行必须是 ADB_EXEC，下一行使用 dumpsys battery。' +
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
          setTimeout(window.__gptAndroidUseSelfCheck, 250);
        })();
    """.trimIndent()

    companion object {
        private const val EXEC_MARKER = "ADB_EXEC"
        private const val MAX_COMMANDS_PER_BLOCK = 8
        private const val MAX_BLOCK_CHARS = 6_000
        private const val MAX_SINGLE_RESULT_CHARS = 6_000
        private const val MAX_RESULT_CHARS = 12_000
        private const val WIRELESS_ADB_RESTART_DELAY_MS = 1_500L
    }
}
