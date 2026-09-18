# GPT Android Use

Android-side bridge for controlling the local device through user-authorized Wireless ADB.

## Current capabilities

- Android 11+ (API 30+) baseline
- Shizuku-style Wireless ADB pairing flow
- Discovers `_adb-tls-pairing._tcp` with Android NSD/mDNS
- Discovers `_adb-tls-connect._tcp` separately after pairing
- Verifies the discovered service belongs to a network interface on this device
- Uses `127.0.0.1:<discovered port>` for the normal same-device connection
- Foreground pairing assistant keeps discovery alive while Android's pairing dialog is open
- Six-digit pairing code is entered directly from a notification
- Manual host/port entry is retained only as an advanced fallback
- Persists the ADB RSA/TLS identity
- Executes allowlisted `adb shell` commands directly from the phone
- GitHub Actions builds a debug APK

## Why Android Settings may show another IP

Android's Wireless debugging page may display an address such as:

```text
172.19.0.1:37145
```

For same-device operation this does not mean the app must connect to `172.19.0.1`.

The primary flow follows Shizuku's approach:

1. Discover the ADB mDNS service.
2. Resolve the advertised address and verify it belongs to this device.
3. Keep the discovered port.
4. Connect to `127.0.0.1:<port>`.

The pairing port and connection port are separate services and normally have different random ports.

## First use

1. Tap **Start Shizuku-style pairing**.
2. Allow notification permission if Android asks.
3. The app opens **Wireless debugging**.
4. Tap **Pair device with pairing code** and keep that system dialog open.
5. Wait for the GPT Android Use notification to say the pairing service was found.
6. Enter the six-digit code directly in that notification.
7. Return to GPT Android Use and tap **Discover & connect**.
8. Execute a supported command such as:
   - `input tap 500 500`
   - `input swipe 500 1500 500 500 300`
   - `dumpsys window`
   - `pm list packages`

No computer or root is required.

## Advanced fallback

If a ROM does not expose the local ADB service on loopback, manual fields allow using the address and ports shown by Android Settings, such as `172.19.0.1`.

## Build

GitHub Actions produces an artifact named `GPT-Android-Use-debug`.

```bash
gradle assembleDebug
```

Minimum Android version: Android 11 (API 30).
