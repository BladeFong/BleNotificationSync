# BLE Notification Sync SDK — Consumer ProGuard / R8 Rules
# These rules are automatically merged into consuming apps and protect
# SDK classes that rely on JNI, reflection, or are part of the public API.

# ── JNI bridge (NativeCrypto uses System.loadLibrary + @JvmStatic extern) ──
-keep class com.ble.notification.crypto.NativeCrypto { *; }

# ── Public API — keep class structure for integrator callbacks ──
-keep class com.ble.notification.sdk.BleNotificationSDK { public *; }
-keep class com.ble.notification.sdk.SdkError { *; }
-keep class com.ble.notification.sdk.SendCallback { *; }
-keep class com.ble.notification.sdk.NotificationAction { *; }

# ── Pairing — callback interface + data class used with EncryptedSharedPreferences ──
-keep class com.ble.notification.pairing.PairingCallback { *; }
-keep class com.ble.notification.pairing.PairingManager { public *; }
-keep class com.ble.notification.pairing.PairedDevice { *; }

# ── BLE connection callback ──
-keep class com.ble.notification.ble.ConnectionCallback { *; }

# ── Protocol frames (FrameEncoder/FrameDecoder/MessageType used in cross-platform sync) ──
-keep class com.ble.notification.protocol.Frame { *; }
-keep class com.ble.notification.protocol.MessageType { *; }

# ── QR scanning (CameraX integration via Fragment) ──
-keep class com.ble.notification.qr.QrResult { *; }
-keep class com.ble.notification.qr.QrScannerFragment { *; }

# ── BleForegroundService (instantiated via Intent by consuming app) ──
-keep class com.ble.notification.sdk.BleForegroundService { *; }

# ── BleScanWorker (WorkManager reflection) ──
-keep class com.ble.notification.sdk.BleScanWorker { *; }
