package com.ble.notification.crypto

/**
 * JNI bridge to LibTomCrypt for AES-CCM and HKDF-SHA256.
 *
 * Native library: libtomcrypt_jni.so (compiled from CMake)
 */
object NativeCrypto {

    init {
        System.loadLibrary("tomcrypt_jni")
    }

    /**
     * AES-CCM encrypt with authentication tag.
     *
     * @param key       16/24/32-byte AES key
     * @param nonce     7-13 byte nonce (must be unique per key)
     * @param plaintext data to encrypt
     * @return ciphertext concatenated with 16-byte tag, or null on error
     */
    @JvmStatic
    external fun aesCcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray?

    /**
     * AES-CCM decrypt and verify authentication tag.
     *
     * @param key        16/24/32-byte AES key
     * @param nonce      7-13 byte nonce (same as used for encryption)
     * @param ciphertext ciphertext concatenated with 16-byte tag
     * @return decrypted plaintext, or null if authentication fails or on error
     */
    @JvmStatic
    external fun aesCcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray?

    /**
     * HKDF-SHA256 key derivation (extract + expand).
     *
     * @param salt   salt value (use [SALT] for project default)
     * @param info   context/application-specific info (e.g. package name)
     * @param length desired output length in bytes (max 255 for SHA-256)
     * @return derived key material, or null on error
     */
    @JvmStatic
    external fun hkdfSha256(salt: ByteArray, info: ByteArray, length: Int): ByteArray?

    /** Default salt for BLE notification sync key derivation. */
    val SALT: ByteArray = "BleNotificationSync".toByteArray(Charsets.UTF_8)

    /**
     * Derive a 32-byte AES key from a package name using HKDF-SHA256.
     *
     * @param packageName the application package name
     * @return 32-byte AES key
     * @throws IllegalStateException if native key derivation fails
     */
    fun deriveKey(packageName: String): ByteArray {
        val key = hkdfSha256(SALT, packageName.toByteArray(Charsets.UTF_8), 32)
            ?: throw IllegalStateException("HKDF key derivation failed for package: $packageName")
        return key
    }
}
