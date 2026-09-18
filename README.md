# GPT Android Use

Android app that combines an embedded ChatGPT web experience with a local Wireless ADB bridge.

## Version 0.4.0

The main screen is now a full embedded WebView that opens:

```text
https://chatgpt.com/
```

No OpenAI API is used and no API key is stored by the app.

### Embedded ChatGPT

- JavaScript and DOM storage enabled
- Persistent WebView cookies/login state
- ChatGPT file picker support
- Authenticated downloads through Android DownloadManager
- Web navigation/back support
- Reload button
- Browser fallback button
- Dedicated ADB setup screen so the chat stays full-screen

Some sign-in methods may reject an embedded WebView. The app includes **Browser** as a fallback because OpenAI's Android sign-in flow officially relies on supported browsers such as Chrome or Brave.

## Wireless ADB

The ADB screen retains the Shizuku-style workflow:

- discover `_adb-tls-pairing._tcp` with Android NSD/mDNS
- discover `_adb-tls-connect._tcp` after pairing
- verify the advertised address belongs to this device
- connect to `127.0.0.1:<discovered port>` for the standard same-device route
- foreground pairing helper with notification code entry
- manual host/port fallback for unusual ROMs
- persistent ADB TLS identity
- allowlisted `adb shell` command execution

## First use

1. Launch the app and sign in to ChatGPT in the embedded page.
2. Tap **ADB** at the bottom.
3. Tap **Start Shizuku-style pairing**.
4. In Android Wireless debugging, tap **Pair device with pairing code**.
5. Enter the six-digit code from the GPT Android Use notification.
6. Return and tap **Discover & connect**.
7. Return to the embedded ChatGPT page.

No computer, OpenAI API key, or root access is required.

## Build

GitHub Actions builds the debug APK:

```bash
gradle assembleDebug
```

Minimum Android version: Android 11 (API 30).
