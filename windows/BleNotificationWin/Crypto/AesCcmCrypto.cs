using System;
using System.Security.Cryptography;

namespace BleNotificationWin.Crypto;

/// <summary>
/// High-level AES-CCM encryption service for BLE notification payloads.
/// Each package name gets a unique derived key via <see cref="KeyDerivation"/>
/// and encryption uses a random 12-byte nonce per call.
/// </summary>
public static class AesCcmCrypto
{
    private const int NONCE_SIZE = 12;

    /// <summary>
    /// Encrypted payload consisting of nonce and ciphertext (including authentication tag).
    /// </summary>
    public record EncryptedPayload(byte[] Nonce, byte[] Ciphertext);

    /// <summary>
    /// Encrypt a plaintext using a package-specific AES-CCM key.
    /// </summary>
    /// <param name="packageName">Application package name (used for key derivation)</param>
    /// <param name="plaintext">Data to encrypt</param>
    /// <returns>EncryptedPayload containing nonce and ciphertext</returns>
    /// <exception cref="InvalidOperationException">Thrown if encryption fails</exception>
    public static EncryptedPayload Encrypt(string packageName, byte[] plaintext)
    {
        byte[] key = KeyDerivation.DeriveKey(packageName);
        byte[] nonce = GenerateNonce();
        byte[]? ciphertext = LibTomCrypt.AesCcmEncrypt(key, nonce, plaintext);
        if (ciphertext == null)
            throw new InvalidOperationException($"AES-CCM encryption failed for package: {packageName}");
        return new EncryptedPayload(nonce, ciphertext);
    }

    /// <summary>
    /// Decrypt a ciphertext using a package-specific AES-CCM key.
    /// </summary>
    /// <param name="packageName">Application package name (used for key derivation)</param>
    /// <param name="nonce">12-byte nonce used during encryption</param>
    /// <param name="ciphertext">Ciphertext with authentication tag</param>
    /// <returns>Decrypted plaintext, or null if authentication fails</returns>
    public static byte[]? Decrypt(string packageName, byte[] nonce, byte[] ciphertext)
    {
        if (nonce.Length != NONCE_SIZE)
            throw new ArgumentException($"Nonce must be {NONCE_SIZE} bytes, got {nonce.Length}", nameof(nonce));

        byte[] key = KeyDerivation.DeriveKey(packageName);
        return LibTomCrypt.AesCcmDecrypt(key, nonce, ciphertext);
    }

    /// <summary>
    /// Generate a cryptographically secure random nonce.
    /// </summary>
    private static byte[] GenerateNonce()
    {
        byte[] nonce = new byte[NONCE_SIZE];
        RandomNumberGenerator.Fill(nonce);
        return nonce;
    }
}
