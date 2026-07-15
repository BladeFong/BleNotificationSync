# Task Plan: BLE Notification Sync 开源项目

## Goal
创建一个跨平台 BLE 闹钟通知同步开源项目，包含协议规范、Android SDK、Windows 端和 macOS 端。

## Current Phase
Phase 1

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
- [ ] Windows 端集成 LibTomCrypt (源码编译 + P/Invoke)
- [ ] macOS 端集成 LibTomCrypt (源码编译 + Bridging Header)
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

### Phase 3.5: Android SDK API 改进（设计完成，实现中）
- [ ] 配对持久化（SharedPreferences + baseKey 存储）
- [ ] 权限内聚（BleClient.hasPermissions + 移除 @Suppress）
- [ ] 结构化错误码（SdkError 密封类替换 String）
- [ ] 生命周期管理（close()）
- [ ] 连接复用（推迟）
- 设计：`docs/superpowers/specs/2026-07-15-sdk-api-improvements-design.md`
- 计划：`docs/superpowers/plans/2026-07-15-sdk-api-improvements.md`
- **Status:** pending

### Phase 4: Windows 端 (C# .NET)
- [ ] 创建 .NET 项目结构
- [ ] 实现加密模块
- [ ] 实现 GATT Server
- [ ] 实现通知适配层（Toast）
- [ ] 实现配对存储（含密钥管理）
- [ ] 实现 WinForms 托盘 UI
- **Status:** pending

### Phase 5: macOS 端 (Swift)
- [ ] 创建 Xcode 项目结构
- [ ] 实现加密模块
- [ ] 实现 CBPeripheralManager
- [ ] 实现通知服务（UserNotifications）
- [ ] 实现配对存储（含密钥管理）
- [ ] 实现 SwiftUI MenuBarExtra UI
- **Status:** pending

### Phase 6: 联调测试
- [ ] Android ↔ Windows 互通测试（含加密）
- [ ] Android ↔ macOS 互通测试（含加密）
- [ ] 编写 README 和集成文档
- **Status:** pending

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
| WinForms 托盘 | 开发量最少，几行代码实现托盘 |
| SwiftUI MenuBarExtra | macOS 13+，代码量比 AppKit 少 70% |
| HKDF + AES-CCM | 密钥派生 + 认证加密，双方独立计算密钥 |
| LibTomCrypt 源码集成 | 跨平台一致，无二进制兼容问题 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| (none yet) | - | - |

## Notes
- 原始方案文档保留在 `docs/reference/original-scheme.md`
- 设计文档在 `docs/superpowers/specs/2026-07-10-ble-notification-sync-design.md`
- 实现规划在 `docs/superpowers/plans/`
