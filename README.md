# GPT Android Use

Android-side bridge for controlling the local device through user-authorized Wireless ADB.

## Scope

- Android 11+ (API 30+) baseline
- Detect whether Wireless debugging is available
- Open Developer options / Wireless debugging settings
- Detect common OEM-specific ADB restrictions
- Local command broker with an explicit allowlist
- Wireless ADB pairing and transport layer (in progress)

## Security model

This app does not attempt to bypass Android authorization. The user must enable Developer options and Wireless debugging, then explicitly pair the app/device. Commands are filtered before execution.

## OEM notes

- Xiaomi / Redmi / POCO: some actions may require **USB debugging (Security settings)**.
- OPPO / OnePlus / realme: some ROM versions restrict ADB through permission monitoring/system optimization controls.
- Huawei EMUI / HarmonyOS 2–4.x commonly do not expose standard Android Wireless debugging.

## Development

Open the project with Android Studio and build the `app` module.

Minimum Android version: Android 11 (API 30).
