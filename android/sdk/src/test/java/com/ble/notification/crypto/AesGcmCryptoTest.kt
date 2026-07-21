package com.ble.notification.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AesGcmCryptoTest {

    private val testKey = ByteArray(32) { it.toByte() }

    // ── Round-trip ─────────────────────────────────────────────────

    @Test
    fun `encrypt then decrypt round-trip succeeds`() {
        val plaintext = "Hello, BLE!".toByteArray(Charsets.UTF_8)

        val payload = AesGcmCrypto.encrypt(testKey, plaintext)
        assertNotNull("encrypt should succeed", payload)

        val decrypted = AesGcmCrypto.decrypt(testKey, payload.nonce, payload.ciphertext)
        assertNotNull("decrypt should succeed", decrypted)
        assertArrayEquals("decrypted must equal original", plaintext, decrypted)
    }

    @Test
    fun `different key fails to decrypt`() {
        val key1 = ByteArray(32) { 0x01 }
        val key2 = ByteArray(32) { 0x02 }
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val payload = AesGcmCrypto.encrypt(key1, plaintext)
        assertNotNull(payload)

        val decrypted = AesGcmCrypto.decrypt(key2, payload.nonce, payload.ciphertext)
        assertNull("different key must not decrypt", decrypted)
    }

    @Test
    fun `nonce is 12 bytes`() {
        val plaintext = "Test nonce length".toByteArray(Charsets.UTF_8)

        val payload = AesGcmCrypto.encrypt(testKey, plaintext)
        assertNotNull(payload)
        assertEquals("nonce must be 12 bytes", 12, payload.nonce.size)
    }

    @Test
    fun `ciphertext size is plaintext plus 16-byte tag`() {
        val plaintext = "Size check".toByteArray(Charsets.UTF_8)

        val payload = AesGcmCrypto.encrypt(testKey, plaintext)
        assertNotNull(payload)
        assertEquals("ciphertext must be plaintext + 16", plaintext.size + 16, payload.ciphertext.size)
    }

    @Test
    fun `handles empty plaintext`() {
        val plaintext = ByteArray(0)

        val payload = AesGcmCrypto.encrypt(testKey, plaintext)
        assertNotNull(payload)
        assertEquals("empty plaintext ciphertext should be just tag", 16, payload.ciphertext.size)

        val decrypted = AesGcmCrypto.decrypt(testKey, payload.nonce, payload.ciphertext)
        assertNotNull(decrypted)
        assertEquals(0, decrypted!!.size)
    }
}
