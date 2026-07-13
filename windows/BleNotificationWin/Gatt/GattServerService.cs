using System;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Foundation;
using Windows.Security.Cryptography;

namespace BleNotificationWin.Gatt;

/// <summary>
/// BLE GATT Server service for receiving notification data from Android devices.
/// </summary>
public class GattServerService : IDisposable
{
    public static readonly Guid ServiceUuid = new("0000A1B2-0000-1000-8000-00805F9B34FB");
    public static readonly Guid WriteCharacteristicUuid = new("0000C3D4-0000-1000-8000-00805F9B34FB");

    public const byte MAGIC_HIGH = 0xAA;
    public const byte MAGIC_LOW = 0xBB;
    public const int FRAME_HEADER_SIZE = 5;
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

    public event EventHandler<byte[]>? OnDataReceived;
    public event EventHandler<string>? OnStatusChanged;
    public event EventHandler? OnDiscoveryStopped;

    public bool IsRunning => _isRunning;

    public async Task<bool> StartAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null)
            {
                OnStatusChanged?.Invoke(this, "No Bluetooth adapter found");
                return false;
            }

            var serviceResult = await GattServiceProvider.CreateAsync(ServiceUuid);
            if (serviceResult.Error != BluetoothError.Success)
            {
                OnStatusChanged?.Invoke(this, $"Failed to create GATT service: {serviceResult.Error}");
                return false;
            }

            _serviceProvider = serviceResult.ServiceProvider;

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
                _serviceProvider = null;
                return false;
            }

            _writeCharacteristic = characteristicResult.Characteristic;
            _writeCharacteristic.WriteRequested += OnWriteRequested;

            _serviceProvider.StartAdvertising(new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = true
            });

            _publisher = new BluetoothLEAdvertisementPublisher();
            _publisher.Advertisement.ServiceUuids.Add(ServiceUuid);
            _publisher.Start();

            _isRunning = true;
            OnStatusChanged?.Invoke(this, "GATT Server started");
            return true;
        }
        catch (Exception ex)
        {
            OnStatusChanged?.Invoke(this, $"Start error: {ex.Message}");
            return false;
        }
    }

    private async void OnWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        if (!_isRunning) return;

        try
        {
            var deferral = args.GetDeferral();
            var request = await args.GetRequestAsync();

            byte[] data = new byte[request.Value.Length];
            CryptographicBuffer.CopyToByteArray(request.Value, out data);

            if (data.Length < FRAME_HEADER_SIZE)
            {
                OnStatusChanged?.Invoke(this, $"Received incomplete frame: {data.Length} bytes");
                request.Respond();
                deferral.Complete();
                return;
            }

            if (data[0] != MAGIC_HIGH || data[1] != MAGIC_LOW)
            {
                OnStatusChanged?.Invoke(this, "Invalid frame magic bytes");
                request.Respond();
                deferral.Complete();
                return;
            }

            OnDataReceived?.Invoke(this, data);
            request.Respond();
            deferral.Complete();
        }
        catch (Exception ex)
        {
            OnStatusChanged?.Invoke(this, $"Write handler error: {ex.Message}");
        }
    }

    public void Stop()
    {
        try
        {
            _publisher?.Stop();
            _publisher = null;

            if (_writeCharacteristic != null)
            {
                _writeCharacteristic.WriteRequested -= OnWriteRequested;
                _writeCharacteristic = null;
            }

            if (_serviceProvider != null)
            {
                _serviceProvider.StopAdvertising();
                _serviceProvider = null;
            }

            _isRunning = false;
            OnStatusChanged?.Invoke(this, "GATT Server stopped");
            OnDiscoveryStopped?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception ex)
        {
            OnStatusChanged?.Invoke(this, $"Stop error: {ex.Message}");
        }
    }

    public async Task<string> GetStatusAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null)
                return "No Bluetooth adapter";

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
        Stop();
    }
}
