package com.ble.notification.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkErrorTest {

    @Test
    fun `each subtype carries message`() {
        assertEquals("Bluetooth is disabled", SdkError.BluetoothDisabled().message)
        assertEquals("Device does not support Bluetooth", SdkError.BluetoothUnavailable().message)
        assertTrue(SdkError.PermissionDenied(listOf("CAMERA")).message.contains("CAMERA"))
        assertEquals("No paired device", SdkError.NotPaired().message)
        assertTrue(SdkError.DeviceNotFound("AA:BB").message.contains("AA:BB"))
        assertEquals("GATT service not found", SdkError.ServiceNotFound().message)
        assertTrue(SdkError.ConnectionFailed("timeout").message.contains("timeout"))
        assertTrue(SdkError.WriteFailed("busy").message.contains("busy"))
        assertEquals("Encryption failed", SdkError.EncryptionFailed().message)
        assertEquals("Operation timed out", SdkError.Timeout().message)
        assertEquals("SDK closed", SdkError.Closed().message)
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
