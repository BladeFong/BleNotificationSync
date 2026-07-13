using System;

namespace BleNotificationWin.Crypto;

/// <summary>
/// Derives per-package AES keys from a BLE notification sync salt using HKDF-SHA256.
/// </summary>
public static class KeyDerivation
{
    /// <summary>
    /// Derive a 32-byte AES key for the given package name.
    /// Uses the project-wide salt and HKDF-SHA256 to produce a deterministic,
    /// domain-separated key per package.
    /// </summary>
    /// <param name="packageName">Application package name (e.g. "com.example.app")</param>
    /// <returns>32-byte derived key</returns>
    /// <exception cref="InvalidOperationException">Thrown if native HKDF fails</exception>
    public static byte[] DeriveKey(string packageName)
    {
        return LibTomCrypt.DeriveKey(packageName);
    }
}
