# BLE Notification Sync

Cross-platform BLE-based notification synchronization — zero cloud, zero account, pure local.

When your Android alarm goes off, the notification is pushed to your computer via BLE in near-field range. The computer receives it and displays a native system notification. No internet, no server, no login.

## Architecture

```
Phone (Android SDK)  ──BLE GATT──▶  PC (Tauri / Windows / macOS)
        │                                    │
  GATT Client                          GATT Server
  Encrypt & Send                    Decrypt & Notify
```

## Supported Platforms

| Platform | Language | BLE Role | Status |
|----------|----------|----------|--------|
| Android SDK | Kotlin (AAR) | GATT Client | ✅ Complete |
| Desktop (Win/macOS/Linux) | Rust + JS (Tauri 2) | GATT Server | 🚧 In progress |
| Windows | C# (.NET 8 WinForms) | GATT Server | 🚧 In progress |
| macOS | Swift (SwiftUI) | GATT Server | 📋 Planned |

## How It Works

### 1. Pair (once)

Scan a QR code displayed on the PC using your Android phone. The QR code contains the PC's BLE MAC address and service UUID. No OS-level Bluetooth pairing dialog needed.

```
ble://pair?mac=XX:XX:XX:XX:XX:XX&uuid=0000A1B2-...
```

### 2. Sync (every notification)

When an alarm or reminder fires on Android, the notification is encrypted with AES-256-GCM and sent over BLE GATT. The PC decrypts it and shows a native OS notification via:

- **Tauri**: WebView notification (via Rust backend)
- **Windows**: WinForms `NotifyIcon` + Toast
- **macOS**: `UserNotifications` framework (planned)

## Protocol

### GATT Service

| Attribute | UUID |
|-----------|------|
| Service | `0000A1B2-0000-1000-8000-00805F9B34FB` |
| Write Characteristic | `0000C3D4-0000-1000-8000-00805F9B34FB` |

### Data Frame

```
| Magic (0xAA 0xBB, 2B) | MsgType (1B) | Seq (1B) | TotalSeq (1B) | Payload (0-240B) |
```

### Message Types

| Value | Type | Direction | Description |
|-------|------|-----------|-------------|
| 0x01 | REGISTER | Phone → PC | Pairing: send app info + key material |
| 0x02 | NOTIFY | Phone → PC | Push notification (encrypted) |
| 0x03 | ACK | PC → Phone | Acknowledgment |
| 0x04 | ICON_DATA | Phone → PC | App icon fragment (raw binary) |
| 0x05 | ICON_END | Phone → PC | Icon transfer complete |

## Encryption

| Parameter | Specification |
|-----------|---------------|
| Algorithm | AES-256-GCM (AEAD) |
| Key Derivation | HKDF-SHA256 |
| Key Length | 32 bytes |
| Nonce | 12 bytes, random per message |
| Auth Tag | 16 bytes |
| Key Distribution | QR code pairing + HKDF |

## Get Started

### Android SDK

```kotlin
// 1. Initialize
BleNotificationSDK.init(context)

// 2. Pair with PC (scan QR code)
BleNotificationSDK.getInstance().startPairing(activity, object : PairingCallback {
    override fun onPaired() { /* ready to send */ }
    override fun onError(error: String) { /* handle error */ }
})

// 3. Send notification
BleNotificationSDK.getInstance().sendNotification("Alarm", "Wake up!", object : SendCallback {
    override fun onSuccess() { /* sent */ }
    override fun onError(error: String) { /* retry */ }
})
```

### Desktop (Tauri)

```bash
cd desktop
npm install
npx tauri dev
```

### Windows

```bash
cd windows
build.bat                       # Build LibTomCrypt DLL
cd BleNotificationWin
dotnet run
```

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

Patent applications covering the GATT notification synchronization method implemented herein have been filed or are in preparation by the copyright owner. Commercial products implementing this method may require a separate patent license.

See [LICENSE.zh-CN](LICENSE.zh-CN) for the Chinese reference translation.

## Third-Party Software

| Library | License | Usage |
|---------|---------|-------|
| [libtomcrypt](https://github.com/libtom/libtomcrypt) | Public domain | AES-GCM + SHA-256 (JNI / P/Invoke) |
| [libtommath](https://github.com/libtom/libtommath) | Public domain | Big number arithmetic (libtomcrypt dependency) |
