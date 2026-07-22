# BLE 通知同步

基于 BLE 的跨平台通知同步方案——零云端、零账号、纯本地。

Android 闹钟触发时，通知通过 BLE 近场直连推送到电脑，电脑弹出系统原生通知。无需网络、无需服务器、无需登录。

## 架构

```
手机 (Android SDK)  ──BLE GATT──▶  PC (Tauri / Windows / macOS)
        │                                    │
  GATT 客户端                          GATT 服务端
  加密发送                            解密并弹通知
```

## 支持平台

| 平台 | 语言 | BLE 角色 | 状态 |
|------|------|----------|------|
| Android SDK | Kotlin (AAR) | GATT 客户端 | ✅ 已完成 |
| 桌面端 (Win/macOS/Linux) | Rust + JS (Tauri 2) | GATT 服务端 | 🚧 开发中 |
| Windows | C# (.NET 8 WinForms) | GATT 服务端 | 🚧 开发中 |
| macOS | Swift (SwiftUI) | GATT 服务端 | 📋 计划中 |

## 工作流程

### 第一步：配对（仅一次）

用 Android 手机扫描 PC 端显示的二维码。二维码包含 PC 的 BLE MAC 地址和服务 UUID。无需系统级蓝牙配对确认。

```
ble://pair?mac=XX:XX:XX:XX:XX:XX&uuid=0000A1B2-...
```

### 第二步：同步（每次通知）

Android 端闹钟触发时，通知内容经 AES-256-GCM 加密后通过 BLE GATT 发送。PC 端解密后弹出系统原生通知：

- **Tauri**：WebView 通知（Rust 后端驱动）
- **Windows**：WinForms `NotifyIcon` 托盘 + Toast 弹窗
- **macOS**：`UserNotifications` 框架（计划中）

## 协议

### GATT 服务

| 属性 | UUID |
|------|------|
| 服务 | `0000A1B2-0000-1000-8000-00805F9B34FB` |
| 写入特征 | `0000C3D4-0000-1000-8000-00805F9B34FB` |

### 数据帧格式

```
| Magic (0xAA 0xBB, 2B) | MsgType (1B) | Seq (1B) | TotalSeq (1B) | Payload (0-240B) |
```

### 消息类型

| 值 | 类型 | 方向 | 说明 |
|----|------|------|------|
| 0x01 | REGISTER | 手机 → PC | 配对：发送应用信息 + 密钥素材 |
| 0x02 | NOTIFY | 手机 → PC | 推送通知（加密） |
| 0x03 | ACK | PC → 手机 | 确认收到 |
| 0x04 | ICON\_DATA | 手机 → PC | 应用图标分片（二进制直传） |
| 0x05 | ICON\_END | 手机 → PC | 图标传输完成 |

## 加密方案

| 参数 | 规格 |
|------|------|
| 加密算法 | AES-256-GCM (AEAD) |
| 密钥派生 | HKDF-SHA256 |
| 密钥长度 | 32 字节 |
| Nonce | 12 字节，每次消息独立随机 |
| 认证标签 | 16 字节 |
| 密钥分发 | 二维码配对 + HKDF |

## 快速开始

### Android SDK

```kotlin
// 1. 初始化
BleNotificationSDK.init(context)

// 2. 与 PC 配对（扫描二维码）
BleNotificationSDK.getInstance().startPairing(activity, object : PairingCallback {
    override fun onPaired() { /* 配对成功，可发送通知 */ }
    override fun onError(error: String) { /* 处理错误 */ }
})

// 3. 发送通知
BleNotificationSDK.getInstance().sendNotification("闹钟", "该起床了！", object : SendCallback {
    override fun onSuccess() { /* 发送成功 */ }
    override fun onError(error: String) { /* 重试 */ }
})
```

### 桌面端 (Tauri)

```bash
cd desktop
npm install
npx tauri dev
```

### Windows 端

```bash
cd windows
build.bat                       # 编译 LibTomCrypt DLL
cd BleNotificationWin
dotnet run
```

## 开源协议

本项目采用 Apache License 2.0 协议。详见 [LICENSE](LICENSE)。

版权方已就本软件实现的 GATT 通知同步方法提交或正在准备专利申请。实现该方法的商业产品可能需要另行取得专利授权。

中文参考译本见 [LICENSE.zh-CN](LICENSE.zh-CN)。

## 第三方软件

| 库 | 协议 | 用途 |
|----|------|------|
| [libtomcrypt](https://github.com/libtom/libtomcrypt) | Public domain | AES-GCM + SHA-256 (JNI / P/Invoke) |
| [libtommath](https://github.com/libtom/libtommath) | Public domain | 大整数运算 (libtomcrypt 依赖) |
