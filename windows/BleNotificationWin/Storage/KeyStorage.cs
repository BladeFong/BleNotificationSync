using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace BleNotificationWin.Storage;

/// <summary>
/// Persists derived AES-CCM keys for paired devices.
/// Keys are indexed by MAC address + package name.
/// Uses DPAPI (Windows Data Protection API) for key encryption at rest.
/// </summary>
public class KeyStorage
{
    private readonly string _filePath;
    private readonly object _lock = new();
    private Dictionary<string, byte[]> _keys = [];

    /// <summary>
    /// Create a KeyStorage with the default storage path.
    /// </summary>
    public KeyStorage() : this(GetDefaultStoragePath()) { }

    /// <summary>
    /// Create a KeyStorage with a custom storage path.
    /// </summary>
    public KeyStorage(string filePath)
    {
        _filePath = filePath;
        Load();
    }

    /// <summary>
    /// Save a derived AES key for a MAC + package combination.
    /// The key is encrypted with DPAPI before writing to disk.
    /// </summary>
    /// <param name="macAddress">Device MAC address</param>
    /// <param name="packageName">App package name</param>
    /// <param name="key">32-byte AES key to store</param>
    public void SaveKey(string macAddress, string packageName, byte[] key)
    {
        if (key.Length != 32)
            throw new ArgumentException("AES key must be 32 bytes", nameof(key));

        lock (_lock)
        {
            string storageKey = GetStorageKey(macAddress, packageName);

            // Encrypt with DPAPI before storing
            byte[] encrypted = ProtectedData.Protect(key, null, DataProtectionScope.CurrentUser);
            _keys[storageKey] = encrypted;

            Persist();
        }
    }

    /// <summary>
    /// Retrieve the AES key for a MAC + package combination.
    /// </summary>
    /// <returns>32-byte key, or null if not found</returns>
    public byte[]? GetKey(string macAddress, string packageName)
    {
        lock (_lock)
        {
            string storageKey = GetStorageKey(macAddress, packageName);

            if (!_keys.TryGetValue(storageKey, out byte[]? encrypted))
                return null;

            try
            {
                byte[] key = ProtectedData.Unprotect(encrypted, null, DataProtectionScope.CurrentUser);
                return key;
            }
            catch
            {
                return null;
            }
        }
    }

    /// <summary>
    /// Remove the key for a MAC + package combination.
    /// </summary>
    public bool RemoveKey(string macAddress, string packageName)
    {
        lock (_lock)
        {
            string storageKey = GetStorageKey(macAddress, packageName);
            bool removed = _keys.Remove(storageKey);

            if (removed) Persist();
            return removed;
        }
    }

    /// <summary>
    /// Remove all keys for a specific MAC address.
    /// </summary>
    public int RemoveByMac(string macAddress)
    {
        lock (_lock)
        {
            string prefix = macAddress + ":";
            int removed = 0;

            var toRemove = new List<string>();
            foreach (var key in _keys.Keys)
            {
                if (key.StartsWith(prefix))
                    toRemove.Add(key);
            }

            foreach (var key in toRemove)
            {
                _keys.Remove(key);
                removed++;
            }

            if (removed > 0) Persist();
            return removed;
        }
    }

    /// <summary>
    /// Check if a key exists for the given MAC + package.
    /// </summary>
    public bool HasKey(string macAddress, string packageName)
    {
        lock (_lock)
        {
            string storageKey = GetStorageKey(macAddress, packageName);
            return _keys.ContainsKey(storageKey);
        }
    }

    /// <summary>
    /// Get all stored key identifiers (MAC:Package format).
    /// </summary>
    public List<string> GetAllKeys()
    {
        lock (_lock)
        {
            return [.. _keys.Keys];
        }
    }

    private void Load()
    {
        try
        {
            if (File.Exists(_filePath))
            {
                string json = File.ReadAllText(_filePath);
                var encryptedKeys = JsonSerializer.Deserialize<Dictionary<string, string>>(json);

                if (encryptedKeys != null)
                {
                    _keys = new Dictionary<string, byte[]>();
                    foreach (var kvp in encryptedKeys)
                    {
                        try
                        {
                            byte[] encrypted = Convert.FromBase64String(kvp.Value);
                            _keys[kvp.Key] = encrypted;
                        }
                        catch
                        {
                            // Skip corrupted entries
                        }
                    }
                }
            }
        }
        catch
        {
            _keys = [];
        }
    }

    private void Persist()
    {
        try
        {
            string? dir = Path.GetDirectoryName(_filePath);
            if (!string.IsNullOrEmpty(dir))
                Directory.CreateDirectory(dir);

            var encryptedKeys = new Dictionary<string, string>();
            foreach (var kvp in _keys)
            {
                encryptedKeys[kvp.Key] = Convert.ToBase64String(kvp.Value);
            }

            string json = JsonSerializer.Serialize(encryptedKeys, new JsonSerializerOptions
            {
                WriteIndented = true
            });
            File.WriteAllText(_filePath, json);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"KeyStorage persist error: {ex.Message}");
        }
    }

    private static string GetStorageKey(string macAddress, string packageName)
    {
        return $"{macAddress}:{packageName}";
    }

    private static string GetDefaultStoragePath()
    {
        string appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(appData, "BleNotificationSync", "keys.json");
    }
}
