package com.ble.notification.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCryptoTest {

    // ── AES-CCM ──────────────────────────────────────────────────────

    @Test
    fun `aesCcm encrypt then decrypt returns original plaintext`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 0xA0).toByte() }
        val plaintext = "Hello, BLE!".toByteArray(Charsets.UTF_8)

        val ciphertext = NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)
        assertNotNull("encrypt should succeed", ciphertext)
        assertTrue("ciphertext should be plaintext + 16 byte tag",
            ciphertext!!.size == plaintext.size + 16)

        val decrypted = NativeCrypto.aesCcmDecrypt(key, nonce, ciphertext)
        assertNotNull("decrypt should succeed", decrypted)
        assertArrayEquals("decrypted must equal original", plaintext, decrypted)
    }

    @Test
    fun `aesCcm decrypt with wrong key returns null`() {
        val key1 = ByteArray(32) { it.toByte() }
        val key2 = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 0xA0).toByte() }
        val plaintext = "Secret message".toByteArray(Charsets.UTF_8)

        val ciphertext = NativeCrypto.aesCcmEncrypt(key1, nonce, plaintext)
        assertNotNull(ciphertext)

        val decrypted = NativeCrypto.aesCcmDecrypt(key2, nonce, ciphertext!!)
        assertNull("wrong key must fail authentication", decrypted)
    }

    @Test
    fun `aesCcm decrypt with wrong nonce returns null`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce1 = ByteArray(12) { (it + 0xA0).toByte() }
        val nonce2 = ByteArray(12) { (it + 0xB0).toByte() }
        val plaintext = "Another message".toByteArray(Charsets.UTF_8)

        val ciphertext = NativeCrypto.aesCcmEncrypt(key, nonce1, plaintext)
        assertNotNull(ciphertext)

        val decrypted = NativeCrypto.aesCcmDecrypt(key, nonce2, ciphertext!!)
        assertNull("wrong nonce must fail authentication", decrypted)
    }

    @Test
    fun `aesCcm encrypt is deterministic for same inputs`() {
        val key = ByteArray(32) { 0x42 }
        val nonce = ByteArray(12) { 0x55 }
        val plaintext = "deterministic".toByteArray(Charsets.UTF_8)

        val ct1 = NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)
        val ct2 = NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)
        assertNotNull(ct1)
        assertArrayEquals("same inputs must produce same ciphertext", ct1, ct2)
    }

    @Test
    fun `aesCcm handles empty plaintext`() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(13) { (it + 1).toByte() }
        val plaintext = ByteArray(0)

        val ciphertext = NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)
        assertNotNull("empty plaintext should still produce tag", ciphertext)
        assertEquals("empty plaintext output should be just the 16-byte tag", 16, ciphertext!!.size)

        val decrypted = NativeCrypto.aesCcmDecrypt(key, nonce, ciphertext)
        assertNotNull("decrypt of empty plaintext should succeed", decrypted)
        assertEquals(0, decrypted!!.size)
    }

    @Test
    fun `aesCcm handles 16-byte key (minimum AES-128)`() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val nonce = ByteArray(8) { (it + 0x10).toByte() }
        val plaintext = "AES-128-CCM".toByteArray(Charsets.UTF_8)

        val ciphertext = NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)
        assertNotNull(ciphertext)

        val decrypted = NativeCrypto.aesCcmDecrypt(key, nonce, ciphertext!!)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `aesCcm handles large payload (BLE icon data simulation)`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 0x30).toByte() }
        val plaintext = ByteArray(240) { (it % 256).toByte() } // max BLE payload

        val ciphertext = NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)
        assertNotNull(ciphertext)
        assertEquals(240 + 16, ciphertext!!.size)

        val decrypted = NativeCrypto.aesCcmDecrypt(key, nonce, ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    // ── HKDF-SHA256 ─────────────────────────────────────────────────

    @Test
    fun `hkdfSha256 produces deterministic output`() {
        val salt = "BleNotificationSync".toByteArray(Charsets.UTF_8)
        val info = "com.test.app".toByteArray(Charsets.UTF_8)

        val key1 = NativeCrypto.hkdfSha256(salt, info, 32)
        val key2 = NativeCrypto.hkdfSha256(salt, info, 32)

        assertNotNull(key1)
        assertNotNull(key2)
        assertArrayEquals("same inputs must produce same key", key1, key2)
        assertEquals(32, key1!!.size)
    }

    @Test
    fun `hkdfSha256 different info produces different keys`() {
        val salt = "BleNotificationSync".toByteArray(Charsets.UTF_8)
        val info1 = "com.app.one".toByteArray(Charsets.UTF_8)
        val info2 = "com.app.two".toByteArray(Charsets.UTF_8)

        val key1 = NativeCrypto.hkdfSha256(salt, info1, 32)
        val key2 = NativeCrypto.hkdfSha256(salt, info2, 32)

        assertNotNull(key1)
        assertNotNull(key2)
        assertTrue("different info must produce different keys",
            !key1.contentEquals(key2!!))
    }

    @Test
    fun `hkdfSha256 different salt produces different keys`() {
        val salt1 = "Salt1".toByteArray(Charsets.UTF_8)
        val salt2 = "Salt2".toByteArray(Charsets.UTF_8)
        val info = "com.test.app".toByteArray(Charsets.UTF_8)

        val key1 = NativeCrypto.hkdfSha256(salt1, info, 32)
        val key2 = NativeCrypto.hkdfSha256(salt2, info, 32)

        assertNotNull(key1)
        assertNotNull(key2)
        assertTrue("different salt must produce different keys",
            !key1.contentEquals(key2!!))
    }

    @Test
    fun `hkdfSha256 produces requested length`() {
        val salt = "salt".toByteArray(Charsets.UTF_8)
        val info = "info".toByteArray(Charsets.UTF_8)

        val key16 = NativeCrypto.hkdfSha256(salt, info, 16)
        val key32 = NativeCrypto.hkdfSha256(salt, info, 32)
        val key64 = NativeCrypto.hkdfSha256(salt, info, 64)

        assertNotNull(key16)
        assertNotNull(key32)
        assertNotNull(key64)
        assertEquals(16, key16!!.size)
        assertEquals(32, key32!!.size)
        assertEquals(64, key64!!.size)
    }

    @Test
    fun `hkdfSha256 with empty salt works`() {
        val salt = ByteArray(0)
        val info = "com.test.app".toByteArray(Charsets.UTF_8)

        val key = NativeCrypto.hkdfSha256(salt, info, 32)
        assertNotNull("empty salt should be valid (HKDF spec)", key)
        assertEquals(32, key!!.size)
    }

    // ── deriveKey helper ────────────────────────────────────────────

    @Test
    fun `deriveKey returns 32-byte key and is deterministic`() {
        val key1 = NativeCrypto.deriveKey("com.nearby.justnow")
        val key2 = NativeCrypto.deriveKey("com.nearby.justnow")

        assertEquals(32, key1.size)
        assertArrayEquals("deriveKey must be deterministic", key1, key2)
    }

    @Test
    fun `deriveKey different packages produce different keys`() {
        val key1 = NativeCrypto.deriveKey("com.app.one")
        val key2 = NativeCrypto.deriveKey("com.app.two")

        assertTrue("different packages must produce different keys",
            !key1.contentEquals(key2))
    }

    // ── Known test vector: RFC 5869 HKDF-SHA256 Test Case 1 ────────

    @Test
    fun `hkdfSha256 matches RFC 5869 test vector 1`() {
        // RFC 5869 Appendix A.1 — SHA-256
        // IKM  = 0x0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b (22 bytes)
        // salt = 0x000102030405060708090a0b0c (13 bytes)
        // info = 0xf0f1f2f3f4f5f6f7f8f9 (10 bytes)
        // L    = 42
        // OKM  = 0x3cb25f25faacd57a90434f64d0362f2a
        //        2d2d0a90cf1a5a4c5db02d56ecc4c5bf
        //        34007208d5b887185865

        val ikm  = byteArrayOf(
            0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
            0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
            0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b
        )
        val salt = byteArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c
        )
        val info = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(),
            0xf8.toByte(), 0xf9.toByte()
        )
        val expected = byteArrayOf(
            0x3c, 0xb2.toByte(), 0x5f, 0x25, 0xfa.toByte(), 0xac.toByte(), 0xd5.toByte(), 0x7a,
            0x90.toByte(), 0x43, 0x4f, 0x64, 0xd0.toByte(), 0x36, 0x2f, 0x2a,
            0x2d, 0x2d, 0x0a, 0x90.toByte(), 0xcf.toByte(), 0x1a, 0x5a, 0x4c,
            0x5d, 0xb0.toByte(), 0x2d, 0x56, 0xec.toByte(), 0xc4.toByte(), 0xc5.toByte(), 0xbf.toByte(),
            0x34, 0x00, 0x72, 0x08, 0xd5.toByte(), 0xb8.toByte(), 0x87.toByte(), 0x18,
            0x58, 0x65
        )

        // RFC 5869 uses: Extract(salt, IKM) then Expand(PRK, info, L)
        // Our hkdfSha256 maps: salt → salt, info → IKM (for extract) + info (for expand)
        // To match RFC 5869 exactly, we need to call extract+expand with IKM as the "in" param.
        // Our wrapper uses info as IKM, so we pass ikm as the info param and salt as salt.
        val result = NativeCrypto.hkdfSha256(salt, ikm, 42)

        assertNotNull("RFC 5869 test vector must succeed", result)
        assertArrayEquals("RFC 5869 test vector mismatch", expected, result)
    }
}
