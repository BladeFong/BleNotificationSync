package com.ble.notification.ble

import com.ble.notification.qr.QrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleClientTest {

    @Test
    fun `parseQrCode parses valid URL`() {
        val url = "ble://pair?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"

        val result = BleClient.parseQrCode(url)

        assertEquals("AA:BB:CC:DD:EE:FF", result?.mac)
        assertEquals("0000A1B2-0000-1000-8000-00805F9B34FB", result?.uuid)
    }

    @Test
    fun `parseQrCode returns null for empty string`() {
        assertNull(BleClient.parseQrCode(""))
    }

    @Test
    fun `parseQrCode returns null for missing mac`() {
        val url = "ble://pair?uuid=0000A1B2-0000-1000-8000-00805F9B34FB"
        assertNull(BleClient.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for missing uuid`() {
        val url = "ble://pair?mac=AA:BB:CC:DD:EE:FF"
        assertNull(BleClient.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for wrong scheme`() {
        val url = "http://pair?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"
        assertNull(BleClient.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for malformed URL`() {
        assertNull(BleClient.parseQrCode("not-a-url"))
    }
}
