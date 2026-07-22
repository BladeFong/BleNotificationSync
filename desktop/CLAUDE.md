# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

本目录是 BLE Notification Sync 的 **Tauri v2 跨平台桌面端**（Windows/macOS/Linux）。`../windows/` 是旧的 C# .NET WinForms 实现，已废弃；`../macos/` 尚未开始。

## 开发环境

### WSL（代码编辑）

当前 WSL 路径：`/mnt/androiddev/MultiPlatformProjects/BleNotificationSync/desktop`

### Windows（编译和测试）

Windows 端同步路径：`D:\Documents\BleNotificationSync\`

WSL 编辑代码后，在 Windows PowerShell 中编译和测试：

```powershell
# 切换到 Windows 同步目录
cd D:\Documents\BleNotificationSync\desktop

# 安装 npm 依赖
npm install

# 开发模式（热重载）
npm run tauri dev

# 生产构建
npm run tauri build

# 仅运行 Rust 单元测试
cargo test --manifest-path src-tauri/Cargo.toml

# 检查 Rust 代码编译
cargo check --manifest-path src-tauri/Cargo.toml
```

**注意**：
- Tauri 编译和运行必须在 Windows 上进行（BLE 硬件访问、Windows 注册表等平台特性）
- WSL 仅用于代码编辑和 Git 操作
- 前端是 Vanilla JS，无构建工具，Tauri 直接加载 `src/` 目录。没有 `vite`、`webpack` 等前端构建流程

## 技术栈

| 层 | 技术 |
|----|------|
| 桌面框架 | Tauri v2（`tray-icon` 特性） |
| 后端 | Rust (edition 2021) + tokio 异步 |
| 前端 | Vanilla HTML/CSS/JS，无框架 |
| BLE | btleplug v0.11（跨平台 BLE） |
| 加密 | Rust `aes-gcm` 0.10 + `hkdf` 0.12 + `sha2` 0.10 |
| 单实例 | tauri-plugin-single-instance v2 |

## 架构概览

```
desktop/
├── src/                    # 前端（Vanilla JS）
│   ├── index.html          # 主页面，内嵌中英双语 i18n
│   ├── main.js             # Tauri 命令调用 + 事件监听
│   └── styles.css          # 终端风格 UI
├── src-tauri/              # Rust 后端
│   ├── Cargo.toml          # Rust 依赖
│   ├── tauri.conf.json     # Tauri v2 配置
│   └── src/
│       ├── main.rs         # 入口：调用 lib::run()
│       ├── lib.rs          # 应用核心：托盘菜单、窗口管理、命令注册
│       ├── ble.rs          # BLE GATT Server（占位实现）
│       ├── crypto.rs       # AES-256-GCM + HKDF-SHA256
│       ├── event_handler.rs # 托盘菜单事件分发
│       ├── protocol.rs     # BLE 二进制帧协议（解析/构建）
│       └── storage.rs      # 配对设备存储 + 注册表设置
└── package.json            # npm 脚本（仅 tauri CLI）
```

## Rust 模块职责

### `lib.rs` — 应用入口 + 系统托盘
- `AppState`：持有托盘菜单中 BLE 服务勾选项的引用，用于动态更新勾选状态
- `run()`：构建 Tauri 应用，注册插件（single_instance、opener）、托管状态（`BleState`、`StorageState`、`AppState`）、注册 13 个 Tauri 命令
- 窗口关闭行为：隐藏而非退出（`CloseRequested` → `prevent_close`）
- 静默模式：启动时直接隐藏窗口并启动 BLE 服务
- 托盘菜单项：显示窗口 / 启动服务（勾选） / 开机自启动（勾选） / 静默启动服务（勾选） / 退出

### `ble.rs` — BLE GATT Server
- `BleState`：`is_running: Mutex<bool>`
- `start_gatt_server` / `stop_gatt_server` / `get_status`：占位实现（TODO）
- `get_mac_address`：Windows PowerShell 获取蓝牙网卡 MAC（含回退逻辑）

### `crypto.rs` — 加密模块（有单元测试）
- `derive_key(package_name: &str) -> [u8; 32]`：HKDF-SHA256 密钥派生，`info=""` 对齐 Android 端
- `encrypt(key, plaintext) -> (nonce, ciphertext)`：AES-256-GCM 加密
- `decrypt(key, nonce, ciphertext) -> Option<Vec<u8>>`：解密

### `protocol.rs` — 通信协议（有单元测试）
- 魔数：`0xAA 0xBB`，帧头 5 字节
- 消息类型：`MSG_REGISTER(0x01)`、`MSG_NOTIFY(0x02)`、`MSG_ACK(0x03)`、`MSG_ICON_DATA(0x04)`、`MSG_ICON_END(0x05)`
- `Frame { msg_type, seq, total_seq, payload }`
- `parse_frame(data: &[u8]) -> Option<Frame>`：解析帧
- `build_frame(msg_type, seq, total_seq, payload) -> Vec<u8>`：构建帧

### `storage.rs` — 存储 + 设置
- `PairedDevice { mac, app_name, package_name, paired_at }`
- `StorageState`：内存 HashMap 存储配对设备 + 密钥缓存
- 设置持久化：写入 Windows 注册表 `HKCU\Software\...\Run`（`set_autostart` / `set_silent_mode`）

### `event_handler.rs` — 托盘事件
- `handle_menu_event`：按 `event_id` 分发到对应处理函数
- `show_window`：显示窗口 + 同步 BLE 状态到前端
- `toggle_ble_service`：切换 BLE 状态 + 更新托盘勾选 + 通知前端
- `toggle_autostart` / `toggle_silent_mode`：切换注册表设置
- `quit_app`：停止 BLE 服务 → `std::process::exit(0)`

## 前端架构

- 无框架，`window.__TAURI__.core.invoke("command_name", { args })` 调用 Rust 命令
- 事件监听：`window.__TAURI__.event.listen("ble-status-sync", callback)` 接收后端状态推送
- 语言：根据 `navigator.language` 自动选中文/英文，`index.html` 内嵌翻译映射表
- 扫码绑定：后端获取 MAC → 前端生成简易二维码 canvas → 等待手机扫描

## 跨平台关系

| 目录 | 平台 | 状态 |
|------|------|------|
| `android/` | Android SDK (Kotlin) | 完成（54 单元测试） |
| `desktop/` | Windows/macOS/Linux (Tauri v2) | 开发中 |
| `windows/` | Windows (C# WinForms) | 已废弃，Tauri 迁移前旧代码 |
| `macos/` | macOS (Swift) | 空目录，已合并到 Tauri 方案 |

## 项目文档位置

- 设计文档：`../docs/superpowers/specs/`
- 实现计划：`../docs/superpowers/plans/`
- planning-with-files：`../task_plan.md`、`../findings.md`、`../progress.md`

## 关键设计决策

- **AES-256-GCM**（非 CCM）：对齐 Rust `aes-gcm` crate，Android 端已同步改为 GCM
- **HKDF-SHA256 info=""**：密钥派生时不使用额外上下文信息
- **桌面端使用 Rust 原生加密 crate**：不依赖 LibTomCrypt（仅 Android JNI 使用）
- **加密密钥 = HKDF(package_name + random)**：配对时 Android 生成 32B 随机数，双方 HKDF 派生 baseKey 持久化
- **窗口关闭 = 隐藏到托盘**：`CloseRequested` 时 `prevent_close()`，仅托盘菜单"退出"真正退出进程
