package com.ble.notification.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameEncoderTest {

    private companion object {
        const val MAGIC_BYTE_0: Byte = 0xAA.toByte()
        const val MAGIC_BYTE_1: Byte = 0xBB.toByte()
        const val HEADER_SIZE = 5
        const val NONCE_SIZE = 12
        const val AUTH_TAG_SIZE = 16
    }

    // ── REGISTER ─────────────────────────────────────────────────

    @Test
    fun `encodeRegister has correct magic and type`() {
        val frame = FrameEncoder.encodeRegister("JustNow", "com.nearby.justnow", ByteArray(32))

        assertEquals(MAGIC_BYTE_0, frame[0])
        assertEquals(MAGIC_BYTE_1, frame[1])
        assertEquals(MessageType.REGISTER.value, frame[2])
    }

    @Test
    fun `encodeRegister single frame Seq=0 TotalSeq=1`() {
        val frame = FrameEncoder.encodeRegister("JustNow", "com.nearby.justnow", ByteArray(32))

        assertEquals(0, frame[3].toInt() and 0xFF)
        assertEquals(1, frame[4].toInt() and 0xFF)
    }

    @Test
    fun `encodeRegister payload is valid JSON`() {
        val frame = FrameEncoder.encodeRegister("TestApp", "com.example.test", ByteArray(32))
        val payload = String(frame, HEADER_SIZE, frame.size - HEADER_SIZE, Charsets.UTF_8)

        assertTrue("payload should contain app_name", payload.contains("\"app_name\""))
        assertTrue("payload should contain package", payload.contains("\"package\""))
        assertTrue("payload should contain random", payload.contains("\"random\""))
        assertTrue("payload should contain TestApp", payload.contains("TestApp"))
        assertTrue("payload should contain com.example.test", payload.contains("com.example.test"))
        assertTrue("payload should start with {", payload.startsWith("{"))
        assertTrue("payload should end with }", payload.endsWith("}"))
    }

    // ── NOTIFY ──────────────────────────────────────────────────

    @Test
    fun `encodeNotify has correct magic and type`() {
        val frame = FrameEncoder.encodeNotify("com.example", "Title", "Body", 12345L)

        assertEquals(MAGIC_BYTE_0, frame[0])
        assertEquals(MAGIC_BYTE_1, frame[1])
        assertEquals(MessageType.NOTIFY.value, frame[2])
    }

    @Test
    fun `encodeNotify single frame Seq=0 TotalSeq=1`() {
        val frame = FrameEncoder.encodeNotify("com.example", "T", "B", 0L)

        assertEquals(0, frame[3].toInt() and 0xFF)
        assertEquals(1, frame[4].toInt() and 0xFF)
    }

    @Test
    fun `encodeNotify has PackageLen followed by package name bytes`() {
        val pkg = "com.example.app"
        val frame = FrameEncoder.encodeNotify(pkg, "Title", "Body", 1000L)

        val packageLen = frame[5].toInt() and 0xFF
        assertEquals("PackageLen must match package name length", pkg.length, packageLen)

        val packageBytes = frame.sliceArray(6 until 6 + packageLen)
        assertEquals(pkg, String(packageBytes, Charsets.UTF_8))
    }

    @Test
    fun `encodeNotify has 12-byte nonce after package`() {
        val pkg = "com.example"
        val frame = FrameEncoder.encodeNotify(pkg, "T", "B", 0L)

        val packageLen = frame[5].toInt() and 0xFF
        val nonceOffset = 6 + packageLen
        val ciphertextOffset = nonceOffset + NONCE_SIZE

        // After package: nonce(12) + ciphertext (min 16 bytes for auth tag)
        assertTrue(
            "frame must contain nonce + ciphertext after package",
            frame.size >= ciphertextOffset + AUTH_TAG_SIZE
        )
    }

    @Test
    fun `encodeNotify ciphertext includes 16-byte auth tag`() {
        val pkg = "com.example"
        val frame = FrameEncoder.encodeNotify(pkg, "Title", "Body", 12345L)

        val packageLen = frame[5].toInt() and 0xFF
        val nonceOffset = 6 + packageLen
        val ciphertextSize = frame.size - nonceOffset - NONCE_SIZE

        assertTrue(
            "ciphertext must be at least AUTH_TAG_SIZE (16 bytes)",
            ciphertextSize >= AUTH_TAG_SIZE
        )
    }

    @Test
    fun `encodeNotify total frame size is header plus packageLen plus package plus nonce plus ciphertext`() {
        val pkg = "com.test"
        val frame = FrameEncoder.encodeNotify(pkg, "Hi", "There", 999L)

        val packageLen = frame[5].toInt() and 0xFF
        val expectedMinSize = HEADER_SIZE + 1 + packageLen + NONCE_SIZE + AUTH_TAG_SIZE
        assertTrue(
            "frame size must be at least header+1+pkgLen+nonce+ciphertext",
            frame.size >= expectedMinSize
        )
    }

    // ── ICON_DATA ───────────────────────────────────────────────

    @Test
    fun `encodeIconData has correct magic and type`() {
        val icon = ByteArray(10) { it.toByte() }
        val frame = FrameEncoder.encodeIconData(icon, 0, 1)

        assertEquals(MAGIC_BYTE_0, frame[0])
        assertEquals(MAGIC_BYTE_1, frame[1])
        assertEquals(MessageType.ICON_DATA.value, frame[2])
    }

    @Test
    fun `encodeIconData single frame Seq=0 TotalSeq=1`() {
        val icon = ByteArray(5) { 0x42 }
        val frame = FrameEncoder.encodeIconData(icon, 0, 1)

        assertEquals(0, frame[3].toInt() and 0xFF)
        assertEquals(1, frame[4].toInt() and 0xFF)
    }

    @Test
    fun `encodeIconData multi-frame has correct seq and totalSeq`() {
        val icon = ByteArray(100) { it.toByte() }
        val frame = FrameEncoder.encodeIconData(icon, 2, 5)

        assertEquals(2, frame[3].toInt() and 0xFF)
        assertEquals(5, frame[4].toInt() and 0xFF)
    }

    @Test
    fun `encodeIconData contains raw icon bytes after header`() {
        val icon = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val frame = FrameEncoder.encodeIconData(icon, 0, 1)

        assertEquals(HEADER_SIZE + icon.size, frame.size)
        val payload = frame.sliceArray(HEADER_SIZE until frame.size)
        assertArrayEquals(icon, payload)
    }

    // ── ICON_END ────────────────────────────────────────────────

    @Test
    fun `encodeIconEnd has correct magic and type`() {
        val frame = FrameEncoder.encodeIconEnd(12345)

        assertEquals(MAGIC_BYTE_0, frame[0])
        assertEquals(MAGIC_BYTE_1, frame[1])
        assertEquals(MessageType.ICON_END.value, frame[2])
    }

    @Test
    fun `encodeIconEnd single frame Seq=0 TotalSeq=1`() {
        val frame = FrameEncoder.encodeIconEnd(0)

        assertEquals(0, frame[3].toInt() and 0xFF)
        assertEquals(1, frame[4].toInt() and 0xFF)
    }

    @Test
    fun `encodeIconEnd payload contains total_size`() {
        val frame = FrameEncoder.encodeIconEnd(60000)
        val payload = String(frame, HEADER_SIZE, frame.size - HEADER_SIZE, Charsets.UTF_8)

        assertTrue("payload should contain total_size", payload.contains("total_size"))
        assertTrue("payload should contain 60000", payload.contains("60000"))
    }

    // ── Cross-cutting ───────────────────────────────────────────

    @Test
    fun `all message types produce valid magic`() {
        val register = FrameEncoder.encodeRegister("App", "com.test", ByteArray(32))
        val notify = FrameEncoder.encodeNotify("com.test", "T", "B", 0L)
        val iconData = FrameEncoder.encodeIconData(ByteArray(1), 0, 1)
        val iconEnd = FrameEncoder.encodeIconEnd(0)

        for (frame in listOf(register, notify, iconData, iconEnd)) {
            assertEquals(MAGIC_BYTE_0, frame[0])
            assertEquals(MAGIC_BYTE_1, frame[1])
        }
    }

    @Test
    fun `all message types have 5-byte header minimum`() {
        val register = FrameEncoder.encodeRegister("A", "com.x")
        val notify = FrameEncoder.encodeNotify("com.x", "T", "B", 0L)
        val iconData = FrameEncoder.encodeIconData(ByteArray(1), 0, 1)
        val iconEnd = FrameEncoder.encodeIconEnd(0)

        for (frame in listOf(register, notify, iconData, iconEnd)) {
            assertTrue("every frame must be at least HEADER_SIZE", frame.size >= HEADER_SIZE)
        }
    }
}
