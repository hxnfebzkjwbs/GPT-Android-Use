# GPT Android Use

Android app that combines an embedded ChatGPT web experience with a local Wireless ADB bridge.

## Version 0.4.2

Version 0.4.2 introduces a fixed development signing certificate for GitHub Actions builds.

Certificate SHA-256:

```text
0D:41:FD:1E:54:BF:E4:08:0C:94:25:5F:FA:76:9A:37:1B:4F:40:9B:A9:4A:EC:6A:E7:9C:FC:31:69:AB:20:11
```

From 0.4.2 onward, GitHub Actions restores the same development keystore before every build and verifies the APK signer before uploading the artifact. Future APKs can therefore update an installed 0.4.2+ build with:

```bash
adb install -r GPT-Android-Use-debug.apk
```

### One-time migration

Versions 0.4.1 and earlier were signed by temporary GitHub-hosted runner debug keys. Android will not allow 0.4.2 to replace those builds in place.

Uninstall the old build once, install 0.4.2, and later fixed-signed versions can update it without uninstalling.

### Development-key warning

The fixed key is intentionally stored in this public repository because the current GitHub integration cannot create Actions Secrets. This makes builds reproducible for personal/test sideloading, but anyone can obtain the development key.

Do not use this key for Play Store or security-sensitive production distribution. A production release should use a private release key stored in GitHub Actions Secrets.

## Embedded ChatGPT

The main screen embeds `https://chatgpt.com/`. No OpenAI API is used.

Google OAuth opens in an external browser rather than inside the WebView. The app prefers Chrome, then Brave, then the Android default browser.

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
