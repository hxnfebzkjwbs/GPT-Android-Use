# GPT Android Use

Android app that combines an embedded ChatGPT web experience with a local Wireless ADB bridge.

## Version 0.4.4

Version 0.4.4 removes the automatic external-login behavior.

The embedded ChatGPT page now keeps normal HTTP/HTTPS authentication navigation inside the WebView, including Google sign-in pages. The app no longer detects Google OAuth URLs and no longer forces Chrome or Brave to open during login.

The bottom **Browser** button remains available as a manual action only.

## Private release signing

GitHub Actions produces the release APK with a private signing key stored only in repository Actions Secrets.

Current release signing certificate SHA-256:

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

Pull requests compile an unsigned release APK and do not receive the signing material. Main-branch builds restore the private PKCS#12 keystore, sign the release APK, verify the certificate fingerprint, and upload the private-signed artifact.

Version 0.4.3 and later use the same private release key. With a higher `versionCode`, updates can be installed in place:

```bash
adb install -r GPT-Android-Use.apk
```

## Embedded ChatGPT

The main screen embeds `https://chatgpt.com/`. No OpenAI API is used.

- JavaScript and DOM storage enabled
- persistent WebView cookies
- file picker support
- authenticated downloads through Android DownloadManager
- back navigation and reload
- manual Browser button
- dedicated ADB setup screen

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

Minimum Android version: Android 11 (API 30).
