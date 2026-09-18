# GPT Android Use

Android-side bridge for controlling the local device through user-authorized Wireless ADB.

## Current capabilities

- Android 11+ (API 30+) baseline
- Opens Wireless debugging settings
- Pairs to the same phone with Android's 6-digit Wireless ADB pairing code
- Uses the actual Wireless debugging IP instead of assuming 127.0.0.1
- Supports manual entry of both the pairing port and the separate connection port
- Auto-discovers the connection port with mDNS as an optional fallback
- Persists the ADB RSA/TLS identity
- Executes allowlisted `adb shell` commands directly from the phone
- OEM guidance for Xiaomi/Redmi/POCO, OPPO/OnePlus/realme and Huawei
- GitHub Actions builds a debug APK on every push / pull request

## First use

Android Wireless ADB normally exposes two ports:

1. **Pairing port** — shown after tapping **Pair device with pairing code**. This port requires the 6-digit code.
2. **Connection port** — shown on the main **Wireless debugging** screen. This port does not ask for the pairing code after the key has been paired.

Example:

```text
Wireless debugging address: 172.19.0.1
Pairing port:              42817
Pairing code:              123456
Connection port:           37145
```

In the app:

1. Enter the Wireless debugging IP, e.g. `172.19.0.1`.
2. Enter the pairing port and 6-digit code, then tap **Pair this phone**.
3. Enter the connection port from the Wireless debugging main page and tap **Connect using address and port**.
4. Alternatively, try **Auto-discover connection port (mDNS)**.
5. Execute a supported command such as:
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
