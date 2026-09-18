# GPT Android Use

Android app that combines an embedded ChatGPT web experience with a local Wireless ADB bridge.

## Version 0.4.1

The main screen embeds:

```text
https://chatgpt.com/
```

No OpenAI API is used and no API key is stored by the app.

### Google account sign-in

Google authentication is intentionally not completed inside the WebView.

When the embedded ChatGPT page navigates to a Google OAuth URL, the app:

1. Detects Google authentication URLs such as `accounts.google.com`, `google-oauth2`, or a Google provider parameter.
2. Opens the authentication URL in Google Chrome when available.
3. Falls back to Brave.
4. Falls back to the Android default browser if neither is available.
5. Reloads the embedded ChatGPT page when the user returns to the app.

This follows OpenAI's Android guidance that supported browser login uses Chrome or Brave.

Android WebView and external browsers maintain separate cookie stores, so a website session created entirely in Chrome may not always transfer into the embedded WebView automatically. The app therefore never asks the user to enter a Google password into its WebView; the **Browser** button remains available when the external authenticated session cannot be reflected in the embedded page.

### Embedded ChatGPT

- JavaScript and DOM storage enabled
- Persistent WebView cookies
- ChatGPT file picker support
- Authenticated downloads through Android DownloadManager
- Web navigation/back support
- Reload button
- Browser fallback button
- Dedicated ADB setup screen

## Wireless ADB

The ADB screen uses the Shizuku-style workflow:

- discover `_adb-tls-pairing._tcp` with Android NSD/mDNS
- discover `_adb-tls-connect._tcp` after pairing
- verify the advertised address belongs to this device
- connect to `127.0.0.1:<discovered port>` for the standard same-device route
- foreground pairing helper with notification code entry
- manual host/port fallback for unusual ROMs
- persistent ADB TLS identity
- allowlisted `adb shell` command execution

## Build

GitHub Actions builds the debug APK:

```bash
gradle assembleDebug
```

Minimum Android version: Android 11 (API 30).
