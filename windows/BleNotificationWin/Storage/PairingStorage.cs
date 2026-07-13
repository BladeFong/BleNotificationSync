using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace BleNotificationWin.Storage;

/// <summary>
/// Persists paired device information to JSON file.
/// Manages device bindings by MAC address + package name.
/// </summary>
public class PairingStorage
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    private readonly string _filePath;
    private readonly object _lock = new();
    private List<PairedDeviceInfo> _devices = [];

    /// <summary>
    /// Create a PairingStorage with the default storage path.
    /// </summary>
    public PairingStorage() : this(GetDefaultStoragePath()) { }

    /// <summary>
    /// Create a PairingStorage with a custom storage path.
    /// </summary>
    public PairingStorage(string filePath)
    {
        _filePath = filePath;
        Load();
    }

    /// <summary>
    /// Save a pairing record.
    /// </summary>
    /// <param name="macAddress">Device MAC address (e.g., "AA:BB:CC:DD:EE:FF")</param>
    /// <param name="packageName">App package name (e.g., "com.example.app")</param>
    /// <param name="appName">App display name</param>
    /// <param name="iconData">Optional app icon data</param>
    public void Save(string macAddress, string packageName, string appName, byte[]? iconData = null)
    {
        lock (_lock)
        {
            string key = GetKey(macAddress, packageName);
            var existing = _devices.FindIndex(d =>
                d.MacAddress == macAddress && d.PackageName == packageName);

            var info = new PairedDeviceInfo
            {
                MacAddress = macAddress,
                PackageName = packageName,
                AppName = appName,
                IconData = iconData,
                PairedAt = DateTime.UtcNow,
                LastSeen = DateTime.UtcNow
            };

            if (existing >= 0)
            {
                // Preserve icon data from existing record if not provided
                if (iconData == null && _devices[existing].IconData != null)
                {
                    info.IconData = _devices[existing].IconData;
                }
                _devices[existing] = info;
            }
            else
            {
                _devices.Add(info);
            }

            Persist();
        }
    }

    /// <summary>
    /// Load pairing info for a MAC + package combination.
    /// </summary>
    /// <returns>Paired device info, or null if not found</returns>
    public PairedDeviceInfo? Load(string macAddress, string packageName)
    {
        lock (_lock)
        {
            return _devices.Find(d =>
                d.MacAddress == macAddress && d.PackageName == packageName);
        }
    }

    /// <summary>
    /// Remove a specific pairing.
    /// </summary>
    public bool Remove(string macAddress, string packageName)
    {
        lock (_lock)
        {
            int removed = _devices.RemoveAll(d =>
                d.MacAddress == macAddress && d.PackageName == packageName);

            if (removed > 0) Persist();
            return removed > 0;
        }
    }

    /// <summary>
    /// Remove all pairings for a specific MAC address.
    /// </summary>
    public int RemoveByMac(string macAddress)
    {
        lock (_lock)
        {
            int removed = _devices.RemoveAll(d => d.MacAddress == macAddress);
            if (removed > 0) Persist();
            return removed;
        }
    }

    /// <summary>
    /// Get all paired devices.
    /// </summary>
    public List<PairedDeviceInfo> GetAll()
    {
        lock (_lock)
        {
            return [.. _devices];
        }
    }

    /// <summary>
    /// Check if a device is paired.
    /// </summary>
    public bool IsPaired(string macAddress, string packageName)
    {
        lock (_lock)
        {
            return _devices.Exists(d =>
                d.MacAddress == macAddress && d.PackageName == packageName);
        }
    }

    /// <summary>
    /// Get all packages paired with a specific MAC address.
    /// </summary>
    public List<PairedDeviceInfo> GetByMac(string macAddress)
    {
        lock (_lock)
        {
            return _devices.FindAll(d => d.MacAddress == macAddress);
        }
    }

    private void Load()
    {
        try
        {
            if (File.Exists(_filePath))
            {
                string json = File.ReadAllText(_filePath);
                _devices = JsonSerializer.Deserialize<List<PairedDeviceInfo>>(json, JsonOptions) ?? [];
            }
        }
        catch
        {
            _devices = [];
        }
    }

    private void Persist()
    {
        try
        {
            string? dir = Path.GetDirectoryName(_filePath);
            if (!string.IsNullOrEmpty(dir))
                Directory.CreateDirectory(dir);

            string json = JsonSerializer.Serialize(_devices, JsonOptions);
            File.WriteAllText(_filePath, json);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"PairingStorage persist error: {ex.Message}");
        }
    }

    private static string GetKey(string macAddress, string packageName)
    {
        return $"{macAddress}:{packageName}";
    }

    private static string GetDefaultStoragePath()
    {
        string appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(appData, "BleNotificationSync", "paired_devices.json");
    }
}

/// <summary>
/// Paired device information.
/// </summary>
public class PairedDeviceInfo
{
    /// <summary>Device MAC address (e.g., "AA:BB:CC:DD:EE:FF")</summary>
    public string MacAddress { get; set; } = "";

    /// <summary>App package name (e.g., "com.example.app")</summary>
    public string PackageName { get; set; } = "";

    /// <summary>App display name</summary>
    public string AppName { get; set; } = "";

    /// <summary>Optional app icon data (ICO format)</summary>
    public byte[]? IconData { get; set; }

    /// <summary>When the pairing was created</summary>
    public DateTime PairedAt { get; set; }

    /// <summary>Last time this device communicated</summary>
    public DateTime LastSeen { get; set; }
}
