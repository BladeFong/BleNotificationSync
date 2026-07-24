# BLE Notification Sync 设计文档

## 1. 项目概述

通过低功耗蓝牙（BLE）建立手机与电脑的近场直连通道，实现**零云端、零账号、纯本地**的闹钟通知同步。

- **Android 端（GATT Client）**：闹钟触发时推送通知到电脑
- **PC/Mac 端（GATT Server）**：接收数据后弹出系统原生通知

### 技术选型

| 平台 | 语言 | UI 框架 | 最低版本 |
|------|------|---------|----------|
| Android SDK | Kotlin | 无（纯 SDK） | API 23 (Android 6.0) |
| PC/Mac | Rust + Web | Tauri + HTML/JS | Windows 10 1709+ / macOS 13+ |

**Tauri 方案选择理由**：
- Windows 和 macOS 共享同一套 UI 和业务代码
- Web 技术栈（HTML/CSS/JS）开发效率高
- Rust 后端性能好、内存安全
- 包体积小（~5-10MB）
- 跨平台 BLE 通过平台特定 Rust binding 实现

**Android API 23 选择理由**：
- 覆盖 99.2% 活跃设备
- 避开 Camera2 早期 bug（API 21-22）
- 运行时权限模型成熟（相机、蓝牙权限更规范）
- BLE GATT / MTU 完整支持

---

## 2. 通信协议

### 2.1 GATT 服务定义

| 项目 | 值 |
|------|-----|
| Service UUID | `9e1d51a4-9c86-4447-9759-f6222b0f4b36` |
| Write Characteristic | `f4788cde-8025-4c07-b352-87db1b272fdf` |
| 属性 | Write + WriteWithoutResponse |

### 2.2 数据帧格式

```
+--------+--------+--------+--------+-------------------+
| Magic  | MsgType| Seq    |TotalSeq| Payload           |
| 2B     | 1B     | 1B     | 1B     | 0~240B            |
| 0xAA 0xBB|      |        |        |                   |
+--------+--------+--------+--------+-------------------+
```

- **Magic**: 固定包头 `0xAA 0xBB`
- **MsgType**: 消息类型
- **Seq**: 当前包序号（0-based）
- **TotalSeq**: 总包数
- **Payload**: JSON 数据的字节流

### 2.3 消息类型

| 值 | 名称 | 方向 | 说明 |
|----|------|------|------|
| 0x01 | REGISTER | Android→PC | 绑定时发送 APP 信息 |
| 0x02 | NOTIFY | Android→PC | 推送通知 |
| 0x03 | ACK | PC→Android | 确认收到（协议保留，当前未实现） |
| 0x04 | ICON_DATA | Android→PC | 图标分片数据 |
| 0x05 | ICON_END | Android→PC | 图标传输完成 |

### 2.4 Payload 格式

#### REGISTER

```json
{
  "app_name": "JustNow",
  "package": "com.nearby.justnow",
  "random": "<32 字节随机数的 hex 编码（小写，64 字符）>",
  "android_id": "abc123...",
  "device_name": "Pixel 8 Pro"
}
```

- `app_name`：APP 显示名称（来自 SDK 调用参数）
- `package`：Android 包名（来自 `context.packageName`）
- `random`：Android 生成的 32 字节随机数，**十六进制字符串**（小写，64 字符）。用于密钥派生，双方各自 `HKDF(package+random) → baseKey` 后持久化
- `android_id`：设备唯一标识（可选，来自 `Settings.Secure.ANDROID_ID`），用作设备主键，向后兼容时回退到 PC MAC
- `device_name`：设备友好名称（可选，来自 `Build.MODEL`）

#### NOTIFY（加密 payload 的明文 JSON）

```json
{
  "title": "任务提醒",
  "body": "会议还有10分钟"
}
```

NOTIFY 帧的 payload 结构为 `[pkg_len(1B) | package(pkg_len B) | nonce(12B) | ciphertext]`，其中 ciphertext 解密后得到以上 JSON。

#### ICON_DATA

图标二进制直传（不做 Base64 编码），每片最大 239 字节。

**帧格式**：
```
+--------+--------+--------+--------+-------------------+
| Magic  | MsgType| Seq    |TotalSeq| IconData          |
| 2B     | 1B     | 1B     | 1B     | 0~239B            |
| 0xAA 0xBB|      |        |        | (原始二进制)       |
+--------+--------+--------+--------+-------------------+
```

**约束**：图标最大 60KB，确保 255 帧内完成传输。

#### ICON_END

空 payload 帧，标记图标传输完成。桌面端收到后关闭图标接收缓冲区。

---

## 2.5 加密方案

### 2.5.1 加密库

| 平台 | 加密库 | 算法 |
|------|--------|------|
| Android | LibTomCrypt (JNI) | AES-GCM |
| PC/Mac | aes-gcm (Rust crate) | AES-GCM |

**选择 AES-GCM 的理由**：
- Rust 生态支持好（aes-gcm crate）
- AEAD 认证加密，与 AES-CCM 同等安全
- API 更简洁，易于跨平台一致

### 2.5.2 密钥管理

| 项目 | 说明 |
|------|------|
| 生成时机 | APP 绑定配对时 |
| 生成方式 | Android 生成 32 字节随机数，通过 REGISTER 帧发给桌面，双方各自 `HKDF(package+random) → baseKey` |
| 保存位置 | Android 端：EncryptedSharedPreferences 存 `baseKey`（按 UUID 索引）<br>PC/Mac 端：按设备 ID（Android ID） + 包名管理，baseKey 存 Windows Credential Manager / macOS Keychain |
| 随机数传递 | REGISTER 帧中明文携带，配对后丢弃 |

**密钥派生算法**（两端实现必须一致）：

```
// 配对时，一次性派生并持久化
random = SecureRandom.nextBytes(32)          // Android 生成
baseKey = HKDF-SHA256(
  salt = "BleNotificationSync",
  IKM  = packageName + random,               // 拼接包名和随机数
  info = "" (零长字节),
  L    = 32
)
// baseKey(32B) 持久化存储
```

**每次消息加密**（配对完成后不再派生新密钥）：

```
nonce = SecureRandom.nextBytes(12)          // 每次消息独立生成
ciphertext = AES-256-GCM-Encrypt(baseKey, nonce, plaintext)
发送: [Package(明文) | nonce(明文) | ciphertext]
```

**安全性**：
- 不同 pairing 的 random 不同 → baseKey 不同（防止反编译包名推算）
- 同一 baseKey 下每次消息 nonce 不同 → 密文不同（防重放攻击）
- 双层随机：配对级隔离 + 消息级隔离

### 2.5.3 通知加密流程

```
+------------------+                    +------------------+
|   Android 端     |                    |    PC/Mac 端     |
+------------------+                    +------------------+
| 1. plaintext = JSON 通知内容          |
|    (title, body, timestamp)          |
| 2. 生成随机数 nonce (8-13 字节)      |
| 3. ciphertext = AES-GCM-Encrypt(     |
|       key, nonce, plaintext)         |
| 4. 发送: [package | nonce | ciphertext]|
|    (package 明文，其余加密)           |
|         --------BLE--------->        |
|                                      | 5. 解析 Package 明文|
|                                      | 6. 遍历已配对设备   |
|                                      |    按 Package 匹配  |
|                                      |    → 查找密钥       |
|                                      | 8. plaintext =     |
|                                      |    AES-GCM-Decrypt |
|                                      |    (key, nonce,    |
|                                      |     ciphertext)    |
+------------------+                    +------------------+
```

**安全说明**：Package 明文暴露不影响安全，因为没有密钥无法解密通知内容。

### 2.5.4 数据帧格式（加密后）

```
+--------+--------+--------+--------+---------+---------+-----------+
| Magic  | MsgType| Seq    |TotalSeq| Package | Nonce   | Ciphertext
| 2B     | 1B     | 1B     | 1B     | 变长    | 8~13B   | 变长
| 0xAA 0xBB|      |        |        | (明文)  | (明文)  |
+--------+--------+--------+--------+---------+---------+-----------+
```

**设计说明**：
- **Package**：APP 包名，明文传输，用于 GATT Server 查找对应密钥
- **Nonce**：随机数，明文传输，用于 AES-GCM 解密
- **Ciphertext**：加密的 JSON 通知内容（title、body、timestamp 等）

**解密逻辑**：
1. GATT Server 解析出 Package（明文）
2. 遍历已配对设备，按 Package 匹配，尝试每个匹配设备的 baseKey
3. 用密钥 + Nonce 解密 Ciphertext

### 2.5.5 绑定流程（加密版）

```
Android                                       PC/Mac
   |                                             |
   | 1. 生成 32B 随机数 random                   |
   | 2. REGISTER({app_name, package, random,     |
   |      android_id, device_name})              |
   | ----------------------------------------->  |
   |                                             | 3. baseKey = HKDF(package + random)
   |                                             | 4. 存储: device_id→(package, app_name, baseKey)
   | 5. baseKey = HKDF(package + random)         |
   | 6. 存储: uuid→(appName, baseKey)            |
   |    (EncryptedSharedPreferences)              |
   | 7. 发送 ICON_DATA × N + ICON_END            |
   | ----------------------------------------->  |
   |                                             | 8. 缓存图标到 %APPDATA%/icons/
   |                                             |
   | 9. 后续通知 AES-256-GCM(baseKey, nonce, pt) |
   | ==========================================>  |
```

**关键**：配对后双方持久化同一个 `baseKey`，后续消息直接用，不再每次派生。

### 2.5.6 安全考虑

| 场景 | 处理 |
|------|------|
| 重放攻击 | nonce 随机生成，每次不同 |
| 中间人攻击 | BLE 近场限制 + QR 码绑定 |
| 未绑定设备 | GATT Server 拒绝解密 |
| Package 明文暴露 | 无影响，需密钥才能解密内容 |
| 包名碰撞 | 不同 APP 用不同包名，天然隔离 |

---

## 3. 系统架构

### 3.1 架构图

```mermaid
flowchart LR
    subgraph Android
        A[业务层] --> B[SDK 封装层]
        B --> C[BLE 通信层]
    end

    subgraph PC/Mac
        D[GATT Server] --> E[通知适配层]
        D --> F[配对存储]
    end

    C -- GATT Write --> D
```

### 3.2 Android 端分层

| 层 | 职责 |
|----|------|
| 业务层 | 调用 SDK API |
| SDK 封装层 | sendNotification / startPairing |
| BLE 通信层 | MTU 协商、分片组包、串行队列、连接管理 |

### 3.3 PC/Mac 端分层

| 层 | 职责 |
|----|------|
| GATT Server | 广播服务、接收数据、重组分片 |
| 通知适配层 | 调用系统 API 弹出通知 |
| 配对存储 | 保存手机绑定信息、APP 图标缓存 |

---

## 4. 配对流程

### 4.1 二维码内容

```
ble://pair?uuid=9e1d51a4-9c86-4447-9759-f6222b0f4b36&mac=XX:XX:XX:XX:XX:XX&name=MyPC
```

- `uuid`：服务 UUID（**必填**），Android 端用于扫描过滤
- `mac`：蓝牙 MAC（可选，用于直连回退）
- `name`：设备友好名称（可选，用于 UI 显示）

### 4.2 配对时序图

```mermaid
sequenceDiagram
    participant A as Android
    participant P as PC/Mac

    A->>P: 1. 扫描二维码获取 UUID
    A->>P: 2. BLE 扫描 → 连接 GATT Server
    A->>P: 3. 请求 MTU (247)
    A->>P: 4. 发送 REGISTER (app_name, package, random, android_id, device_name)
    P-->>P: 5. 派生 baseKey = HKDF(package + random)
    P-->>P: 6. 存储 device_id→(package, app_name, baseKey)
    A-->>A: 7. 派生 baseKey = HKDF(package + random)
    A-->>A: 8. 存储到 EncryptedSharedPreferences
    A->>P: 9. 发送 ICON_DATA × N + ICON_END
    P-->>P: 10. 缓存图标到本地
    Note over A,P: 绑定完成，后续通知加密传输
```

### 4.3 配对状态机

```mermaid
stateDiagram-v2
    [*] --> 未绑定
    未绑定 --> 连接中: 扫描二维码
    连接中 --> 未绑定: 超时/失败
    连接中 --> 注册中: GATT就绪
    注册中 --> 未绑定: 注册失败
    注册中 --> 已绑定: REGISTER成功
    已绑定 --> [*]
```

### 4.4 配对流程图

```mermaid
flowchart TD
    A[业务层调用 startPairing] --> B[SDK 启动扫码]
    B --> C{扫码成功?}
    C -->|失败| D[回调 onError]
    C -->|成功| E[解析 UUID]
    E --> F[BLE 扫描 + 连接 GATT Server]
    F --> G{连接成功?}
    G -->|失败| D
    G -->|成功| H[请求 MTU 247]
    H --> I[发送 REGISTER]
    I --> J{桌面端派生并存储密钥}
    J --> K[SDK 派生并存储密钥]
    K --> L[发送 ICON_DATA × N]
    L --> M[发送 ICON_END]
    M -->|成功| N[回调 onPaired]
    N --> O[桌面端缓存图标]
```

---

## 5. SDK API 设计

### 5.1 扫码分层设计

SDK 提供三层扫码能力，APP 根据需求选择：

| 层级 | 组件 | 说明 | 使用方式 |
|------|------|------|----------|
| **解码层** | `QrDecoder.parseQrCode(url)` | 二维码 URL 解析 | 必须使用 |
| **相机层** | `QrScanner` | CameraX + ML Kit 二维码检测 | 可选，APP 自己写 UI 时使用 |
| **UI 层** | `QrScannerFragment` | 完整扫码界面（含取景框） | 可选，直接 add 到 Activity/Fragment |

**APP 使用方式**：

```kotlin
// 方式 1：直接使用 SDK 扫码 Fragment（最简单）
val fragment = QrScannerFragment.newInstance { qrResult ->
    // 扫码成功，qrResult 包含 mac 和 uuid
}
supportFragmentManager.beginTransaction()
    .add(R.id.container, fragment)
    .commit()

// 方式 2：使用相机层，自己写 UI
val scanner = QrScanner(activity) { qrResult ->
    // 扫码成功
}
scanner.start()

// 方式 3：只用解码逻辑（完全自定义）
val result = QrDecoder.parseQrCode(scannedUrl)
```

**依赖关系**：

```
QrScannerFragment (UI 层)
    ↓ 依赖
QrScanner (相机层)
    ↓ 依赖
QrDecoder (解码层)
```

### 5.2 配对 API

```kotlin
class BleNotificationSDK {
    // 开始配对
    fun startPairing(activity: Activity, callback: PairingCallback)

    // 查询绑定状态
    fun isPaired(): Boolean
    fun getPairedDevices(): List<PairedDevice>

    // 取消绑定
    fun unpair(deviceId: String)
}

interface PairingCallback {
    fun onScanSuccess()
    fun onQrResult(mac: String, uuid: String)
    fun onConnecting()
    fun onRegistering()
    fun onPaired()
    fun onError(error: SdkError)
}
```

### 5.2 通知弹出与同步 API

```kotlin
class BleNotificationSDK {
    // 发送内建通知（支持配置通知按钮，弹出本地通知并在后台通过 BleForegroundService 同步至 PC）
    fun sendNotification(
        title: String,
        body: String,
        actions: List<NotificationAction> = emptyList(),
        notificationId: Int? = null,
        callback: SendCallback? = null
    )

    // 发送内建通知（直接接收 NotificationCompat.Builder，弹出本地通知并在后台通过 BleForegroundService 同步至 PC）
    fun sendNotification(
        builder: NotificationCompat.Builder,
        notificationId: Int? = null,
        callback: SendCallback? = null
    )
}

data class NotificationAction(
    val label: String,
    val actionId: String
)

interface SendCallback {
    fun onSuccess()
    fun onError(error: SdkError)
}
```

---

## 6. 连接策略

### 6.1 通知推送流程

```mermaid
flowchart TD
    A[调用 sendNotification] --> B[弹出本地通知]
    B --> C[启动 BleForegroundService]
    C --> D[BLE 扫描 + 连接 GATT Server]
    D --> E{连接成功?}
    E -->|失败| F[不进行蓝牙发送，回调 onError]
    E -->|成功| G[发送 NOTIFY]
    G --> H[3 秒后断开连接]
    H --> I[回调 SendCallback onSuccess]
```

### 6.2 连接策略

- **每次通知独立连接，用完即断**（3 秒延迟断开）
- Android 端使用 `BleForegroundService`（前台 Service）保证后台 BLE 扫描不被系统杀掉
- 支持 WorkManager `BleScanWorker` 作为后备唤醒机制
- 连接方式：BLE 扫描 → 按 Service UUID 过滤 → 连接 GATT，而非仅依赖 MAC 直连
- 扫描超时 10 秒，双重回退（先按设备名扫描，再按 UUID 扫描）
- 连接耗时约 300ms - 1 秒

### 6.3 断线处理

| 场景 | 处理 |
|------|------|
| 连接失败 | 只发本地通知，回调 success=false |
| 发送超时 | 重试 3 次，失败则放弃 |
| PC 端不可用 | 连接失败，只发本地通知 |

---

## 7. 桌面端设计（Tauri v2）

### 7.1 技术栈

| 层 | 技术 |
|----|------|
| 桌面框架 | Tauri v2（`tray-icon` 特性） |
| 后端 | Rust (edition 2021) + tokio 异步 |
| 前端 | Vanilla HTML/CSS/JS，零构建工具 |
| BLE 外设 | `ble-peripheral-rust` v0.2（跨平台） |
| 加密 | `aes-gcm` 0.10 + `hkdf` 0.12 + `sha2` 0.10 |
| 密钥存储 | Windows Credential Manager / macOS Keychain / Linux Secret Service（`keyring` crate） |
| 通知（Windows） | `winrt-notification` Toast + PowerShell BalloonTip 兜底 |
| 通知（非 Windows） | `notify-rust`（libnotify / UNUserNotificationCenter） |
| 单实例 | `tauri-plugin-single-instance` v2 |

### 7.2 Rust 模块架构

```
src-tauri/src/
├── main.rs          # 入口：panic hook → lib::run()
├── lib.rs           # 应用核心：托盘菜单、窗口管理、13 个 Tauri 命令注册、NotifyState
├── ble.rs           # BLE GATT Server（ble-peripheral-rust 跨平台方案）
├── crypto.rs        # AES-256-GCM + HKDF-SHA256
├── event_handler.rs # 托盘菜单事件分发
├── protocol.rs      # BLE 二进制帧协议（解析/构建）
├── notify.rs        # 通知适配（Windows: winrt-notification, 非Windows: notify-rust）
│                    #   + NotifyState epoch 防抖清理 + PowerShell 兜底
├── config.rs        # 配置管理 + 图标存储 + 密钥安全存储
└── storage.rs       # 配对设备存储 + 注册表设置（开机自启/静默模式）
```

### 7.3 通知三级策略（Windows）

1. **安装版**：`winrt-notification` → WinRT Toast（支持大图标，3 分钟 epoch 防抖清理操作中心）
2. **开发版/失败兜底**：PowerShell `NotifyIcon.BalloonTip`
3. **非 Windows**：`notify-rust` → libnotify（Linux）/ UNUserNotificationCenter（macOS）

安装版检测：通过 `HKCU\...\Uninstall\BLE Notification Sync\InstallLocation` 注册表比对运行路径。

### 7.4 托盘功能

- 显示窗口 / 启动服务（勾选）/ 开机自启动（勾选）/ 静默启动服务（勾选）/ 退出
- 窗口关闭行为：隐藏而非退出（`CloseRequested` → `prevent_close`）
- 静默模式：启动时直接隐藏窗口并启动 BLE 服务

### 7.5 图标管理

- 图标存储：`%APPDATA%/ble-notification-sync/icons/<package_name>.png`
- 绑定时通过 ICON_DATA/ICON_END 分片接收，落盘后通知复用
- Windows Toast 通知左侧图标优先使用对应 App 图标，回退到安装目录 `icon.ico`

> **废弃代码**：`../windows/`（C# WinForms）和 `../macos/`（Swift）目录为旧实现，已由 Tauri v2 统一方案替代。

---

## 8. 错误处理

### 8.1 BLE 连接异常

| 场景 | 处理策略 |
|------|----------|
| GATT 连接断开 | 重试 3 次，间隔 1s |
| MTU 协商失败 | 回退到默认 23 字节 |
| GATT_BUSY | 串行队列等待 |
| 发送超时 | 重试 3 次，失败回调 |

### 8.2 Android 端

| 场景 | 处理策略 |
|------|----------|
| 闹钟触发时 BLE 断开 | 尝试重连，失败则只发本地通知 |
| 扫码失败 | 回调 onError |
| 配对超时 | 回调 onError，状态回退 |
| 密钥丢失 | 重新绑定 |
| 加密失败 | 回调 onError，不发送 |

### 8.3 PC/Mac 端

| 场景 | 处理策略 |
|------|----------|
| 电脑睡眠 | GATT Server 断开，唤醒后自动重新广播 |
| 多手机绑定 | 支持，每个手机独立存储 |
| 图标传输中断 | 下次绑定时重新传输 |
| 通知权限未授权 | 首次启动引导授权 |
| 解密失败 | 丢弃数据，记录日志 |
| 密钥不存在 | 拒绝解密，返回错误码 |

---

## 9. 测试策略

### 9.1 单元测试

| 模块 | 测试内容 |
|------|----------|
| 协议层 | 分片/重组、帧解析、消息类型编码 |
| 加密层 | AES-GCM 加解密、密钥派生、nonce 生成 |
| SDK API | setReminder / cancelReminder 逻辑 |
| 配对解析 | 二维码 URL 解析 |

### 9.2 集成测试

| 场景 | 方法 |
|------|------|
| GATT 通信 | 模拟 GATT Server + 真实 Android 设备 |
| 通知弹出 | 验证 PC/Mac 端收到数据后正确弹出通知 |
| 分片传输 | 大数据（图标）完整传输验证 |
| 加密传输 | 验证加密通知能正确解密 |
| 密钥交换 | 验证绑定流程中密钥正确传递 |

### 9.3 手动测试

| 场景 | 验证点 |
|------|--------|
| 首次配对 | 二维码扫描 → 绑定成功 |
| 通知推送 | 闹钟触发 → PC 收到通知 |
| 断线重连 | 关闭蓝牙 → 重新连接 |
| 多设备 | 两台手机绑定同一台电脑 |

---

## 10. 项目结构

```
BleNotificationSync/
├── third_party/              # 第三方库源码
│   └── libtomcrypt/          # LibTomCrypt 源码（MIT 许可，仅 Android JNI 使用）
├── android/                  # Android SDK (Kotlin AAR)
│   ├── sdk/                  # SDK 核心模块
│   │   ├── crypto/           # 加密模块 (JNI + NativeCrypto)
│   │   ├── ble/              # BLE 通信模块（扫描/连接/权限）
│   │   ├── protocol/         # 协议编解码（FrameEncoder/FrameDecoder）
│   │   ├── qr/               # 二维码扫描（分层：解码/相机/UI）
│   │   ├── pairing/          # 配对管理（EncryptedSharedPreferences）
│   │   ├── sdk/              # SDK 入口 + 闹钟/前台Service
│   │   └── ui/               # 设备管理 Fragment
│   └── build.gradle.kts
├── examples/
│   └── android-demo/         # Demo App
├── desktop/                  # Tauri v2 跨平台桌面端 (Rust + Vanilla JS)
│   ├── src-tauri/            # Rust 后端
│   │   ├── src/
│   │   │   ├── main.rs       # 入口（panic hook + crash.log）
│   │   │   ├── lib.rs        # 应用核心（托盘/窗口/菜单/命令注册）
│   │   │   ├── ble.rs        # BLE GATT Server（FragmentBuffer 分片重组）
│   │   │   ├── crypto.rs     # AES-256-GCM + HKDF-SHA256
│   │   │   ├── protocol.rs   # 二进制帧协议（MSG_REGISTER/NOTIFY/ICON_DATA/ICON_END）
│   │   │   ├── notify.rs     # 通知适配（NotifyState epoch 防抖）
│   │   │   ├── config.rs     # 配置 + 图标存储 + keyring 密钥管理
│   │   │   ├── storage.rs    # 配对设备存储 + 注册表设置
│   │   │   └── event_handler.rs # 托盘菜单事件分发
│   │   ├── Cargo.toml
│   │   └── tauri.conf.json
│   ├── src/                  # Web 前端（中英双语 i18n 内嵌）
│   │   ├── index.html
│   │   ├── main.js
│   │   └── styles.css
│   └── package.json
├── windows/                  # [已废弃] 旧 C# .NET WinForms 实现
├── macos/                    # [已废弃] 旧 macOS Swift 实现（空目录）
├── docs/                     # 文档
│   ├── superpowers/specs/    # 设计文档
│   ├── superpowers/plans/    # 实现计划
│   └── reference/            # 原始方案参考
├── task_plan.md              # planning-with-files 任务计划
├── findings.md               # 研究发现与技术决策
├── progress.md               # 进度日志
├── LICENSE
└── README.md
```

---

## 11. 实现阶段

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | 协议规范文档 | ✅ 完成 |
| 2 | Android SDK 实现（54 单元测试） | ✅ 完成 |
| 3 | SDK API 改进（持久化/权限/错误码/生命周期） | ✅ 完成（连接复用推迟） |
| 4 | Tauri v2 桌面端骨架 + BLE GATT Server | ✅ 完成 |
| 5 | 桌面端 UI + 通知适配 + 图标同步 | ✅ 完成 |
| 6 | Android ↔ Windows 联调测试 | ✅ 完成 |
| 7 | Android ↔ macOS 联调测试 | ⏳ 待进行 |
| 8 | README 和集成文档 | ⏳ 待进行 |
