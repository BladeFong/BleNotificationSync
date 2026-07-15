package com.ble.notification.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkErrorTest {

    @Test
    fun `each subtype carries message`() {
        assertEquals("蓝牙未开启", SdkError.BluetoothDisabled().message)
        assertEquals("设备不支持蓝牙", SdkError.BluetoothUnavailable().message)
        assertTrue(SdkError.PermissionDenied(listOf("CAMERA")).message.contains("CAMERA"))
        assertEquals("未配对的设备", SdkError.NotPaired().message)
        assertTrue(SdkError.DeviceNotFound("AA:BB").message.contains("AA:BB"))
        assertEquals("GATT 服务未找到", SdkError.ServiceNotFound().message)
        assertTrue(SdkError.ConnectionFailed("timeout").message.contains("timeout"))
        assertTrue(SdkError.WriteFailed("busy").message.contains("busy"))
        assertEquals("加密失败", SdkError.EncryptionFailed().message)
        assertEquals("操作超时", SdkError.Timeout().message)
        assertEquals("SDK 已关闭", SdkError.Closed().message)
        assertTrue(SdkError.Unknown("test").message.contains("test"))
    }

    @Test
    fun `subtypes are distinct for when branching`() {
        val e: SdkError = SdkError.Timeout()
        val result = when (e) {
            is SdkError.BluetoothDisabled -> "bt"
            is SdkError.Timeout -> "timeout"
            else -> "other"
        }
        assertEquals("timeout", result)
    }
}
