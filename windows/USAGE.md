# 使用说明

## 快速开始

### 1. 编译 LibTomCrypt

在 Windows 上运行：

```batch
cd windows
build.bat
```

这将：
- 下载并编译 LibTomCrypt 和 LibTomMath
- 生成 `libtomcrypt.dll` 到 `windows/install/` 目录

### 2. 构建 .NET 项目

```batch
cd windows/BleNotificationWin
dotnet build
```

### 3. 运行程序

```batch
dotnet run
```

### 4. 运行测试

在程序界面点击 "Test Encryption" 按钮，或在代码中调用：

```csharp
CryptoTests.RunTests();
```

## API 使用示例

### 加密

```csharp
using BleNotificationWin.Crypto;

string packageName = "com.example.app";
byte[] plaintext = Encoding.UTF8.GetBytes("Hello, World!");

var encrypted = AesCcmCrypto.Encrypt(packageName, plaintext);
// encrypted.Nonce - 12 字节随机 nonce
// encrypted.Ciphertext - 密文 + 16 字节认证标签
```

### 解密

```csharp
byte[]? decrypted = AesCcmCrypto.Decrypt(
    packageName,
    encrypted.Nonce,
    encrypted.Ciphertext
);

if (decrypted != null)
{
    string message = Encoding.UTF8.GetString(decrypted);
}
```

### 密钥派生

```csharp
byte[] key = KeyDerivation.DeriveKey("com.example.app");
// 返回 32 字节 AES 密钥
```

## 架构说明

### 加密流程

1. **密钥派生**：使用 HKDF-SHA256 从包名派生 32 字节密钥
   - Salt: `"BleNotificationSync"` (UTF-8)
   - Info: 包名 (UTF-8)
   - 输出长度: 32 字节

2. **加密**：使用 AES-CCM 加密
   - 生成 12 字节随机 nonce
   - 输出: 密文 + 16 字节认证标签

3. **解密**：使用相同密钥和 nonce 解密
   - 验证认证标签
   - 返回明文或 null（认证失败）

### 与 Android 端兼容

- 使用相同的 LibTomCrypt 库
- 相同的加密算法参数
- 相同的密钥派生方式
- 相同的输出格式

## 故障排除

### 编译错误

1. **找不到 dotnet**：安装 .NET 8 SDK
2. **找不到 CMake**：安装 CMake 3.22+
3. **缺少 Visual Studio Build Tools**：安装 Visual Studio 或 Build Tools

### 运行时错误

1. **找不到 libtomcrypt.dll**：确保 DLL 在程序目录或系统 PATH 中
2. **加密失败**：检查输入数据是否有效
3. **解密失败**：检查 nonce 和密文是否正确

## 开发说明

### 添加新功能

1. 在 `Crypto/` 目录下添加新类
2. 遵循现有的命名规范
3. 添加单元测试

### 调试

1. 启用详细日志（修改 `LibTomCrypt.cs`）
2. 使用 Visual Studio 调试器
3. 检查 P/Invoke 签名是否正确

## 性能优化

1. **缓存密钥**：对于频繁使用的包名，可以缓存派生的密钥
2. **异步操作**：对于大文件加密，使用异步方法
3. **内存池**：使用 `ArrayPool<byte>` 减少内存分配

## 安全注意事项

1. **Nonce 唯一性**：确保每个密钥的 nonce 唯一
2. **密钥保护**：不要硬编码密钥
3. **内存清零**：敏感数据使用后立即清零
4. **随机数生成**：使用 `RandomNumberGenerator` 生成随机数
