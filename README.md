# GPT Android Use

Android app that combines an embedded ChatGPT web experience with a local Wireless ADB bridge.

## Version 0.4.3

GitHub Actions now produces the release APK with a private signing key stored only in repository Actions Secrets.

Current release signing certificate SHA-256:

```text
21:D0:60:A5:3A:5D:9F:81:06:65:D4:C2:A8:F2:0C:D4:18:B4:8B:23:7D:AD:CC:A3:35:70:40:9E:78:44:39:25
```

The workflow reads these repository secrets:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Pull-request builds compile an unsigned release APK and do not receive the signing material. Main-branch builds restore the PKCS#12 keystore, sign the release APK, verify the certificate fingerprint, and upload the private-signed artifact.

### Signing migration

Version 0.4.2 used the previous public development certificate. Version 0.4.3 uses the new private release certificate, so Android treats this as a signer change. Install 0.4.3 after uninstalling the old signer once.

After 0.4.3 is installed, future builds that use the same private key and a higher `versionCode` can update it with:

```bash
adb install -r GPT-Android-Use.apk
```

Keep the private PKCS#12 keystore and its passwords backed up. Losing the private key means losing the ability to issue ordinary in-place updates for installations signed by it.

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

Pull requests:

```bash
gradle assembleRelease
```

Main-branch GitHub Actions builds then sign the release APK with the repository Secrets.

Minimum Android version: Android 11 (API 30).
