# 预编译包与 Release 产物目录 (Pre-built Artifacts)

本目录归类存放了当前项目最新编译的各平台运行包、安装包与 SDK 库文件。

> [!IMPORTANT]
> **桌面端安装包已移出 Git 仓库**：为了保持代码库的轻量，Windows 侧（`.msi`/`_setup.exe`/`.zip`）及 Linux 侧（`.tar.gz`）的预编译二进制安装包已不再直接提交和追踪于 Git 源码树中。
> 
> 请前往项目 GitHub 主页右侧的 [**Releases**](https://github.com/BladeFong/BleNotificationSync/releases) 页面，一键下载最新的官方预编译发布包。

---

## 目录与文件结构

```text
releases/
└── android/
    └── ble-notification-sdk.aar                    # Android SDK 编译好的库文件 (AAR, 220KB)
```

---

## 使用说明

### 1. 桌面端 (Windows / Linux)
请直接在项目 GitHub 主页右侧的 **Releases** 模块中下载对应平台的正式包：
- **Windows 安装版**：`BLE_Notification_Sync_<version>_x64_setup.exe` / `BLE_Notification_Sync_<version>_x64.msi`
- **Windows 免安装版**：`ble-notification-sync.zip` (解压后双击运行)
- **Linux 预编译版**：`ble-notification-sync.tar.gz` (解压后运行 `chmod +x ble-notification-sync` 赋予权限并启动)

### 2. Android 端
- **SDK 库文件**：`ble-notification-sdk.aar` 依然保存在当前 Git 物理目录下（`releases/android/`），第三方 Android 项目可在 `build.gradle.kts` 中通过 `implementation(files("libs/ble-notification-sdk.aar"))` 本地引入。
- **Maven/JitPack 依赖**：推荐直接在 `build.gradle.kts` 中通过 JitPack 一行集成：
  ```kotlin
  implementation("com.github.BladeFong:BleNotificationSync:<version>")
  ```
- **演示 App (APK)**：`ble-notification-demo.apk` 已经移出仓库，可在 Releases 发行版页面的 Assets 资源列表中直接下载，或本地通过 `./gradlew assembleDebug` 编译生成。

