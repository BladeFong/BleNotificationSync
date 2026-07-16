package com.ble.notification.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    @Test
    fun `parseQrCode parses valid URL`() {
        val url = "ble://pair?mac=AA:BB:CC:DD:EE:FF&uuid=9e1d51a4-9c86-4447-9759-f6222b0f4b36"

        val result = QrDecoder.parseQrCode(url)

        assertEquals("AA:BB:CC:DD:EE:FF", result?.mac)
        assertEquals("9e1d51a4-9c86-4447-9759-f6222b0f4b36", result?.uuid)
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
        val url = "ble://pair?uuid=9e1d51a4-9c86-4447-9759-f6222b0f4b36"
        assertNull(QrDecoder.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for missing uuid`() {
        val url = "ble://pair?mac=AA:BB:CC:DD:EE:FF"
        assertNull(QrDecoder.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for wrong scheme`() {
        val url = "http://pair?mac=AA:BB:CC:DD:EE:FF&uuid=9e1d51a4-9c86-4447-9759-f6222b0f4b36"
        assertNull(QrDecoder.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for wrong host`() {
        val url = "ble://connect?mac=AA:BB:CC:DD:EE:FF&uuid=9e1d51a4-9c86-4447-9759-f6222b0f4b36"
        assertNull(QrDecoder.parseQrCode(url))
    }

    @Test
    fun `parseQrCode returns null for malformed URL`() {
        assertNull(QrDecoder.parseQrCode("not-a-url"))
    }

    @Test
    fun `parseQrCode handles different MAC formats`() {
        val url = "ble://pair?mac=00:11:22:33:44:55&uuid=9e1d51a4-9c86-4447-9759-f6222b0f4b36"

        val result = QrDecoder.parseQrCode(url)

        assertEquals("00:11:22:33:44:55", result?.mac)
        assertEquals("9e1d51a4-9c86-4447-9759-f6222b0f4b36", result?.uuid)
    }

    @Test
    fun `parseQrCode returns null for extra unknown parameters`() {
        val url = "ble://pair?mac=AA:BB:CC:DD:EE:FF&uuid=9e1d51a4-9c86-4447-9759-f6222b0f4b36&extra=value"

        val result = QrDecoder.parseQrCode(url)

        assertEquals("AA:BB:CC:DD:EE:FF", result?.mac)
        assertEquals("9e1d51a4-9c86-4447-9759-f6222b0f4b36", result?.uuid)
    }
}
