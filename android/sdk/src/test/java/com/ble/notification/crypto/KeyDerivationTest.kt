package com.ble.notification.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDerivationTest {

    @Test
    fun `deriveKey returns 32-byte key`() {
        val key = KeyDerivation.deriveKey("com.example.app")
        assertEquals("key must be 32 bytes", 32, key.size)
    }

    @Test
    fun `deriveKey is deterministic for same package name`() {
        val key1 = KeyDerivation.deriveKey("com.nearby.justnow")
        val key2 = KeyDerivation.deriveKey("com.nearby.justnow")
        assertArrayEquals("same package must produce same key", key1, key2)
    }

    @Test
    fun `different package names produce different keys`() {
        val key1 = KeyDerivation.deriveKey("com.app.one")
        val key2 = KeyDerivation.deriveKey("com.app.two")
        assertTrue("different packages must produce different keys",
            !key1.contentEquals(key2))
    }
}
