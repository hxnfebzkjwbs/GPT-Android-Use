# Third-party notices

## libadb-android

This project depends on [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android), which is dual-licensed under Apache License 2.0 or GPL-3.0-or-later.

The local `AdbConnectionManager.java` identity/certificate setup code is adapted from the library's test application and used under Apache License 2.0.

## Shizuku

The Wireless ADB service-discovery and pairing UX in version 0.3.0 was designed with reference to the open-source [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) implementation, including its use of Android NSD for `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp`, local-interface validation, loopback connections, and notification-based pairing workflow.

Shizuku is licensed under Apache License 2.0.

## sun-security-android

Used for generating the X.509 certificate required by the ADB TLS identity.

## Conscrypt

Used as the TLS provider for Android Wireless ADB pairing/connection.
