package com.ble.notification.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class FrameDecoderTest {

    private companion object {
        const val MAGIC_BYTE_0: Byte = 0xAA.toByte()
        const val MAGIC_BYTE_1: Byte = 0xBB.toByte()
        const val HEADER_SIZE = 5
    }

    @Test
    fun `decode REGISTER frame`() {
        val encoded = FrameEncoder.encodeRegister("JustNow", "com.nearby.justnow")

        val frame = FrameDecoder.decode(encoded)

        assertNotNull(frame)
        assertEquals(MessageType.REGISTER, frame!!.type)
        assertEquals(0, frame.seq)
        assertEquals(1, frame.totalSeq)
        assertNotNull(frame.payload)

        val json = String(frame.payload!!, Charsets.UTF_8)
        assert(json.contains("JustNow"))
        assert(json.contains("com.nearby.justnow"))
    }

    @Test
    fun `decode returns null for invalid magic`() {
        val data = byteArrayOf(
            0x00, 0x00, // wrong magic
            MessageType.REGISTER.value,
            0, 1
        )

        assertNull(FrameDecoder.decode(data))
    }

    @Test
    fun `decode returns null for data too short`() {
        val data = byteArrayOf(MAGIC_BYTE_0, MAGIC_BYTE_1)

        assertNull(FrameDecoder.decode(data))
    }

    @Test
    fun `decode handles empty payload`() {
        val data = byteArrayOf(
            MAGIC_BYTE_0, MAGIC_BYTE_1,
            MessageType.REGISTER.value,
            0, 1
        )

        val frame = FrameDecoder.decode(data)

        assertNotNull(frame)
        assertEquals(MessageType.REGISTER, frame!!.type)
        assertEquals(0, frame.seq)
        assertEquals(1, frame.totalSeq)
        assertNull(frame.payload)
    }

    @Test
    fun `decode ICON_DATA frame preserves raw bytes`() {
        val iconBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val encoded = FrameEncoder.encodeIconData(iconBytes, 2, 5)

        val frame = FrameDecoder.decode(encoded)

        assertNotNull(frame)
        assertEquals(MessageType.ICON_DATA, frame!!.type)
        assertEquals(2, frame.seq)
        assertEquals(5, frame.totalSeq)
        assertArrayEquals(iconBytes, frame.payload)
    }

    @Test
    fun `decode ICON_END frame`() {
        val encoded = FrameEncoder.encodeIconEnd(60000)

        val frame = FrameDecoder.decode(encoded)

        assertNotNull(frame)
        assertEquals(MessageType.ICON_END, frame!!.type)
        assertEquals(0, frame.seq)
        assertEquals(1, frame.totalSeq)
        assertNotNull(frame.payload)

        val json = String(frame.payload!!, Charsets.UTF_8)
        assert(json.contains("total_size"))
        assert(json.contains("60000"))
    }
}
