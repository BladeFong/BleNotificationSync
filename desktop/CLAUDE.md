# CLAUDE.md

## 项目定位

本目录是 BLE Notification Sync 的 **Tauri v2 跨平台桌面端**（Windows/macOS/Linux），项目整体设计与开发进度见上级目录专门文档。

## 开发环境与构建规范

### WSL（代码编辑）

当前 WSL 路径：`/mnt/androiddev/MultiPlatformProjects/BleNotificationSync/desktop`

### Windows（编译和测试）

Windows 端同步路径：`D:\Documents\BleNotificationSync\`

WSL 编辑代码后，在 Windows PowerShell 中编译和测试：

```powershell
# 编译直接运行版
powershell.exe -Command "Stop-Process -Name 'ble-notification-sync' -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 2; cd D:\Documents\BleNotificationSync\desktop; cargo build --release --manifest-path src-tauri\Cargo.toml" 2>&1 | tail -3

# 编译安装版
powershell.exe -Command "Stop-Process -Name 'ble-notification-sync' -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 2; cd D:\Documents\BleNotificationSync\desktop; npm run tauri build" 2>&1 | tail -10
```

### 关键约束

- **编译前代码同步**：在 Windows 端编译验证前，必须先将代码同步至 `D:\Documents\BleNotificationSync\desktop`
- **构建并发控制**：一次只能跑一个编译命令，**禁止**并发下发多个编译/构建任务（避免抢占 target/ 锁死）
- **PowerShell 命令执行**：所有 PowerShell 命令行必须及时捕获并输出返回结果，严禁无声在后台长时间悬挂
- **Rust 官方文档检索**：`https://docs.rs/` 是 Rust 官方 Crate 文档中心，排查或使用 Rust 开源库时优先从 docs.rs 获取最新官方 API 规范与结构体定义
- **平台限制**：Tauri 编译和运行必须在 Windows 上进行（BLE 硬件访问、Windows 凭据/通知等平台特性）
- **环境划分**：WSL 仅用于代码编辑、Git 操作和 Linux 编译
- **前端规则**：前端是 Vanilla JS，无构建工具，Tauri 直接加载 `src/` 目录。没有 `vite`、`webpack` 等前端构建流程

## 专门文档指针

项目设计、规范、实现计划与进度请参阅以下专门文档：
- **设计规范**：`../docs/superpowers/specs/`
- **实现计划**：`../docs/superpowers/plans/`
- **任务规划**：`../task_plan.md`
- **技术决策**：`../findings.md`
- **进度日志**：`../progress.md`
- **项目说明**：`../README.md`

