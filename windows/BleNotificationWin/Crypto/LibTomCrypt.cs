using System;
using System.Runtime.InteropServices;

namespace BleNotificationWin.Crypto;

/// <summary>
/// P/Invoke bridge to LibTomCrypt for AES-CCM and HKDF-SHA256.
/// Native library: libtomcrypt.dll (compiled from CMake)
/// </summary>
public static class LibTomCrypt
{
    private const string LibName = "libtomcrypt";

    // CCRYPT_OK = 0
    private const int CRYPT_OK = 0;

    // CCM constants
    private const int CCM_ENCRYPT = 0;
    private const int CCM_DECRYPT = 1;

    // Tag length
    private const int TAG_LENGTH = 16;

    // Salt for key derivation
    private static readonly byte[] SALT = "BleNotificationSync"u8.ToArray();

    // Static initialization flag
    private static bool _initialized;
    private static readonly object _initLock = new();

    /// <summary>
    /// Ensure LibTomCrypt is initialized (register ciphers and hashes).
    /// Thread-safe, called once.
    /// </summary>
    private static void EnsureInitialized()
    {
        if (_initialized) return;
        lock (_initLock)
        {
            if (_initialized) return;
            RegisterAllCiphers();
            RegisterAllHashes();
            _initialized = true;
        }
    }

    /// <summary>
    /// AES-CCM encrypt with authentication tag.
    /// </summary>
    /// <param name="key">16/24/32-byte AES key</param>
    /// <param name="nonce">7-13 byte nonce (must be unique per key)</param>
    /// <param name="plaintext">Data to encrypt</param>
    /// <returns>Ciphertext concatenated with 16-byte tag, or null on error</returns>
    public static byte[]? AesCcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext)
    {
        EnsureInitialized();

        int cipherIdx = FindCipher("aes");
        if (cipherIdx < 0) return null;

        // Output: ciphertext + 16-byte tag
        int outputLength = plaintext.Length + TAG_LENGTH;
        byte[] output = new byte[outputLength];
        byte[] tag = new byte[TAG_LENGTH];
        ulong tagLen = TAG_LENGTH;

        int result = ccm_memory(
            cipherIdx,
            key, (ulong)key.Length,
            IntPtr.Zero, // no pre-scheduled key
            nonce, (ulong)nonce.Length,
            null, 0, // no AAD
            plaintext, (ulong)plaintext.Length,
            output, // ciphertext output
            tag,
            ref tagLen,
            CCM_ENCRYPT);

        if (result != CRYPT_OK) return null;

        // Copy tag to end of output
        Array.Copy(tag, 0, output, plaintext.Length, TAG_LENGTH);
        return output;
    }

    /// <summary>
    /// AES-CCM decrypt and verify authentication tag.
    /// </summary>
    /// <param name="key">16/24/32-byte AES key</param>
    /// <param name="nonce">7-13 byte nonce (same as used for encryption)</param>
    /// <param name="ciphertext">Ciphertext concatenated with 16-byte tag</param>
    /// <returns>Decrypted plaintext, or null if authentication fails</returns>
    public static byte[]? AesCcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext)
    {
        EnsureInitialized();

        if (ciphertext.Length < TAG_LENGTH) return null; // too short for tag

        int cipherIdx = FindCipher("aes");
        if (cipherIdx < 0) return null;

        int ptLength = ciphertext.Length - TAG_LENGTH;
        byte[] plaintext = new byte[ptLength];
        byte[] tag = new byte[TAG_LENGTH];
        ulong tagLen = TAG_LENGTH;

        // Extract tag from ciphertext
        Array.Copy(ciphertext, ptLength, tag, 0, TAG_LENGTH);

        int result = ccm_memory(
            cipherIdx,
            key, (ulong)key.Length,
            IntPtr.Zero,
            nonce, (ulong)nonce.Length,
            null, 0,
            plaintext, (ulong)ptLength, // plaintext output
            ciphertext, // ciphertext (without tag)
            tag,
            ref tagLen,
            CCM_DECRYPT);

        if (result != CRYPT_OK) return null;
        return plaintext;
    }

    /// <summary>
    /// HKDF-SHA256 key derivation (extract + expand).
    /// </summary>
    /// <param name="salt">Salt value</param>
    /// <param name="info">Context/application-specific info</param>
    /// <param name="length">Desired output length in bytes (max 255)</param>
    /// <returns>Derived key material, or null on error</returns>
    public static byte[]? HkdfSha256(byte[] salt, byte[] info, int length)
    {
        EnsureInitialized();

        int hashIdx = FindHash("sha256");
        if (hashIdx < 0) return null;
        if (length <= 0 || length > 255) return null;

        // Step 1: Extract — PRK = HMAC(salt, IKM)
        ulong hashSize = 32; // SHA-256 = 32 bytes
        byte[] prk = new byte[hashSize];
        byte[] output = new byte[length];

        // IKM = info (package name); salt may be empty for default
        byte[]? saltToUse = salt.Length > 0 ? salt : null;

        int result = hkdf_extract(
            hashIdx,
            saltToUse, saltToUse != null ? (ulong)saltToUse.Length : 0,
            info, (ulong)info.Length,
            prk, ref hashSize);

        if (result != CRYPT_OK) return null;

        // Step 2: Expand — OKM = Expand(PRK, info, L)
        result = hkdf_expand(
            hashIdx,
            info, (ulong)info.Length,
            prk, (ulong)hashSize,
            output, (ulong)length);

        // Zero and free the intermediate PRK
        Array.Clear(prk);

        if (result != CRYPT_OK) return null;
        return output;
    }

    /// <summary>
    /// Derive a 32-byte AES key from a package name using HKDF-SHA256.
    /// </summary>
    /// <param name="packageName">Application package name</param>
    /// <returns>32-byte derived key</returns>
    public static byte[] DeriveKey(string packageName)
    {
        byte[]? key = HkdfSha256(SALT, System.Text.Encoding.UTF8.GetBytes(packageName), 32);
        if (key == null)
            throw new InvalidOperationException($"HKDF key derivation failed for package: {packageName}");
        return key;
    }

    #region P/Invoke declarations

    [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int register_all_ciphers();

    [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int register_all_hashes();

    [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int find_cipher([MarshalAs(UnmanagedType.LPStr)] string name);

    [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int find_hash([MarshalAs(UnmanagedType.LPStr)] string name);

    [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int ccm_memory(
        int cipher,
        byte[] key, ulong keyLen,
        IntPtr uskey, // symmetric_ECB* (unused, can be NULL)
        byte[] nonce, ulong nonceLen,
        byte[]? header, ulong headerLen,
        byte[] pt, ulong ptLen,
        byte[] ct,
        byte[] tag,
        ref ulong tagLen,
        int direction);

    [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int hkdf_extract(
        int hash,
        byte[]? salt, ulong saltLen,
        byte[] ikm, ulong ikmLen,
        byte[] prk, ref ulong prkLen);

    [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int hkdf_expand(
        int hash,
        byte[] info, ulong infoLen,
        byte[] prk, ulong prkLen,
        byte[] okm, ulong okmLen);

    [DllImport(LibName, CallingConvention = CallingConvention.Cdecl)]
    private static extern void zeromem(IntPtr dest, ulong size);

    #endregion

    #region Helper wrappers

    private static void RegisterAllCiphers()
    {
        int result = register_all_ciphers();
        if (result != CRYPT_OK)
            throw new InvalidOperationException("Failed to register LibTomCrypt ciphers");
    }

    private static void RegisterAllHashes()
    {
        int result = register_all_hashes();
        if (result != CRYPT_OK)
            throw new InvalidOperationException("Failed to register LibTomCrypt hashes");
    }

    private static int FindCipher(string name)
    {
        return find_cipher(name);
    }

    private static int FindHash(string name)
    {
        return find_hash(name);
    }

    #endregion
}
