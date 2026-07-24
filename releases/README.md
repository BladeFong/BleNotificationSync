# 预编译包与 Release 产物目录 (Pre-built Artifacts)

本目录归类存放了当前项目最新编译的各平台运行包、安装包与 SDK 库文件，方便在无完整编译环境或直接分发时使用。

---

## 目录与文件结构

```text
releases/
├── android/
│   └── ble-notification-sdk.aar                    # Android SDK 编译好的库文件 (AAR)
├── windows/
│   ├── ble-notification-sync.zip                   # Windows 64位独立免安装版压缩包 (ZIP)
│   ├── BLE_Notification_Sync_0.1.0_x64_setup.exe   # Windows 64位 NSIS 安装向导程序
│   └── BLE_Notification_Sync_0.1.0_x64.msi         # Windows 64位 MSI 安装包
└── linux/
    └── ble-notification-sync.tar.gz                # Linux 64位预编译二进制压缩包 (TAR.GZ)
```

---

## 使用说明

### 1. Windows 桌面端
- **`BLE_Notification_Sync_0.1.0_x64_setup.exe`**：标准图形界面安装向导，自动创建快捷方式与 AppUserModelId。
- **`BLE_Notification_Sync_0.1.0_x64.msi`**：Windows Installer 部署安装包。
- **`ble-notification-sync.zip`**：独立免安装运行包，解压后双击 `ble-notification-sync.exe` 运行。

### 2. Linux 桌面端
- **`ble-notification-sync.tar.gz`**：预编译 64 位 Linux 二进制压缩包。解压后在终端运行：
  ```bash
  tar -xzvf ble-notification-sync.tar.gz
  chmod +x ble-notification-sync
  ./ble-notification-sync
  ```

### 3. Android 端
- **SDK 库文件**：`ble-notification-sdk.aar`，第三方 Android 项目可在 `build.gradle.kts` 中通过 `implementation(files("libs/ble-notification-sdk.aar"))` 本地引入。
- **演示 App (APK)**：`ble-notification-demo.apk` 已从 Git 仓库移除（以减小仓库体积），可在 GitHub Releases 页面下载，或本地通过 `./gradlew assembleDebug` 编译生成。
