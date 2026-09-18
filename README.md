# GPT Android Use

Android-side bridge for controlling the local device through user-authorized Wireless ADB.

## Current capabilities

- Android 11+ (API 30+) baseline
- Opens Wireless debugging settings
- Pairs to the same phone with Android's 6-digit Wireless ADB pairing code
- Persists the ADB RSA/TLS identity
- Auto-discovers and reconnects to an already paired Wireless ADB endpoint
- Executes allowlisted `adb shell` commands directly from the phone
- OEM guidance for Xiaomi/Redmi/POCO, OPPO/OnePlus/realme and Huawei
- GitHub Actions builds a debug APK on every push / pull request

## First use

1. Open **Developer options → Wireless debugging**.
2. Tap **Pair device with pairing code**.
3. In this app enter the displayed pairing port and six-digit code.
4. Tap **Pair this phone**.
5. After connection, execute a supported command such as:
   - `input tap 500 500`
   - `input swipe 500 1500 500 500 300`
   - `dumpsys window`
   - `pm list packages`

The app does not bypass Android's pairing authorization and does not require root or a computer.

## Build

The GitHub Actions workflow produces an artifact named `GPT-Android-Use-debug`.

Local build:

```bash
gradle assembleDebug
```

Minimum Android version: Android 11 (API 30).
