# Progress Log

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
