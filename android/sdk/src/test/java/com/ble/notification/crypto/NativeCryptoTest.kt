package com.ble.notification.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCryptoTest {

    @Test
    fun `aesGcm encrypt then decrypt returns original plaintext`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 0xA0).toByte() }
        val plaintext = "Hello, BLE!".toByteArray(Charsets.UTF_8)
        val ciphertext = NativeCrypto.aesGcmEncrypt(key, nonce, plaintext)
        assertNotNull("encrypt should succeed", ciphertext)
        assertTrue("ciphertext should be plaintext + 16 byte tag", ciphertext!!.size == plaintext.size + 16)
        val decrypted = NativeCrypto.aesGcmDecrypt(key, nonce, ciphertext)
        assertNotNull("decrypt should succeed", decrypted)
        assertArrayEquals("decrypted must equal original", plaintext, decrypted)
    }

    @Test
    fun `aesGcm decrypt with wrong key returns null`() {
        val key1 = ByteArray(32) { it.toByte() }
        val key2 = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 0xA0).toByte() }
        val plaintext = "Secret message".toByteArray(Charsets.UTF_8)
        val ciphertext = NativeCrypto.aesGcmEncrypt(key1, nonce, plaintext)
        assertNotNull(ciphertext)
        val decrypted = NativeCrypto.aesGcmDecrypt(key2, nonce, ciphertext!!)
        assertNull("wrong key must fail authentication", decrypted)
    }

    @Test
    fun `aesGcm decrypt with wrong nonce returns null`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce1 = ByteArray(12) { (it + 0xA0).toByte() }
        val nonce2 = ByteArray(12) { (it + 0xB0).toByte() }
        val plaintext = "Another message".toByteArray(Charsets.UTF_8)
        val ciphertext = NativeCrypto.aesGcmEncrypt(key, nonce1, plaintext)
        assertNotNull(ciphertext)
        val decrypted = NativeCrypto.aesGcmDecrypt(key, nonce2, ciphertext!!)
        assertNull("wrong nonce must fail authentication", decrypted)
    }

    @Test
    fun `aesGcm encrypt is deterministic for same inputs`() {
        val key = ByteArray(32) { 0x42 }
        val nonce = ByteArray(12) { 0x55 }
        val plaintext = "deterministic".toByteArray(Charsets.UTF_8)
        val ct1 = NativeCrypto.aesGcmEncrypt(key, nonce, plaintext)
        val ct2 = NativeCrypto.aesGcmEncrypt(key, nonce, plaintext)
        assertNotNull(ct1)
        assertArrayEquals("same inputs must produce same ciphertext", ct1, ct2)
    }

    @Test
    fun `aesGcm handles empty plaintext`() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val plaintext = ByteArray(0)
        val ciphertext = NativeCrypto.aesGcmEncrypt(key, nonce, plaintext)
        assertNotNull("empty plaintext should still produce tag", ciphertext)
        assertEquals("empty plaintext output should be just the 16-byte tag", 16, ciphertext!!.size)
        val decrypted = NativeCrypto.aesGcmDecrypt(key, nonce, ciphertext)
        assertNotNull("decrypt of empty plaintext should succeed", decrypted)
        assertEquals(0, decrypted!!.size)
    }

    @Test
    fun `aesGcm handles 16-byte key (minimum AES-128)`() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val nonce = ByteArray(12) { (it + 0x10).toByte() }
        val plaintext = "AES-128-GCM".toByteArray(Charsets.UTF_8)
        val ciphertext = NativeCrypto.aesGcmEncrypt(key, nonce, plaintext)
        assertNotNull(ciphertext)
        val decrypted = NativeCrypto.aesGcmDecrypt(key, nonce, ciphertext!!)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `aesGcm handles large payload (BLE icon data simulation)`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 0x30).toByte() }
        val plaintext = ByteArray(240) { (it % 256).toByte() }
        val ciphertext = NativeCrypto.aesGcmEncrypt(key, nonce, plaintext)
        assertNotNull(ciphertext)
        assertEquals(240 + 16, ciphertext!!.size)
        val decrypted = NativeCrypto.aesGcmDecrypt(key, nonce, ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `hkdfSha256 produces deterministic output`() {
        val salt = "BleNotificationSync".toByteArray(Charsets.UTF_8)
        val ikm = "com.test.app".toByteArray(Charsets.UTF_8)
        val key1 = NativeCrypto.hkdfSha256(salt, ikm, 32)
        val key2 = NativeCrypto.hkdfSha256(salt, ikm, 32)
        assertNotNull(key1); assertNotNull(key2)
        assertArrayEquals("same inputs must produce same key", key1, key2)
        assertEquals(32, key1!!.size)
    }

    @Test
    fun `hkdfSha256 different ikm produces different keys`() {
        val salt = "BleNotificationSync".toByteArray(Charsets.UTF_8)
        val ikm1 = "com.app.one".toByteArray(Charsets.UTF_8)
        val ikm2 = "com.app.two".toByteArray(Charsets.UTF_8)
        val key1 = NativeCrypto.hkdfSha256(salt, ikm1, 32)
        val key2 = NativeCrypto.hkdfSha256(salt, ikm2, 32)
        assertNotNull(key1); assertNotNull(key2)
        assertTrue("different ikm must produce different keys", !key1.contentEquals(key2!!))
    }

    @Test
    fun `hkdfSha256 different salt produces different keys`() {
        val salt1 = "Salt1".toByteArray(Charsets.UTF_8)
        val salt2 = "Salt2".toByteArray(Charsets.UTF_8)
        val ikm = "com.test.app".toByteArray(Charsets.UTF_8)
        val key1 = NativeCrypto.hkdfSha256(salt1, ikm, 32)
        val key2 = NativeCrypto.hkdfSha256(salt2, ikm, 32)
        assertNotNull(key1); assertNotNull(key2)
        assertTrue("different salt must produce different keys", !key1.contentEquals(key2!!))
    }

    @Test
    fun `hkdfSha256 produces requested length`() {
        val salt = "salt".toByteArray(Charsets.UTF_8)
        val ikm = "info".toByteArray(Charsets.UTF_8)
        val key16 = NativeCrypto.hkdfSha256(salt, ikm, 16)
        val key32 = NativeCrypto.hkdfSha256(salt, ikm, 32)
        val key64 = NativeCrypto.hkdfSha256(salt, ikm, 64)
        assertNotNull(key16); assertNotNull(key32); assertNotNull(key64)
        assertEquals(16, key16!!.size); assertEquals(32, key32!!.size); assertEquals(64, key64!!.size)
    }

    @Test
    fun `hkdfSha256 with empty salt works`() {
        val salt = ByteArray(0)
        val ikm = "com.test.app".toByteArray(Charsets.UTF_8)
        val key = NativeCrypto.hkdfSha256(salt, ikm, 32)
        assertNotNull("empty salt should be valid (HKDF spec)", key)
        assertEquals(32, key!!.size)
    }

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
        assertTrue("different packages must produce different keys", !key1.contentEquals(key2))
    }
}
