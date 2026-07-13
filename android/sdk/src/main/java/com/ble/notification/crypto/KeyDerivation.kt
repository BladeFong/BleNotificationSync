package com.ble.notification.crypto

/**
 * Derives per-package AES keys from a BLE notification sync salt using HKDF-SHA256.
 */
object KeyDerivation {

    /**
     * Derive a 32-byte AES key for the given package name.
     *
     * Uses the project-wide [NativeCrypto.SALT] and HKDF-SHA256 to produce a
     * deterministic, domain-separated key per package.
     *
     * @param packageName application package name (e.g. "com.example.app")
     * @return 32-byte derived key
     * @throws IllegalStateException if native HKDF fails
     */
    fun deriveKey(packageName: String): ByteArray {
        return NativeCrypto.deriveKey(packageName)
    }
}
