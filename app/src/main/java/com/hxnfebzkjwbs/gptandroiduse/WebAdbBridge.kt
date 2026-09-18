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
                adb.autoConnect().getOrThrow()

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
                postStatus("Bridge: error")
                postResult(requestId, false, t.message ?: t.javaClass.simpleName)
            } finally {
                inFlight.remove(requestId)
            }
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

          let enabled = true;
          let timer = null;
          const seen = new Set();

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
            for (let i = 0; i < text.length; i++) {
              h ^= text.charCodeAt(i);
              h = Math.imul(h, 16777619);
            }
            return (h >>> 0).toString(16);
          }

          function assistantContainers() {
            return Array.from(document.querySelectorAll('[data-message-author-role="assistant"]'));
          }

          function requestIdFor(code, text) {
            const role = code.closest('[data-message-author-role="assistant"]');
            const turn = code.closest('[data-testid^="conversation-turn-"]');
            const turnKey = turn ? (turn.getAttribute('data-testid') || '') :
              'assistant-' + Math.max(0, assistantContainers().indexOf(role));
            const codes = role ? Array.from(role.querySelectorAll('pre')) : [code];
            const codeIndex = Math.max(0, codes.indexOf(code));
            return turnKey + ':' + codeIndex + ':' + hashText(text);
          }

          function candidateBlocks() {
            const out = [];
            assistantContainers().forEach(role => {
              role.querySelectorAll('pre').forEach(code => out.push(code));
            });
            return out;
          }

          function isVisible(el) {
            if (!el) return false;
            const style = window.getComputedStyle(el);
            if (!style || style.display === 'none' || style.visibility === 'hidden') return false;
            if (el.hidden || el.getAttribute('aria-hidden') === 'true') return false;
            const rect = el.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0;
          }

          function stopButtons() {
            return Array.from(document.querySelectorAll(
              'button[data-testid="stop-button"],' +
              'button[aria-label*="Stop" i],' +
              'button[aria-label*="停止"]'
            ));
          }

          function isStreaming() {
            return stopButtons().some(button => isVisible(button) && !button.disabled);
          }

          function markExisting() {
            candidateBlocks().forEach(code => {
              const text = (code.innerText || code.textContent || '').trim();
              if (!text) return;
              seen.add(requestIdFor(code, text));
            });
          }

          function matchingBlocks() {
            return candidateBlocks().map(code => {
              const text = (code.innerText || code.textContent || '').replace(/\r/g, '').trim();
              return { code, text };
            }).filter(item => {
              if (!item.text) return false;
              const firstLine = item.text.split('\n', 1)[0].trim();
              return firstLine === MARKER;
            });
          }

          function dispatchBlock(item, force) {
            const id = requestIdFor(item.code, item.text);
            if (!force && seen.has(id)) return false;
            seen.add(id);
            nativeStatus(force ? 'ADB_EXEC_FORCE_FOUND' : 'ADB_EXEC_FOUND', id);
            try {
              window.GPTAndroidUseNative.executeBlock(TOKEN, id, item.text);
              return true;
            } catch (e) {
              nativeStatus('NATIVE_CALL_ERROR', String(e));
              return false;
            }
          }

          function scan(force) {
            if (!enabled) return;
            const streaming = isStreaming();
            if (streaming && !force) {
              nativeStatus('SCAN_WAIT_STREAMING', 'visibleStop=' + stopButtons().filter(isVisible).length);
              return;
            }

            const matches = matchingBlocks();
            if (force) {
              const latest = matches.length ? matches[matches.length - 1] : null;
              if (!latest) {
                nativeStatus('SCAN_NO_ADB_EXEC', 'blocks=' + candidateBlocks().length);
                return;
              }
              dispatchBlock(latest, true);
              return;
            }

            matches.forEach(item => dispatchBlock(item, false));
          }

          function scheduleScan() {
            if (timer) clearTimeout(timer);
            timer = setTimeout(() => scan(false), 1200);
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

          function clickSend() {
            const button = firstMatch([
              'button[data-testid="send-button"]',
              '#composer-submit-button',
              'button[data-testid="composer-submit-button"]',
              'button[aria-label="Send prompt"]',
              'button[aria-label*="Send" i]:not([aria-label*="Stop" i])',
              'button[aria-label*="发送"]',
              'button[aria-label*="提交"]'
            ]);
            if (!button) {
              nativeStatus('SEND_BUTTON_MISSING', location.pathname);
              return false;
            }
            if (button.disabled) {
              nativeStatus('SEND_BUTTON_DISABLED', button.id || button.getAttribute('data-testid') || '');
              return false;
            }
            button.click();
            nativeStatus('MESSAGE_SENT', button.id || button.getAttribute('data-testid') || 'button');
            return true;
          }

          function submitMessage(text, attempts) {
            if (!enabled) return;
            const editor = findComposer();
            if (!editor) {
              if (attempts === 8 || attempts === 4 || attempts === 0) {
                nativeStatus('COMPOSER_MISSING', 'attempts=' + attempts);
              }
              if (attempts > 0) setTimeout(() => submitMessage(text, attempts - 1), 350);
              return;
            }
            nativeStatus('COMPOSER_FOUND', editor.id || editor.tagName);
            const inserted = setComposerText(editor, text);
            if (!inserted) {
              nativeStatus('COMPOSER_WRITE_FAILED', editor.id || editor.tagName);
            }
            setTimeout(() => {
              if (!clickSend() && attempts > 0) {
                setTimeout(() => submitMessage(text, attempts - 1), 350);
              }
            }, 180);
          }

          window.__gptAndroidUseBridgeResult = function(id, ok, output) {
            const message =
              'ADB_RESULT ' + id + '\n' +
              'status: ' + (ok ? 'OK' : 'ERROR') + '\n' +
              output + '\n\n' +
              'Continue from this result. If another device action is required, ' +
              'reply with exactly one code block whose first line is ADB_EXEC.';
            submitMessage(message, 8);
          };

          window.__gptAndroidUseBridgeBootstrap = function() {
            nativeStatus('BOOTSTRAP_START', location.pathname);
            const message =
              'Native ADB bridge is enabled for this Android device. ' +
              'When you need to operate the device, reply with exactly one fenced code block. ' +
              'The first line inside the block must be ADB_EXEC. ' +
              'Each following line must be one allowed adb shell command without the "adb shell" prefix. ' +
              'Examples of allowed command families: input tap/swipe/text, am start/force-stop, ' +
              'pm list packages, pm path, dumpsys, settings get, uiautomator dump, screencap. ' +
              'Execution results will return automatically as ADB_RESULT messages. ' +
              'Do not emit ADB_EXEC blocks merely as examples.';
            submitMessage(message, 8);
          };

          window.__gptAndroidUseSelfCheck = function() {
            const editor = findComposer();
            const send = firstMatch([
              'button[data-testid="send-button"]',
              '#composer-submit-button',
              'button[data-testid="composer-submit-button"]',
              'button[aria-label="Send prompt"]',
              'button[aria-label*="Send" i]:not([aria-label*="Stop" i])'
            ]);
            nativeStatus(
              'SELF_CHECK',
              'editor=' + (!!editor) +
              ',send=' + (!!send) +
              ',assistant=' + assistantContainers().length +
              ',blocks=' + candidateBlocks().length +
              ',adbExec=' + matchingBlocks().length +
              ',streaming=' + isStreaming()
            );
          };

          window.__gptAndroidUseScanNow = function() {
            nativeStatus('FORCE_SCAN_START', 'path=' + location.pathname);
            scan(true);
          };

          window.__gptAndroidUseSetBridgeEnabled = function(value) {
            enabled = !!value;
            if (enabled) {
              markExisting();
              scheduleScan();
            }
          };

          markExisting();
          const observer = new MutationObserver(scheduleScan);
          observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            characterData: true
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
    }
}
