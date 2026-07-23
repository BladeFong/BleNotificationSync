# 预编译包与 Release 产物目录 (Pre-built Artifacts)

本目录归类存放了当前项目最新编译的各平台运行包、安装包与 SDK 库文件，方便在无完整编译环境或直接分发时使用。

---

## 目录与文件结构

```text
releases/
├── android/
│   ├── ble-notification-sdk.aar        # Android SDK 编译好的库文件 (AAR)
│   └── ble-notification-demo.apk       # Android 演示 App 安装包 (APK)
├── windows/
│   └── ble-notification-sync.exe       # Windows 桌面端直接运行包 (EXE)
└── linux/
    └── ble-notification-sync          # Linux 64位预编译可执行文件 (二进制)
```

---

## 使用说明

### 1. Android
- **`ble-notification-demo.apk`**：可直接使用 `adb install` 或传输至 Android 手机点击安装。
- **`ble-notification-sdk.aar`**：第三方 Android 项目可在 `build.gradle.kts` 中通过 `implementation(files("libs/ble-notification-sdk.aar"))` 本地引入。

### 2. Windows
- **`ble-notification-sync.exe`**：双击即可直接运行 BLE 通知监听服务与 GUI 管理界面。

### 3. Linux
- **`ble-notification-sync`**：预编译 64 位 Linux 二进制可执行文件。在终端运行：
  ```bash
  chmod +x ble-notification-sync
  ./ble-notification-sync
  ```
