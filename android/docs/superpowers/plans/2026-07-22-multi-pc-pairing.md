# 多 PC 绑定与 DeviceManager UI 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Android SDK 增加多 PC 绑定支持（以 PC 的固定 UUID 为持久化 Key），并内置基于 Compose Material 2 的设备管理组件 (`DeviceManagerFragment` / `DeviceManagerActivity`)。

**Architecture:** 重构 `PairingManager` 的持久化与响应式 `StateFlow` 数据层；更新 `BleNotificationSDK` 的对外 API 及蓝牙广播匹配通知发送逻辑；使用 Jetpack Compose (Material 2) 构建只显示设备名的解绑/绑定界面并自动适配宿主 App 主题色。

**Tech Stack:** Kotlin, EncryptedSharedPreferences, Kotlin Coroutines StateFlow, Jetpack Compose Material 2, AndroidX Fragment, CameraX (QrScannerFragment).

## Global Constraints

- **设备唯一主键**：必须使用 PC 的固定 `uuid` (格式：`pairing_$uuid`)，严禁使用变动 BLE MAC 地址。
- **UI 风格**：必须使用 Material 2 (`androidx.compose.material`)，不得使用 M3 大圆角和 M3 粉调调色盘。
- **界面展示**：设备列表项中只能展示设备名称 `name`，禁止显示 MAC 地址。
- **无 Git 仓**：本工程当前无 `.git` 目录，不需要执行 git commit 指令。

---

### Task 1: 数据模型与 PairingManager 多设备响应式持久化重构

**Files:**
- Modify: `sdk/src/main/java/com/ble/notification/pairing/PairingManager.kt`
- Test: `sdk/src/test/java/com/ble/notification/pairing/PairingManagerTest.kt`

**Interfaces:**
- Consumes: `QrResult(mac, uuid, name)`
- Produces: `PairedDevice(uuid, name, appName, pairedAt)`, `PairingManager.pairedDevicesFlow: StateFlow<List<PairedDevice>>`

- [ ] **Step 1: 编写 PairingManager 多设备与 UUID Key 测试**

```kotlin
// sdk/src/test/java/com/ble/notification/pairing/PairingManagerTest.kt
package com.ble.notification.pairing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingManagerTest {

    private lateinit var pairingManager: PairingManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        pairingManager = PairingManager(context)
        pairingManager.unpairAll()
    }

    @Test
    fun testSaveAndGetMultipleDevicesByUuid() {
        val uuid1 = "pc-uuid-001"
        val uuid2 = "pc-uuid-002"

        pairingManager.savePairing(
            uuid = uuid1,
            mac = "AA:BB:CC:DD:EE:01",
            appName = "DemoApp",
            baseKey = byteArrayOf(1, 2, 3),
            deviceName = "Work-PC"
        )

        pairingManager.savePairing(
            uuid = uuid2,
            mac = "AA:BB:CC:DD:EE:02",
            appName = "DemoApp",
            baseKey = byteArrayOf(4, 5, 6),
            deviceName = "Home-PC"
        )

        val devices = pairingManager.getPairedDevices()
        assertEquals(2, devices.size)
        assertTrue(pairingManager.isPaired(uuid1))
        assertTrue(pairingManager.isPaired(uuid2))

        val dev1 = devices.find { it.uuid == uuid1 }
        assertNotNull(dev1)
        assertEquals("Work-PC", dev1?.name)

        pairingManager.unpair(uuid1)
        assertEquals(1, pairingManager.getPairedDevices().size)
        assertFalse(pairingManager.isPaired(uuid1))
    }
}
```

- [ ] **Step 2: 运行测试以验证失败**

运行: `./gradlew :sdk:testDebugUnitTest --tests "com.ble.notification.pairing.PairingManagerTest"`
Expected: FAIL (因为 `savePairing` 方法签名尚未接收 `uuid`，且 `unpairAll` 未实现)

- [ ] **Step 3: 重构 PairingManager 接口与 StateFlow 实现**

修改 `sdk/src/main/java/com/ble/notification/pairing/PairingManager.kt`：
1. `data class PairedDevice(val uuid: String, val name: String, val appName: String, val pairedAt: Long = System.currentTimeMillis())`
2. 内部增设 `_pairedDevicesFlow = MutableStateFlow<List<PairedDevice>>(emptyList())` 和 `val pairedDevicesFlow: StateFlow<List<PairedDevice>>`
3. 存储 Key 修改为 `pairing_$uuid`，value 格式为 `$uuid|$name|$appName|${baseKey.joinToString(",")}|$pairedAt`
4. 实现 `savePairing(uuid, mac, appName, baseKey, deviceName)`，`unpair(uuid)`，`unpairAll()`，`getPairedDevices()`
5. 增加旧存量数据 `pairing_$packageName` 自动迁移逻辑。

- [ ] **Step 4: 运行测试验证通过**

运行: `./gradlew :sdk:testDebugUnitTest --tests "com.ble.notification.pairing.PairingManagerTest"`
Expected: PASS

---

### Task 2: BleNotificationSDK 接口与通知广播逻辑重构

**Files:**
- Modify: `sdk/src/main/java/com/ble/notification/sdk/BleNotificationSDK.kt`
- Modify: `sdk/src/main/java/com/ble/notification/sdk/SdkError.kt`

**Interfaces:**
- Consumes: `PairingManager.pairedDevicesFlow`
- Produces: `BleNotificationSDK.pairedDevicesState`, `startPairing`, `sendNotification`

- [ ] **Step 1: 在 SdkError 中添加已绑定提示类型**

在 `sdk/src/main/java/com/ble/notification/sdk/SdkError.kt` 中添加 `AlreadyPaired`：
```kotlin
sealed class SdkError(val message: String) {
    ...
    class AlreadyPaired(msg: String = "该设备已绑定") : SdkError(msg)
}
```

- [ ] **Step 2: 重构 BleNotificationSDK 方法实现**

在 `BleNotificationSDK.kt` 中：
1. 暴露 `val pairedDevicesState: StateFlow<List<PairedDevice>> = pairingManager.pairedDevicesFlow`
2. `fun getPairedDevices(): List<PairedDevice> = pairingManager.getPairedDevices()`
3. `fun unpair(uuid: String) = pairingManager.unpair(uuid)`
4. `fun unpairAll() = pairingManager.unpairAll()`
5. 重构 `startPairing`：从 `qrResult` 中读取 `uuid` 和 `name`。若 `pairingManager.isPaired(qrResult.uuid)` 为 true，回调 `onError(SdkError.AlreadyPaired())` 并停止流程。
6. 重构 `sendNotification`：开启 BLE 扫描，搜寻第一个在场且处于 `getPairedDevices()` 中的设备并完成连接与推送。

- [ ] **Step 3: 运行 Unit Test 验证 BleNotificationSDK**

运行: `./gradlew :sdk:testDebugUnitTest`
Expected: BUILD SUCCESSFUL & ALL TESTS PASS

---

### Task 3: Compose Material 2 设备管理界面与入口实现

**Files:**
- Create: `sdk/src/main/java/com/ble/notification/ui/DeviceManagerFragment.kt`
- Create: `sdk/src/main/java/com/ble/notification/ui/DeviceManagerActivity.kt`
- Modify: `sdk/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `BleNotificationSDK.pairedDevicesState`, `BleNotificationSDK.unpair`, `BleNotificationSDK.startPairing`
- Produces: `BleNotificationSDK.getDeviceManagerFragment()`, `BleNotificationSDK.openDeviceManager()`

- [ ] **Step 1: 创建 DeviceManagerFragment (Compose M2)**

编写 `sdk/src/main/java/com/ble/notification/ui/DeviceManagerFragment.kt`：
- 使用 `ComposeView` 渲染页面。
- 提取 Context 主题色：
  ```kotlin
  val typedValue = TypedValue()
  context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
  val primaryColor = Color(typedValue.data)
  ```
- 构建 M2 `MaterialTheme(colors = lightColors(primary = primaryColor))`
- 页面构成：
  - `TopAppBar` (标题：“已关联 PC 设备”)
  - `LazyColumn`：卡片项显示设备名 `device.name` 与“解除绑定” `OutlinedButton`。点击解绑弹框确认。
  - `BottomFooterBar`：包含说明文本 + “+ 扫描二维码绑定新设备”主按钮。
  - 点击主按钮触发 `BleNotificationSDK.getInstance().startPairing`，并在内部拉起 `QrScannerFragment`。已绑定时 Toast 提示“该设备已绑定”。

- [ ] **Step 2: 创建 DeviceManagerActivity 并注册 AndroidManifest**

编写 `sdk/src/main/java/com/ble/notification/ui/DeviceManagerActivity.kt`：
- 继承 `AppCompatActivity` / `FragmentActivity`。
- 在 `onCreate` 中直接嵌入 `DeviceManagerFragment`。
在 `sdk/src/main/AndroidManifest.xml` 中添加：
```xml
<activity
    android:name="com.ble.notification.ui.DeviceManagerActivity"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.Light.NoActionBar" />
```

- [ ] **Step 3: 在 BleNotificationSDK 暴露界面入口 API**

在 `BleNotificationSDK.kt` 中添加：
```kotlin
fun getDeviceManagerFragment(): Fragment = DeviceManagerFragment()

fun openDeviceManager(context: Context) {
    val intent = Intent(context, DeviceManagerActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
```

---

### Task 4: Demo 应用集成与功能集成验证

**Files:**
- Modify: `demo/src/main/java/com/ble/notification/demo/MainActivity.kt`
- Modify: `demo/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `BleNotificationSDK.openDeviceManager`, `BleNotificationSDK.pairedDevicesState`

- [ ] **Step 1: 在 Demo 中增加“设备管理”按钮**

修改 `demo/src/main/res/layout/activity_main.xml`，新增 `btn_device_manager` 按钮。
修改 `demo/src/main/java/com/ble/notification/demo/MainActivity.kt`：
```kotlin
findViewById<Button>(R.id.btn_device_manager).setOnClickListener {
    sdk.openDeviceManager(this)
}
```

- [ ] **Step 2: 编译整工程并运行验证**

运行: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL
