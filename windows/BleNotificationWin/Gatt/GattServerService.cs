using System;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Bluetooth.Advertisement;

namespace BleNotificationWin.Gatt;

/// <summary>
/// BLE GATT Server service for receiving notification data from Android devices.
/// Uses Windows.Devices.Bluetooth.GenericAttributeProfile to create a local GATT service
/// with a WRITE_NO_RESPONSE characteristic for receiving encrypted notification payloads.
/// </summary>
public class GattServerService : IDisposable
{
    // Protocol constants
    public static readonly Guid ServiceUuid = new("0000A1B2-0000-1000-8000-00805F9B34FB");
    public static readonly Guid WriteCharacteristicUuid = new("0000C3D4-0000-1000-8000-00805F9B34FB");

    // Frame protocol constants
    public const byte MAGIC_HIGH = 0xAA;
    public const byte MAGIC_LOW = 0xBB;
    public const int FRAME_HEADER_SIZE = 5; // Magic(2) + MsgType(1) + Seq(1) + TotalSeq(1)

    // Message types
    public const byte MSG_REGISTER = 0x01;
    public const byte MSG_NOTIFY = 0x02;
    public const byte MSG_ACK = 0x03;
    public const byte MSG_ICON_DATA = 0x04;
    public const byte MSG_ICON_END = 0x05;

    private GattServiceProvider? _serviceProvider;
    private GattLocalCharacteristic? _writeCharacteristic;
    private BluetoothLEAdvertisementPublisher? _publisher;

    private bool _isRunning;
    private bool _disposed;
    private DateTime _discoveryStartTime;
    private System.Threading.Timer? _discoveryTimer;

    // Events
    public event EventHandler<byte[]>? OnDataReceived;
    public event EventHandler<string>? OnStatusChanged;
    public event EventHandler? OnDiscoveryStopped;

    /// <summary>
    /// Gets whether the GATT server is currently running and advertising.
    /// </summary>
    public bool IsRunning => _isRunning;

    /// <summary>
    /// Start the GATT server and begin advertising.
    /// </summary>
    /// <returns>True if started successfully</returns>
    public async Task<bool> Start()
    {
        if (_isRunning) return true;

        try
        {
            // Check Bluetooth adapter status
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null)
            {
                OnStatusChanged?.Invoke(this, "No Bluetooth adapter found");
                return false;
            }

            if (!adapter.IsAdvertisementOffloadedSupported)
            {
                OnStatusChanged?.Invoke(this, "BLE advertisement offloading not supported");
                return false;
            }

            // Create GATT service provider
            var serviceResult = await GattServiceProvider.CreateAsync(ServiceUuid);
            if (serviceResult.Error != BluetoothError.Success)
            {
                OnStatusChanged?.Invoke(this, $"Failed to create GATT service: {serviceResult.Error}");
                return false;
            }

            _serviceProvider = serviceResult.ServiceProvider;

            // Create write characteristic with WRITE_NO_RESPONSE property
            var characteristicParameters = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.WriteWithoutResponse,
                WriteProtectionLevel = GattProtectionLevel.Plain,
                ReadProtectionLevel = GattProtectionLevel.Plain
            };

            var characteristicResult = await _serviceProvider.Service.CreateCharacteristicAsync(
                WriteCharacteristicUuid, characteristicParameters);

            if (characteristicResult.Error != BluetoothError.Success)
            {
                OnStatusChanged?.Invoke(this, $"Failed to create characteristic: {characteristicResult.Error}");
                _serviceProvider.Dispose();
                _serviceProvider = null;
                return false;
            }

            _writeCharacteristic = characteristicResult.Characteristic;

            // Subscribe to WriteWithoutResponse events via ValueChanged
            _writeCharacteristic.ValueChanged += OnCharacteristicValueChanged;

            // Start advertising the service
            _serviceProvider.StartAdvertising(new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = true
            });

            // Also start a BLE advertisement publisher to make the device discoverable
            _publisher = new BluetoothLEAdvertisementPublisher();
            _publisher.Advertisement.ServiceUuids.Add(ServiceUuid);
            _publisher.Start();

            _isRunning = true;
            _discoveryStartTime = DateTime.Now;

            // Auto-stop discovery after 2 minutes (Android side should connect within this window)
            _discoveryTimer = new System.Threading.Timer(OnDiscoveryTimeout, null,
                TimeSpan.FromMinutes(2), System.Threading.Timeout.InfiniteTimeSpan);

            OnStatusChanged?.Invoke(this, "GATT server started, advertising...");
            return true;
        }
        catch (Exception ex)
        {
            OnStatusChanged?.Invoke(this, $"GATT server start failed: {ex.Message}");
            Cleanup();
            return false;
        }
    }

    /// <summary>
    /// Stop the GATT server and stop advertising.
    /// </summary>
    public void Stop()
    {
        if (!_isRunning) return;

        _discoveryTimer?.Dispose();
        _discoveryTimer = null;

        Cleanup();
        _isRunning = false;

        OnStatusChanged?.Invoke(this, "GATT server stopped");
    }

    /// <summary>
    /// Handle WRITE_NO_RESPONSE characteristic value changes.
    /// This is the main data reception handler for the protocol.
    /// </summary>
    private void OnCharacteristicValueChanged(GattSession sender, GattValueChangedEventArgs args)
    {
        if (!_isRunning) return;

        try
        {
            byte[] data = new byte[args.CharacteristicValue.Length];
            Windows.Security.Cryptography.CryptographicBuffer.CopyToByteArray(
                args.CharacteristicValue, out data);

            if (data.Length < FRAME_HEADER_SIZE)
            {
                OnStatusChanged?.Invoke(this, $"Received incomplete frame: {data.Length} bytes");
                return;
            }

            // Validate magic bytes
            if (data[0] != MAGIC_HIGH || data[1] != MAGIC_LOW)
            {
                OnStatusChanged?.Invoke(this, "Invalid frame magic bytes");
                return;
            }

            OnDataReceived?.Invoke(this, data);
        }
        catch (Exception ex)
        {
            OnStatusChanged?.Invoke(this, $"Error processing received data: {ex.Message}");
        }
    }

    /// <summary>
    /// Handle discovery timeout - stop advertising after configured duration.
    /// </summary>
    private void OnDiscoveryTimeout(object? state)
    {
        if (!_isRunning) return;

        try
        {
            _publisher?.Stop();
            OnStatusChanged?.Invoke(this, "Discovery timeout - stopped advertising");
            OnDiscoveryStopped?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception ex)
        {
            OnStatusChanged?.Invoke(this, $"Error stopping discovery: {ex.Message}");
        }
    }

    /// <summary>
    /// Cleanup all GATT server resources.
    /// </summary>
    private void Cleanup()
    {
        try
        {
            _publisher?.Stop();
            _publisher?.Dispose();
            _publisher = null;

            if (_writeCharacteristic != null)
            {
                _writeCharacteristic.ValueChanged -= OnCharacteristicValueChanged;
                _writeCharacteristic = null;
            }

            if (_serviceProvider != null)
            {
                _serviceProvider.StopAdvertising();
                _serviceProvider.Dispose();
                _serviceProvider = null;
            }
        }
        catch (Exception ex)
        {
            OnStatusChanged?.Invoke(this, $"Cleanup error: {ex.Message}");
        }
    }

    /// <summary>
    /// Get current Bluetooth status.
    /// </summary>
    public async Task<string> GetStatusAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null)
                return "No Bluetooth adapter";

            if (!adapter.IsAdvertisementOffloadedSupported)
                return "BLE advertisement not supported";

            return _isRunning ? "Running" : "Stopped";
        }
        catch
        {
            return "Unknown";
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        _discoveryTimer?.Dispose();
        Cleanup();
        GC.SuppressFinalize(this);
    }
}
