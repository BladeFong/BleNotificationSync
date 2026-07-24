package com.ble.notification.sdk

/**
 * SDK structured error types. All onError callbacks use this sealed class instead of String.
 * [message] is for diagnostic purposes only; callers should branch on type.
 */
sealed class SdkError(val message: String) {
    class BluetoothDisabled : SdkError("Bluetooth is disabled")
    class BluetoothUnavailable : SdkError("Device does not support Bluetooth")
    class PermissionDenied(permissions: List<String>) : SdkError("Missing permissions: $permissions")
    class NotPaired : SdkError("No paired device")
    class DeviceNotFound(mac: String) : SdkError("Device not found: $mac")
    class ServiceNotFound : SdkError("GATT service not found")
    class ConnectionFailed(cause: String) : SdkError("Connection failed: $cause")
    class WriteFailed(cause: String) : SdkError("Write failed: $cause")
    class EncryptionFailed : SdkError("Encryption failed")
    class Timeout : SdkError("Operation timed out")
    class Closed : SdkError("SDK closed")
    class AlreadyPaired(msg: String = "Device already paired") : SdkError(msg)
    class Unknown(cause: String) : SdkError(cause)
}
