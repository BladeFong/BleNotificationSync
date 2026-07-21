package com.ble.notification.crypto

import java.security.SecureRandom

/**
 * High-level AES-GCM encryption service for BLE notification payloads.
 *
 * Each package name gets a unique derived key via [KeyDerivation] and
 * encryption uses a random 12-byte nonce per call.
 */
object AesGcmCrypto {

    private const val NONCE_SIZE = 12

    /**
     * Encrypted payload consisting of nonce and ciphertext (including authentication tag).
     */
    data class EncryptedPayload(val nonce: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedPayload) return false
            return nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            var result = nonce.contentHashCode()
            result = 31 * result + ciphertext.contentHashCode()
            return result
        }
    }

    /**
     * Encrypt a plaintext using a pre-derived AES-GCM key.
     *
     * @param key       32-byte AES key (derived during pairing via HKDF)
     * @param plaintext the data to encrypt
     * @return [EncryptedPayload] containing nonce and ciphertext
     * @throws IllegalStateException if encryption fails
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray): EncryptedPayload {
        val nonce = generateNonce()
        val ciphertext = NativeCrypto.aesGcmEncrypt(key, nonce, plaintext)
            ?: throw IllegalStateException("AES-GCM encryption failed")
        return EncryptedPayload(nonce, ciphertext)
    }

    /**
     * Decrypt a ciphertext using a pre-derived AES-GCM key.
     *
     * @param key        32-byte AES key (derived during pairing via HKDF)
     * @param nonce      the 12-byte nonce used during encryption
     * @param ciphertext the ciphertext with authentication tag
     * @return decrypted plaintext, or null if authentication fails
     */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes, got ${nonce.size}" }
        return NativeCrypto.aesGcmDecrypt(key, nonce, ciphertext)
    }

    private fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)
        return nonce
    }
}