# BleNotificationSync

基于 BLE（低功耗蓝牙）的跨平台通知同步系统。在无网、无账号、无云端服务器的环境下，实现 Android 手机通知（如闹钟、提醒事项）到桌面端（Windows / macOS / Linux）的近场即时推送与系统通知提醒。

<p align="center">
  <img src="docs/images/architecture.png" alt="系统架构图" width="720" />
</p>

<p align="center">
  <img src="docs/images/pairing-demo.gif" alt="扫码配对与通知同步演示" width="360" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/images/desktop-preview.png" alt="桌面端接收效果" width="400" />
</p>

---

## 核心特性

- **纯本地局域传输**：基于 BLE GATT 协议直连，不依赖互联网与云端服务器。
- **免系统蓝牙配对**：采用二维码扫码交互完成密钥协商，无需进入操作系统蓝牙设置页面手动配对。
- **多设备关联**：Android 客户端支持同时绑定并管理多台桌面端设备。
- **端到端加密**：采用 AES-256-GCM 认证加密，结合 HKDF-SHA256 派生密钥，按消息生成随机 Nonce。
- **图标分片传输**：绑定阶段自动提取 App 图标并切片传输至桌面端缓存，通知弹窗直接复用图标。

---

## 系统架构

```text
Android 客户端 (GATT Client)               桌面客户端 (GATT Server)
[ App / SDK ]                             [ Tauri v2 / Rust Backend ]
     │                                                │
     ├── 1. 扫码解析 MAC / Service UUID ────────────────┤
     ├── 2. 发起 GATT 连接 (TRANSPORT_LE) ────────────┤
     ├── 3. 密钥协商 (HKDF-SHA256) ───────────────────┤
     ├── 4. 传输 App 图标二进制切片 ──────────────────┤
     └── 5. 推送加密通知帧 (AES-256-GCM) ───────────▶ └── 触发系统 Native Toast
```

### 平台与技术栈

| 模块 | 语言 / 框架 | BLE 角色 | 状态 |
| :--- | :--- | :--- | :--- |
| **Android SDK** | Kotlin, C (LibTomCrypt JNI), API 23+ | GATT Client | 已完成 |
| **Android Demo** | Kotlin, Jetpack Compose, Activity Result API | 宿主示范 App | 已完成 |
| **桌面端** | Rust, Tauri 2, HTML/JS | GATT Server (WinRT / BlueZ / CoreBluetooth) | 已完成 |

---

## 传输协议与安全规范

### GATT 服务定义

| 属性 | UUID |
| :--- | :--- |
| **Service UUID** | `364b0f2b-22f6-5997-4744-869ca4511d9e` |
| **Characteristic UUID** | `0000C3D4-0000-1000-8000-00805F9B34FB` |

### 数据帧结构

数据帧物理格式（单帧最大 244 字节，匹配 MTU 协商）：

```text
+-------------------+--------------+----------+--------------+------------------+
| Magic (2B, 0xAABB)| MsgType (1B) | Seq (1B) | TotalSeq (1B)| Payload (0-239B) |
+-------------------+--------------+----------+--------------+------------------+
```

### 消息类型 (MsgType)

| 字节值 | 类型 | 方向 | 说明 |
| :--- | :--- | :--- | :--- |
| `0x01` | **REGISTER** | Phone → PC | 绑定注册：传输包名、App 名称与随机密钥因子 |
| `0x02` | **NOTIFY** | Phone → PC | 通知推送：包含加密后的标题、正文及时间戳 JSON |
| `0x03` | **ACK** | PC → Phone | 接收确认响应 |
| `0x04` | **ICON_DATA** | Phone → PC | App 图标原始二进制数据切片 |
| `0x05` | **ICON_END** | Phone → PC | 图标传输完成标识 |

### 密码学规范

- **对称加密**：AES-256-GCM（16 字节认证 Tag，12 字节随机 Nonce）。
- **密钥派生**：`baseKey = HKDF-SHA256(salt="BleNotificationSync", IKM=package_name + random_32B, info="")`。
- **存储隔离**：Android 侧采用 Base64 格式保存于 `EncryptedSharedPreferences`；桌面端采用系统原生安全存储（Windows 凭据管理器 / macOS Keychain / Secret Service）。

---

## 快速开始

### 1. 编译并运行桌面端

要求：Node.js 18+，Rust 1.75+，对应平台的 C/C++ 编译环境。

```bash
cd desktop
npm install
npx tauri dev
```

### 2. 集成 Android SDK

#### 依赖引入

在项目的 `build.gradle.kts` 中引入 SDK 模块或 AAR：

```kotlin
dependencies {
    implementation(project(":sdk"))
    // 或使用编译好的 AAR:
    // implementation(files("libs/ble-notification-sdk-release.aar"))
}
```

#### 调用示例

```kotlin
// 1. 初始化 SDK
val sdk = BleNotificationSDK.init(applicationContext)

// 2. 扫码绑定 PC 设备
sdk.startPairing(activity, "我的应用名", object : PairingCallback {
    override fun onPaired() {
        // 绑定成功，已保存秘钥
    }
    override fun onError(error: SdkError) {
        // 错误处理：SdkError.AlreadyPaired, SdkError.ScanFailed 等
    }
})

// 3. 推送通知给所有已绑定的 PC
//    可选参数 notificationId 指定本地通知 ID，不传则根据标题+内容自动生成
sdk.sendNotification(
    title = "会议提醒",
    body = "项目周会将于 10 分钟后开始",
    // notificationId = 1001,  // 可选：指定通知 ID，便于后续取消或更新
    callback = object : SendCallback {
        override fun onSuccess() { /* 推送成功 */ }
        override fun onError(error: String) { /* 异常处理 */ }
    }
)
```

---

## 开发与调试注意事项

1. **Windows 通知限制**：
   - Windows 10/11 系统的 Toast 通知要求应用必须关联有效的 `AppUserModelId`。
   - 在 `tauri dev` 调试阶段，若系统静默拦截通知，需确保应用注册了快捷方式；通过正式安装包安装后安装器会自动处理。
2. **Android 权限与 GPS 开关**：
   - Android 12+ (API 31+) 需要 `BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT` 动态权限。
   - 在部分 OEM 系统（如小米、华为、OPPO）上，系统级 GPS 位置总开关关闭会导致 BLE 扫描静默返回 0 结果，调用前需确保位置服务处于开启状态。

---

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
