package com.ble.notification.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AesCcmCryptoTest {

    // ── Round-trip ─────────────────────────────────────────────────

    @Test
    fun `encrypt then decrypt round-trip succeeds`() {
        val packageName = "com.example.test"
        val plaintext = "Hello, BLE!".toByteArray(Charsets.UTF_8)

        val payload = AesCcmCrypto.encrypt(packageName, plaintext)
        assertNotNull("encrypt should succeed", payload)

        val decrypted = AesCcmCrypto.decrypt(packageName, payload.nonce, payload.ciphertext)
        assertNotNull("decrypt should succeed", decrypted)
        assertArrayEquals("decrypted must equal original", plaintext, decrypted)
    }

    @Test
    fun `different packageName fails to decrypt`() {
        val packageName1 = "com.example.app1"
        val packageName2 = "com.example.app2"
        val plaintext = "Secret".toByteArray(Charsets.UTF_8)

        val payload = AesCcmCrypto.encrypt(packageName1, plaintext)
        assertNotNull(payload)

        val decrypted = AesCcmCrypto.decrypt(packageName2, payload.nonce, payload.ciphertext)
        assertNull("different package name must not decrypt", decrypted)
    }

    @Test
    fun `nonce is 12 bytes`() {
        val packageName = "com.example.nonce"
        val plaintext = "Test nonce length".toByteArray(Charsets.UTF_8)

        val payload = AesCcmCrypto.encrypt(packageName, plaintext)
        assertNotNull(payload)
        assertEquals("nonce must be 12 bytes", 12, payload.nonce.size)
    }

    @Test
    fun `ciphertext size is plaintext plus 16-byte tag`() {
        val packageName = "com.example.size"
        val plaintext = "Size check".toByteArray(Charsets.UTF_8)

        val payload = AesCcmCrypto.encrypt(packageName, plaintext)
        assertNotNull(payload)
        assertEquals("ciphertext must be plaintext + 16", plaintext.size + 16, payload.ciphertext.size)
    }

    @Test
    fun `handles empty plaintext`() {
        val packageName = "com.example.empty"
        val plaintext = ByteArray(0)

        val payload = AesCcmCrypto.encrypt(packageName, plaintext)
        assertNotNull(payload)
        assertEquals("empty plaintext ciphertext should be just tag", 16, payload.ciphertext.size)

        val decrypted = AesCcmCrypto.decrypt(packageName, payload.nonce, payload.ciphertext)
        assertNotNull(decrypted)
        assertEquals(0, decrypted!!.size)
    }
}