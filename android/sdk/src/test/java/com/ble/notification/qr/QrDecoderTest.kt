package com.ble.notification.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    @Test
    fun `parseQrCode parses valid URL`() {
        val url = "ble://pair?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"

        val result = QrDecoder.parseQrCode(url)

        assertEquals("AA:BB:CC:DD:EE:FF", result?.mac)
        assertEquals("0000A1B2-0000-1000-8000-00805F9B34FB", result?.uuid)
    }

    @Test
    fun `parseQrCode returns null for empty string`() {
        assertNull(QrDecoder.parseQrCode(""))
    }

    @Test
    fun `parseQrCode returns null for blank string`() {
        assertNull(QrDecoder.parseQrCode("   "))
    }

    @Test
    fun `parseQrCode returns null for missing mac`() {
        val url = "ble://pair?uuid=0000A1B2-0000-1000-8000-00805F9B34FB"
        assertNull(QrDecoder.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for missing uuid`() {
        val url = "ble://pair?mac=AA:BB:CC:DD:EE:FF"
        assertNull(QrDecoder.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for wrong scheme`() {
        val url = "http://pair?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"
        assertNull(QrDecoder.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for wrong host`() {
        val url = "ble://connect?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"
        assertNull(QrDecoder.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for malformed URL`() {
        assertNull(QrDecoder.parseQrCode("not-a-url"))
    }

    @Test
    fun `parseQrCode handles different MAC formats`() {
        val url = "ble://pair?mac=00:11:22:33:44:55&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"

        val result = QrDecoder.parseQrCode(url)

        assertEquals("00:11:22:33:44:55", result?.mac)
        assertEquals("0000A1B2-0000-1000-8000-00805F9B34FB", result?.uuid)
    }

    @Test
    fun `parseQrCode returns null for extra unknown parameters`() {
        val url = "ble://pair?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB&extra=value"

        val result = QrDecoder.parseQrCode(url)

        assertEquals("AA:BB:CC:DD:EE:FF", result?.mac)
        assertEquals("0000A1B2-0000-1000-8000-00805F9B34FB", result?.uuid)
    }
}
