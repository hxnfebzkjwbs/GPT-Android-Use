# GPT Android Use

Android app that combines an embedded ChatGPT web experience with a same-device Wireless ADB bridge.

## Version 0.4.7

Version 0.4.7 fixes reply detection when ChatGPT leaves hidden stop controls mounted in the DOM and lets Test Bridge force-scan the latest ADB_EXEC block.

### Web ↔ ADB bridge

The main screen has **Bridge OFF / Bridge ON** and **Test Bridge** controls.

When Bridge is enabled:

1. The app injects a page observer only while the top-level page is `chatgpt.com`.
2. Existing conversation history is marked as already seen, so old ADB blocks are not replayed.
3. The app sends one bridge-protocol message into the current ChatGPT conversation. Version 0.4.6 also supports the current `#composer-submit-button` and ProseMirror composer selectors.
4. New assistant responses are watched for a fenced code block whose first line is exactly:

```text
ADB_EXEC
```

5. Each following line is treated as one ADB shell command, without the `adb shell` prefix.
6. Native code validates every command against the allowlist, reconnects Wireless ADB if needed, then executes the block sequentially.
7. The execution result is automatically sent back into the same ChatGPT conversation as an `ADB_RESULT` message so the model can continue from the device result.

Example assistant output:

```text
ADB_EXEC
input tap 500 1200
input keyevent KEYCODE_BACK
```

The bridge accepts at most 8 commands per block. Only visible stop controls count as active streaming. Test Bridge force-scans the latest ADB_EXEC block even if it is already visible on the page.

Currently allowed command families:

- `input tap`
- `input swipe`
- `input text`
- `input keyevent`
- `am start`
- `am force-stop`
- `pm list packages`
- `pm path`
- `dumpsys`
- `settings get`
- `uiautomator dump`
- `screencap`

Shell chaining, pipes, redirects, command substitution, destructive package removal, reboot/wipe commands, and similar bypasses are rejected.

The JavaScript interface uses a random per-session token and Native also checks that the current top-level page is still `chatgpt.com` before accepting any command.

## Embedded ChatGPT

The main screen embeds `https://chatgpt.com/`. No OpenAI API is used.

Normal HTTP/HTTPS authentication, including Google sign-in, stays inside the WebView. The app does not force login into Chrome or Brave.

The **Browser** button remains a manual action only.

## Wireless ADB

The ADB setup screen uses the Shizuku-style workflow:

- discover `_adb-tls-pairing._tcp` with Android NSD/mDNS
- discover `_adb-tls-connect._tcp` after pairing
- verify the advertised address belongs to this device
- connect to `127.0.0.1:<discovered port>`
- foreground pairing helper with notification code entry
- manual host/port fallback for unusual ROMs
- persistent ADB TLS identity
- allowlisted shell execution

Returning from the ADB setup screen no longer disconnects Wireless ADB, so the web bridge can immediately reuse the connection.

## Private release signing

GitHub Actions signs release APKs with the private PKCS#12 key stored only in repository Actions Secrets.

Current signing certificate SHA-256:

```text
21:D0:60:A5:3A:5D:9F:81:06:65:D4:C2:A8:F2:0C:D4:18:B4:8B:23:7D:AD:CC:A3:35:70:40:9E:78:44:39:25
```

The workflow reads:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Pull requests compile an unsigned release APK and do not receive the signing material. Main-branch builds restore the private keystore, sign the release APK, verify the certificate fingerprint, and upload the artifact.

Version 0.4.3 and later use the same private release key. With a higher `versionCode`, update in place with:

```bash
adb install -r GPT-Android-Use.apk
```

Minimum Android version: Android 11 (API 30).


### Bridge diagnostics

Tap **Test Bridge** while Bridge is ON. The status line reports stages such as:

- `PAGE_INJECTED` — the JavaScript bridge is installed on chatgpt.com
- `SELF_CHECK · editor=true,send=true` — ChatGPT composer and send controls were found
- `COMPOSER_MISSING` / `SEND_BUTTON_MISSING` — the current page DOM no longer matches
- `ADB_EXEC_FOUND` — a new assistant ADB block was detected
- `Bridge test: ADB OK` — the native Wireless ADB connection successfully ran a read-only settings query

**Test Bridge** also retries the bridge protocol message.


### 0.4.7 reply execution fix

If an `ADB_EXEC` reply is already visible but was not executed, tap **Test Bridge**. The app now:

- ignores hidden/inactive Stop buttons when deciding whether generation is still running
- reports `ADB_EXEC_FORCE_FOUND` when the latest command block is located
- reports `Bridge: Native received ADB_EXEC` as soon as the JavaScript call reaches Android
- force-executes the latest matching block once for diagnosis
