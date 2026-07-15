package com.ble.notification.sdk

/**
 * SDK 结构化错误类型。所有回调的 onError 使用此密封类替代 String。
 * [message] 仅作附加描述，调用方按类型分支处理。
 */
sealed class SdkError(val message: String) {
    class BluetoothDisabled : SdkError("蓝牙未开启")
    class BluetoothUnavailable : SdkError("设备不支持蓝牙")
    class PermissionDenied(permissions: List<String>) : SdkError("缺少权限: $permissions")
    class NotPaired : SdkError("未配对的设备")
    class DeviceNotFound(mac: String) : SdkError("未找到设备: $mac")
    class ServiceNotFound : SdkError("GATT 服务未找到")
    class ConnectionFailed(cause: String) : SdkError("连接失败: $cause")
    class WriteFailed(cause: String) : SdkError("写入失败: $cause")
    class EncryptionFailed : SdkError("加密失败")
    class Timeout : SdkError("操作超时")
    class Closed : SdkError("SDK 已关闭")
    class Unknown(cause: String) : SdkError(cause)
}
