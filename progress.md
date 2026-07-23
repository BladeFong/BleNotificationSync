# Progress Log

## 2026-07-23 — 通知功能优化

- **问题**：
  - 安装版通知使用 PowerShell 而非原生 Toast
  - 通知检测逻辑不正确（路径比较问题）
- **修复**：
  - 查询 HKCU 注册表获取安装路径
  - 比较运行路径与安装路径是否一致
  - 安装版使用 notify-rust 原生通知
  - 开发版使用 PowerShell 通知兜底
- **待改进**：
  - 通知图标显示（需要 icon.ico 文件）
  - 通知在通知中心停留问题

## 2026-07-23 — BLE 连接问题修复 + UI 优化

- **问题**：
  - 停止服务后重新启动，Android 端 status=133 连接失败
  - 启动服务前扫码绑定按钮可点击
  - 广播状态检测不准确（总是显示"未广播/异常"）
- **修复**：
  - 切回 `ble-peripheral-rust` 跨平台库
  - characteristic 同时配置 `Write` + `WriteWithoutResponse`
  - 启动服务前先重置状态
  - 服务未启动时禁用扫码绑定按钮
  - 广播状态检测：启动后等待 500ms 再检测
- **项目整理**：
  - 移动 `android/demo` → `examples/android-demo`
  - 删除 `windows/`、`macos/`
  - 保留 `third_party/`（被 Android JNI 使用）

## 2026-07-22 — keyring crate 集成（跨平台安全存储）

- **改动内容**：
  - 使用 `keyring` crate 替代 Windows 特定的 `cmdkey`/PowerShell 实现
  - 移除 106 行平台特定代码，替换为 31 行跨平台 API
  - 统一接口：`store_base_key` / `get_base_key` / `delete_base_key`
- **支持平台**：
  - Windows: Credential Manager
  - macOS: Keychain
  - Linux: Secret Service (libsecret)
- **依赖**：`keyring = "4"` (v4.1.5)
- **注意**：需要重新绑定，旧的 `cmdkey` 存储的密钥不兼容

## 2026-07-22 — 桌面端设备标识重构（移除 MAC 地址）

- **改动内容**：
  - 设备标识从 PC BLE MAC 改为 Android ANDROID_ID（`device_id`）
  - 设备列表显示手机设备名（`device_name`），不再显示 MAC
  - 扫码绑定对话框只显示 PC 设备名，不再显示 MAC
  - REGISTER 消息支持可选的 `android_id` 和 `device_name` 字段（向后兼容）
  - 密钥存储使用 `device_id` 作为标识
  - QR 码格式移除 `mac=` 参数
- **涉及文件**：
  - `ble.rs`：handle_register、handle_notify、sync_to_config、DeviceInfo
  - `storage.rs`：PairedDevice 结构体、add/remove/get_paired_device
  - `config.rs`：DeviceEntry 结构体、store/get/delete_base_key
  - `main.js`：扫码对话框、设备列表显示
- **待 Android 端同步**：
  - REGISTER 消息添加 `android_id` + `device_name` 字段
  - NOTIFY 消息在加密内容中带上 `android_id`
  - QR 码格式移除 `mac` 字段

## 2026-07-22 — 桌面端代码审查与安全修复

- **审查范围**：Tauri v2 桌面端（Rust 后端 + Vanilla JS 前端）
- **已修复问题**：
  - 命令注入（config.rs）：PS 脚本改用 `$args[0]` 参数化传递
  - XSS（main.js）：`innerHTML` 改为 `textContent` + `createTextNode`
  - PowerShell 注入（notify.rs）：改用 `-args` 参数化传递
  - 协议验证（protocol.rs）：添加 `total_seq` 和 `seq` 边界校验
- **已处理问题**：
  - unsafe impl Send/Sync（lib.rs）：添加 `# Safety` 文档说明
- **验证为误报**：Mutex 死锁（所有场景锁顺序一致）
- **待处理项**：错误处理统一、i18n 方案、资源管理优化

## Session: 2026-07-21 — SDK 权限 API 重构 + BLE 后台扫描排查

- **Status:** 权限 API 重构完成，BLE 后台扫描问题待最终验证
- **SDK 权限 API 重构**：
  - `registerPermissionLaunchers(activity)`：onCreate 注册两段式位置权限 Launcher
  - `ensurePermissions(activity)`：onResume 统一检查 BLE + 位置所有权限，幂等方法
  - `startPairing`：移除权限逻辑，纯业务流程
  - 两段式位置权限：先 FINE_LOCATION（用户选"仅使用时允许"），再 BACKGROUND_LOCATION（用户选"始终允许"），使用 `registerForActivityResult` 异步接力
- **BLE 后台扫描排查**：
  - 前台服务类型改为 `connectedDevice|location`（参考 MetaRadar 项目）
  - 添加 `FOREGROUND_SERVICE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` 权限
  - 旧权限 `BLUETOOTH`/`BLUETOOTH_ADMIN` 加 `maxSdkVersion="30"`
  - `connectWithScan` 加 GPS 总开关检查日志
  - 扫描回调改为成员变量防止 GC
- **Demo 应用**：
  - `onCreate` 调用 `sdk.registerPermissionLaunchers(this)`
  - `onResume` 调用 `sdk.ensurePermissions(this)`（幂等）
  - 新增"扫描 BLE 设备"按钮用于前台调试
- **BLE MAC 行为确认**：Windows/macOS 广播地址随时可能变化，Android 端必须始终用 UUID 扫描
- **MetaRadar 参考**：前台服务需声明 `CONNECTED_DEVICE|LOCATION` 类型才能后台 BLE 扫描

## Session: 2026-07-21 — 通知修复与密钥对齐

- **Status:** Android↔Windows 联调通过，通知三级回退机制就绪
- **根因定位**：`tauri-plugin-notification` 内部 `let _ = notification.show()` 静默吞掉错误，导致通知失败无任何日志。同时 `notify_rust` 的 WinRT Toast 在未安装应用（无 AUMID）时静默失败。
- **通知三级回退**：Tauri 跨平台组件 → notify_rust（WinRT Toast / macOS / Linux）→ PowerShell BalloonTip（Windows 兜底），每层失败均有日志输出。
- **密钥 IKM 对齐**：修复 Android 端 `AesGcmCrypto.encrypt` 使用 `NativeCrypto.deriveKey(packageName)` 导致 IKM 缺失 random 的 bug。改为直接使用注册时存储的 baseKey，与桌面端 `HKDF(salt, packageName+random, L=32)` 对齐。
- **安全确认**：注册时加 random 确保 baseKey 不可从公开信息（包名+salt）推导，开源后仍安全。
- **Android SDK 接口变更**：`AesGcmCrypto.encrypt/decrypt` 改为接受 `key: ByteArray` 参数，`FrameEncoder.encodeNotify` 同步新增 `key` 参数，`BleNotificationSDK.sendNotification` 从 `PairingManager.getBaseKey()` 获取密钥传入。
- **Tauri 通知组件验证**：`tauri-plugin-notification` 在 Windows 上可正常工作（之前失败是密钥不匹配导致通知未到达发送步骤），保留为跨平台首选方案，支持后续 macOS 适配。
- **编译验证**：桌面端 release + Android demo 均编译通过，Android demo 已安装到手机测试。

## Session: 2026-07-18 — Android-Windows联调成功与通知字段对接

- **Status:** Android 与 Windows 端 BLE 联调成功，解决通知推送与系统通知弹出问题
- **权限与ROM兼容修复**：定位并解决 Android 12+ BLE 扫描权限在某些 ROM 下被静默过滤的问题，通过在 Android SDK 端补充地址权限请求，成功打通蓝牙扫描，获得 Windows 外设的 BLE 随机 MAC 地址并成功连接。
- **GATT 框架定位**：确认 Windows 桌面端仍然使用高效可靠的原生 WinRT APIs（通过 `windows` 库直接构建 `GattServiceProvider`），彻底弃用了有广播冲突和参数错误限制的旧兼容组件。
- **凭据管理器读写修正**：修复了 Windows 凭据管理器（Credential Manager）由于 `cmdkey` 语法不规范（未加冒号且误用 `/add` 域凭据）导致密钥实际写入失败的 Bug（改为 `/generic` 通用凭据和冒号参数）。同时重构了 `get_base_key`，使用基于 C# 互操作调用的 `CredReadW` 原生 API 读取密码，摆脱了对第三方 `Get-StoredCredential` 模块的依赖。
- **通知解析与跨平台通知插件（tauri-plugin-notification）对接**：修复了双端字段键名不一致（`body` vs `content`）导致 JSON 反序列化失败的 Bug。重构还原了官方的 `tauri-plugin-notification` 系统，采用异步 `tokio::spawn` 执行，并在弹出前动态检测/申请 OS 通知权限；同时针对未签名开发版应用会被 Windows Action Center 静默拦截 Toast 通知的问题，确定了需通过安装包安装（以便由安装程序注册 Start Menu 快捷方式及匹配的 AppUserModelId）或在开发模式下手动添加应用快捷方式的系统规则，同时将通知插件的内部报错直接导出到前端界面日志框以便调试。

## Session: 2026-07-17 — Windows Native WinRT BLE Implementation & Compilation

- **Status:** Windows 原生 WinRT BLE 广播修复并打包成功
- **GATT 广播修复**：在 Windows 上绕过 `ble-peripheral-rust` 跨平台包装器，直接接入 Windows 原生 UWP/WinRT 接口。
- **参数错误（0x80070057）解决**：针对 `BluetoothLEAdvertisementPublisher`，将原本失败的 `Create(&adv)` 方式改为使用默认的 `new()` 构造函数，然后向其内部的 `Advertisement` 对象中添加 `Service UUID`。此方式与 C# GATT 实现对齐，完全解决了启动时报错的问题。
- **广播可见性修复**：同时启动 `GattServiceProvider`（管理 GATT 特征值并接收连接）与 `BluetoothLEAdvertisementPublisher`（在广播包中宣告 UUID 字段），解决 Windows 蓝牙广播不携带 Service UUID 导致客户端无法通过 UUID 过滤扫描到的问题。
- **生命周期安全保护**：在 `BleState` 中维护 `WindowsBleResources`，防止 provider 和 publisher 相关的 WinRT COM 对象在主函数返回时被析构，保证了 BLE 服务的长效性。
- **编译器错误解决**：通过对 TypedEventHandler 的声明与注册包裹局部作用域（Block），避免非 `Send` 的 WinRT 代理对象生命周期跨越 `tokio::select!` Await 点引起的 `future cannot be sent between threads safely` 编译报错。
- **编译打包**：成功在 Windows 侧生成了 release 二进制文件 `ble-notification-sync.exe` 以及 `msi`/`nsis` 安装包。

## Session: 2026-07-16 — 跨平台 BLE 外设重构

- **Status:** 跨平台 BLE 外设实现完成并编译通过
- 分析并定位 Windows 平台下自定义广播 `BluetoothLEAdvertisementPublisher` 导致 `E_INVALIDARG` (0x80070057) 报错的原因，认定其与 `GattServiceProvider` 存在广播冲突。
- 物理删除平台专属的 `ble_winrt.rs` 文件，清理了 `lib.rs` 中的 Windows 条件编译和模块声明。
- 在 `ble.rs` 中使用 `ble-peripheral-rust` 库重构实现统一的外设与 GATT 服务。
- 在 `ble.rs` 中重构了 `get_ble_mac()`，为 Windows（PowerShell 脚本）和 macOS（`system_profiler`）提供了通用的物理 MAC 地址获取逻辑。
- 在 `BleState` 中引入 `shutdown_tx` 单发通道，在 `stop_service` 时发送信号终止后台轮询任务，支持释放 BLE 外设资源，避免生命周期泄漏。

## Session: 2026-07-15 — Tauri 桌面端迁移

- **Status:** 核心框架就绪，BLE 和通知待实现
- 新增 `desktop/` Tauri v2 项目替代旧 `windows/` (C# WinForms) + `macos/` 独立实现
- Tauri 后端模块：lib.rs（托盘+窗口管理）、ble.rs（BLE 占位）、crypto.rs（AES-GCM+HKDF，含测试）、protocol.rs（帧协议，含测试）、storage.rs（配对+注册表）、event_handler.rs（托盘事件分发）
- 前端 Vanilla JS，中英双语，通过 `window.__TAURI__.core` 调用命令
- 加密对齐：桌面端使用 Rust 原生 aes-gcm/hkdf crate，Android 端同步改为 AES-GCM
- 同步更新 task_plan.md（Phase 4 合并跨平台桌面端）、findings.md（加密算法和选型记录）
- `../windows/` 旧 C# 实现标记为已废弃

## Session: 2026-07-15 — SDK API 改进设计

- **Status:** 设计完成，进入实现
- 完成 SDK API 改进设计文档（`docs/superpowers/specs/2026-07-15-sdk-api-improvements-design.md`）
- 完成实现计划（`docs/superpowers/plans/2026-07-15-sdk-api-improvements.md`）
- 修正主设计文档密钥管理策略：配对时生成随机数 + HKDF(package+random) → baseKey 持久化
- 5 项改进：配对持久化 / 权限内聚 / 结构化错误码 / 生命周期管理 / 连接复用推迟

## Session: 2026-07-10

### Phase 1: 协议规范 & 设计文档
- **Status:** complete
- **Started:** 2026-07-10
- **Completed:** 2026-07-10
- Actions taken:
  - 阅读原始方案文档
  - 与用户确认需求（业务模式、数据格式、配对方式、MVP 范围、SDK 技术栈）
  - 设计通信协议（GATT 服务、数据帧、消息类型）
  - 设计配对流程（二维码、时序图、状态机）
  - 设计 SDK API（配对、闹钟、通知）
  - 设计 PC/Mac 端架构
  - 设计错误处理策略
  - 设计加密方案（HKDF-SHA256 + AES-CCM）
  - 编写完整设计文档
  - 创建 planning-with-files 结构
  - 编写实现规划文档
- Files created/modified:
  - `docs/superpowers/specs/2026-07-10-ble-notification-sync-design.md` (created)
  - `docs/superpowers/plans/2026-07-10-ble-notification-sync.md` (created)
  - `docs/reference/original-scheme.md` (created)
  - `task_plan.md` (created)
  - `findings.md` (created)
  - `progress.md` (created)

### Phase 2: 实现计划生成
- **Status:** complete
- **Started:** 2026-07-10
- **Completed:** 2026-07-10
- Actions taken:
  - 生成 writing-plans 格式的实现计划（16 个 Task）
  - 更新 task_plan.md 和 findings.md
  - 添加扫码分层实现（Task 8: QrDecoder/QrScanner/QrScannerFragment）
- Files created/modified:
  - `docs/superpowers/plans/2026-07-10-ble-notification-sync.md` (updated, 2175 行)

### Phase 3: 加密模块实现
- **Status:** pending

### Phase 4: Android SDK (Kotlin)
- **Status:** pending

### Phase 4: 跨平台桌面端 (Tauri v2)
- **Status:** 进行中（核心框架就绪，BLE 和通知待实现）
- 替代原 Phase 5 (C# Windows) + Phase 6 (Swift macOS)

### Phase 5: 联调测试
- **Status:** pending

## Test Results
| Test | Input | Expected | Actual | Status |
|------|-------|----------|--------|--------|
| (none yet) | - | - | - | - |

## Error Log
| Timestamp | Error | Attempt | Resolution |
|-----------|-------|---------|------------|
| (none yet) | - | - | - |

## 5-Question Reboot Check
| Question | Answer |
|----------|--------|
| Where am I? | Phase 1-3 完成，Phase 4 (Tauri 桌面端) 进行中，核心框架就绪 |
| Where am I going? | Phase 4: 实现 BLE GATT Server + 通知适配 + macOS/Linux 兼容；Phase 5: 联调测试 |
| What's the goal? | 创建跨平台 BLE 闹钟通知同步开源项目 |
| What have I learned? | 见 findings.md |
| What have I done? | 设计文档 + Android SDK + Tauri 桌面端骨架；旧 windows/ C# 实现已废弃 |
