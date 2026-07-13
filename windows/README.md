# BLE Notification Sync - Windows

Windows 客户端实现，使用 LibTomCrypt 进行 AES-CCM 加密。

## 构建步骤

### 前置要求

- .NET 8 SDK
- CMake 3.22+
- Visual Studio Build Tools（或完整的 Visual Studio）

### 1. 编译 LibTomCrypt

```batch
cd windows
build.bat
```

这将在 `windows/install` 目录下生成 `libtomcrypt.dll`。

### 2. 构建 .NET 项目

```batch
cd windows/BleNotificationWin
dotnet build
```

### 3. 运行

```batch
dotnet run
```

## 项目结构

```
windows/
├── BleNotificationWin/           # .NET 8 WinForms 项目
│   ├── BleNotificationWin.csproj # 项目文件
│   ├── Crypto/
│   │   ├── LibTomCrypt.cs        # P/Invoke 桥接
│   │   ├── AesCcmCrypto.cs       # AES-CCM 加密服务
│   │   └── KeyDerivation.cs      # HKDF-SHA256 密钥派生
│   ├── Program.cs                # 程序入口
│   └── MainForm.cs               # 主窗口
├── build.bat                     # 构建脚本
└── README.md                     # 本文档
```

## 加密实现

### API 接口

- `AesCcmCrypto.Encrypt(packageName, plaintext)` → `EncryptedPayload`
- `AesCcmCrypto.Decrypt(packageName, nonce, ciphertext)` → `byte[]?`
- `KeyDerivation.DeriveKey(packageName)` → `byte[]`
- `record EncryptedPayload(byte[] Nonce, byte[] Ciphertext)`

### 加密参数

- AES-CCM 密钥：32 字节（通过 HKDF-SHA256 派生）
- Nonce：12 字节（随机生成）
- 认证标签：16 字节
- Salt：`"BleNotificationSync"` (UTF-8)

### 与 Android 端兼容性

Windows 端实现与 Android 端完全兼容，使用相同的：
- LibTomCrypt 库
- 加密算法（AES-CCM）
- 密钥派生算法（HKDF-SHA256）
- 相同的 Salt 值
- 相同的输出格式（Nonce + Ciphertext）

## 依赖

- LibTomCrypt 1.18.2：第三方加密库
- LibTomMath：大数运算库
- .NET 8.0：运行时框架

## 注意事项

1. 首次运行前需要编译 LibTomCrypt
2. 确保 `libtomcrypt.dll` 在程序运行目录或系统 PATH 中
3. 加密操作是线程安全的
4. 密钥派生是确定性的（相同包名总是产生相同密钥）
