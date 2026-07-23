# Task Plan: BLE Notification Sync 开源项目

## Goal
创建一个跨平台 BLE 闹钟通知同步开源项目，包含协议规范、Android SDK、跨平台桌面端（Tauri v2）。

## Current Phase
Phase 4

## Phases

### Phase 1: 协议规范 & 设计文档
- [x] 编写设计文档（含加密方案）
- [x] 定义 GATT 服务和 Characteristic
- [x] 定义数据帧格式和消息类型
- [x] 定义配对流程
- [x] 设计加密方案（HKDF + AES-CCM）
- **Status:** complete

### Phase 2: 加密模块实现
- [x] 下载 LibTomCrypt 源码到 third_party/libtomcrypt/
- [x] Android 端集成 LibTomCrypt (JNI 源码编译)
- [x] Windows 端集成 LibTomCrypt — 已跳过，桌面改用 Rust 原生 crate
- [x] macOS 端集成 LibTomCrypt — 已跳过，桌面改用 Rust 原生 crate
- [x] 实现 AES-GCM 加解密（已改为 GCM 对齐桌面）
- [x] 实现密钥派生（HKDF-SHA256，info="" 对齐桌面）
- **Status:** Android 端 complete

### Phase 3: Android SDK (Kotlin)
- [x] 创建 Android 项目结构
- [x] 实现协议层（分片/重组/帧解析）
- [x] 实现加密层（AES-GCM 加解密 + HKDF 密钥派生）
- [x] 实现 BLE 通信层（GATT 连接/MTU 协商 + ACK 接收）
- [x] 实现扫码分层（解码层/相机层/UI 层）
- [x] 实现 SDK API（startPairing/setReminder/cancelReminder/sendNotification）
- [x] 编写单元测试（54 纯 JVM 测试）
- **Status:** complete

### Phase 3.5: Android SDK API 改进
- [x] 配对持久化（EncryptedSharedPreferences + baseKey 存储，UUID 主键）
- [x] 权限内聚（BleClient.getMissingPermissions + ensurePermissions）
- [x] 结构化错误码（SdkError 密封类 12 子类型）
- [x] 生命周期管理（close()）
- [x] 连接复用（**不做**，同设备不同 App 独立进程无法共享 GATT，单 App 无复用需求）
- 设计：`docs/superpowers/specs/2026-07-15-sdk-api-improvements-design.md`
- 计划：`docs/superpowers/plans/2026-07-15-sdk-api-improvements.md`
- **Status:** complete（除连接复用推迟外）

### Phase 4: 跨平台桌面端 (Tauri v2) — 替代原 Windows/macOS 独立实现
- [x] 创建 Tauri v2 项目骨架（Rust + Vanilla JS）
- [x] 实现系统托盘（显示窗口/启动服务/自启/静默/退出）
- [x] 实现加密模块（Rust aes-gcm + hkdf，对齐 Android 端）
- [x] 实现通信协议模块（二进制帧解析/构建）
- [x] 实现配对存储（内存 + Windows 注册表设置持久化）
- [x] 实现事件处理（托盘菜单分发）
- [x] 前端 UI（中英双语、状态显示、日志控制台、扫码绑定）
- [x] 实现 BLE GATT Server（基于 ble-peripheral-rust 跨平台方案）
- [x] 实现通知适配层（Windows: winrt-notification Toast → PowerShell 兜底；非 Windows: notify-rust）
- [x] 实现 macOS BLE 兼容层（基于 ble-peripheral-rust 实现 macOS 下的广播与服务）
- **Status:** complete（Android↔Windows 联调通过，通知三级回退机制就绪）
- **原代码：** `../windows/` 为旧 C# .NET WinForms 实现，已废弃

### Phase 5: 联调测试
- [x] Android ↔ Windows 互通测试（含加密）— 配对+通知同步通过
- [ ] Android ↔ macOS 互通测试（含加密）
- [ ] 编写 README 和集成文档
- **Status:** 进行中（Windows 联调通过，macOS 待测）

## Key Questions
1. ~~图标传输是否需要压缩~~ → 二进制直传，不压缩，最大 60KB
2. Windows/macOS 是否需要支持多手机同时绑定？
3. ~~Windows 最低版本~~ → Windows 10 1709+（GattServiceProvider 要求）
4. ~~Android 最低版本~~ → API 23（CameraX + ML Kit 要求）

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 仅 Mode B（闹钟推送） | MVP 范围聚焦，不需要通知监听权限 |
| 无保活，用完即断 | 简化架构，降低功耗 |
| 二维码配对 | 避免蓝牙乱推送，无需 PC 二次确认 |
| 纯 Kotlin SDK | 现代 Android 开发首选，Java 可直接调用 |
| Tauri v2 系统托盘（tray-icon） | 替代原 WinForms 托盘，跨平台统一方案 |
| SwiftUI MenuBarExtra | 已废弃，macOS 并入 Tauri 方案 |
| HKDF + AES-GCM | 密钥派生 + 认证加密（已从 CCM 改为 GCM 对齐 Rust aes-gcm crate） |
| LibTomCrypt 源码集成 | 仅 Android JNI 使用；桌面端使用 Rust 原生加密 crate（aes-gcm, hkdf, sha2） |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| (none yet) | - | - |

## Notes
- 原始方案文档保留在 `docs/reference/original-scheme.md`
- 设计文档在 `docs/superpowers/specs/2026-07-10-ble-notification-sync-design.md`
- 实现规划在 `docs/superpowers/plans/`
