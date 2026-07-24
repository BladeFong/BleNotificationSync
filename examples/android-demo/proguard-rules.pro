# BLE Notification Sync Demo — ProGuard / R8 Rules

# Room database (WorkManager + our own if any) — keep no-arg constructors for reflection
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager InitializationProvider
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class androidx.startup.InitializationProvider { *; }

# CameraX + ML Kit Barcode (heavy JNI/reflection usage)
-keep class androidx.camera.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# SDK native JNI bridge
-keep class com.ble.notification.crypto.NativeCrypto { *; }

# Keep pairing data classes (used via reflection in EncryptedSharedPreferences)
-keep class com.ble.notification.pairing.PairedDevice { *; }
-keep class com.ble.notification.pairing.PairingState { *; }

# Keep SDK public API
-keep class com.ble.notification.sdk.BleNotificationSDK { public *; }
-keep class com.ble.notification.sdk.SdkError { *; }
-keep class com.ble.notification.sdk.SendCallback { *; }
-keep class com.ble.notification.sdk.NotificationAction { *; }

# Keep protocol & pairing callback interfaces
-keep class com.ble.notification.protocol.** { *; }
-keep class com.ble.notification.pairing.PairingCallback { *; }
-keep class com.ble.notification.pairing.PairingManager { public *; }
-keep class com.ble.notification.ble.ConnectionCallback { *; }
