# 多 PC 绑定与内置设备管理组件 (Multi-PC Pairing & Device Manager) 设计文档

## 1. 概述与设计目标

本文档定义了 Android SDK 端对**多 PC 绑定**的支持方案，以及内置的**设备管理界面 (`DeviceManagerFragment`)** 设计。

### 主要目标
1. **多 PC 绑定支持**：弃用单设备存储，以 PC 端生成的唯一固定 **`uuid`** 作为主键管理多台 PC 的配对信息。
2. **硬件隐私与随机 MAC 适配**：绝不使用 GATT/BLE 随机 MAC 地址（RPA）作为设备标识，避免 MAC 地址频繁变动导致绑定失效。
3. **响应式 API 暴露**：SDK 提供 `StateFlow<List<PairedDevice>>` 状态流及同步 API，允许宿主 App 自定义 UI 或使用 SDK 内置 UI。
4. **开箱即用内置 UI**：提供基于 **Jetpack Compose + Material 2 (M2)** 风格的 `DeviceManagerFragment` 与 `DeviceManagerActivity`，仅展示 PC **设备名 (`name`)**，提供设备列表展示、解除绑定以及扫码绑定功能。
5. **主题契合**：自动读取并同步宿主 App 主题色 (`colorPrimary`)。

---

## 2. 数据模型与存储架构 (Data Model & Storage)

### 2.1 数据模型定义

```kotlin
package com.ble.notification.pairing

data class PairedDevice(
    val uuid: String,        // PC 设备唯一标识（不直接在 UI 列表展示）
    val name: String,        // PC 设备名称（UI 列表展示的名称）
    val appName: String,     // 配对来源 App 标识
    val pairedAt: Long       // 绑定时间戳 (毫秒)
)
```

### 2.2 持久化存储规范 (`PairingManager`)

* **存储介质**：`EncryptedSharedPreferences` (维持现有 MasterKey AES256 加密)
* **存储 Key 格式**：`pairing_$uuid` (前缀 + PC 的唯一 `uuid`)
* **存储 Value 格式**：`$uuid|$name|$appName|${baseKey.joinToString(",")}|$pairedAt`
* **存量升级迁移 (Migration)**：
  * SDK 初始化时检查是否存在历史单设备 Key `pairing_$packageName`。
  * 若存在旧格式，提取秘钥与 MAC/旧数据后，转换为基于 `uuid`（或旧 MAC 生成的兼容 UUID）的 `pairing_$uuid` 存储，并删除旧 Key。

---

## 3. 底层 API 规范 (`BleNotificationSDK`)

SDK 提供完整的开放 API，支持宿主 App 自定义界面或使用内置 UI：

```kotlin
package com.ble.notification.sdk

import androidx.fragment.app.Fragment
import com.ble.notification.pairing.PairedDevice
import kotlinx.coroutines.flow.StateFlow

class BleNotificationSDK private constructor(private val context: Context) {

    /** 观察所有已绑定 PC 列表的响应式 StateFlow */
    val pairedDevicesState: StateFlow<List<PairedDevice>>

    /** 同步获取当前已绑定 PC 列表 */
    fun getPairedDevices(): List<PairedDevice>

    /** 判断是否有已绑定的 PC 设备（或指定 UUID 是否已绑定） */
    fun isPaired(uuid: String? = null): Boolean

    /** 解除指定 UUID 的 PC 设备绑定 */
    fun unpair(uuid: String)

    /** 解除所有已绑定的 PC 设备 */
    fun unpairAll()

    /** 触发扫码配对流程 */
    fun startPairing(
        activity: FragmentActivity,
        appName: String,
        callback: PairingCallback
    )

    /** 获取 SDK 内置的设备管理 Fragment (Compose M2 界面) */
    fun getDeviceManagerFragment(): Fragment

    /** 快捷打开 SDK 内置的设备管理 Activity */
    fun openDeviceManager(context: Context)

    /** 发送加密通知到首个在场已绑定 PC */
    fun sendNotification(title: String, body: String, callback: SendCallback? = null)
}
```

---

## 4. 内置 UI 设计规范 (Compose Material 2)

### 4.1 风格与规范 (M2 风格)
* **设计风格**：采用 `androidx.compose.material` **Material 2 (M2)** 标准风格。
* **禁用 M3 特性**：禁用 M3 的超大圆角 (Pill shape) 以及淡粉/紫粉 tonal 调色盘。
* **主题色自动同步**：从宿主 Context 提取 `R.attr.colorPrimary` 填充至 Compose `Colors.primary`，实现 UI 颜色自动契合宿主 App。

### 4.2 布局与交互 (`DeviceManagerScreen`)
1. **TopAppBar**：标题为“已关联 PC 设备”，包含返回导航图标。
2. **列表区 (LazyColumn)**：
   * 列表项仅显示 PC **设备名称 (`device.name`)**，字体采用 M2 Subtitle1 / Body1。
   * 列表项不包含 MAC 地址或 UUID 的显示。
   * 右侧配有 M2 **OutlinedButton** （文本：“解除绑定”）。
   * 点击“解除绑定”展示 M2 **AlertDialog** 弹窗二次确认，确认后调用 `unpair(device.uuid)`。
3. **空状态 (Empty State)**：
   * 列表为空时，展示图文提示：“暂无绑定的 PC 设备”。
4. **固定底部栏 (Bottom Footer Bar)**：
   * 上方：辅助说明文案（如：“扫描 PC 端控制台显示的 BLE 配对二维码进行绑定”）。
   * 下方：宽尺寸主按钮（文本：“+ 扫描二维码绑定新设备”）。
5. **扫码交互与重复绑定**：
   * 点击底部“绑定新设备”按钮，拉起 `QrScannerFragment`。
   * **重复绑定判定**：若扫描到的 PC 的 `uuid` 已经存在于绑定列表中，提示 Toast **“该设备已绑定”** 并关闭扫码，不覆盖旧数据。

---

## 5. 通知发送与蓝牙匹配逻辑 (Notification Dispatch)

当调用 `sendNotification(title, body, callback)` 时：
1. 从 `PairingManager` 读取所有已绑定设备的 `uuid` 及对应 `baseKey`。
2. 若已绑定列表为空，回调 `SdkError.NotPaired()`。
3. 启动 BLE 扫描，搜寻广播内容中匹配已绑定 `uuid` 列表的第一台 PC 设备。
4. 扫描到目标设备后，立即建立 GATT 连接并发送加密帧，发送完成后断开连接，成功回调。

---

## 6. 权限与生命周期

1. **权限管理**：宿主 App 须在主 Activity 的 `onResume()` 中统一调用 `BleNotificationSDK.ensurePermissions(activity)`。
2. **错误响应**：扫码取消或解析错误回调 `SdkError` 并提示，管理界面实时响应 `pairedDevicesState`。
