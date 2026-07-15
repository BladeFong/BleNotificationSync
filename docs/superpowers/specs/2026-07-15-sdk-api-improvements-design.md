# BleNotificationSync Android SDK API 改进设计

## 背景

SDK 初版实现了基础功能链路（加密/协议/BLE/扫码/闹钟），但有 5 个 API 层面的设计缺陷需要修复。

---

## 1. 配对持久化

### 问题

`PairingManager.savePairing()` 只写内存 `mutableMapOf`，App 重启后 `isPaired()` 全部返回 false。

### 方案

SharedPreferences，key=`pairing_$packageName`，value=`"$mac|$appName"`。

```
PairingManager(Context)
  ├── savePairing(packageName, mac, appName)   → prefs.putString("pairing_$pkg", "$mac|$appName")
  ├── isPaired(packageName): Boolean           → prefs.contains(key)
  ├── getPairedMac(packageName): String?       → value.split("|")[0]
  ├── getPairedAppName(packageName): String?   → value.split("|")[1]
  ├── getPairedDevices(): List<PairedDevice>   → prefs.all.filterKeys { it startsWith "pairing_" }
  └── unpair(packageName)                       → prefs.remove(key)
```

新增数据类：

```kotlin
data class PairedDevice(
    val packageName: String,
    val mac: String,
    val appName: String
)
```

### 注意

密钥生成机制：

```
配对时 Android 生成 32 字节随机数
    ↓ REGISTER 帧携带 {random} 发给桌面
双方各自: baseKey = HKDF(salt="BleNotificationSync", IKM=packageName+random, info="", L=32)
    ↓ 各自持久化 baseKey + random
每次消息: nonce(12B 随机) + ciphertext = AES-256-GCM(baseKey, nonce, plaintext)
```

`PairingManager` 持久化（SharedPreferences）：

```
key:   "pairing_$packageName"
value: "$mac|$appName|$baseKey"     // baseKey: 32B HKDF 派生结果
                                    // random: 不存（配后丢弃）
```

此设计确保：
- 不同包名/不同配对随机数 → 不同 baseKey（防止反编译包名推算密钥）
- 每次消息独立 nonce → 同一 baseKey 下不同密文（防重放）

---

## 2. 权限内聚

### 问题

`@Suppress("MissingPermission")` 把 BLE 权限检查甩给调用方，不同 Android 版本需要不同权限组合。

### 方案：分层处理

| API | Activity 引用 | 策略 |
|-----|--------------|------|
| `startPairing` | FragmentActivity | 缺权限→自动 `requestPermission` |
| `sendNotification` | 无 | 缺权限→回调 `SdkError.PERMISSION_DENIED` |
| `setReminder` | 无 | 不涉及 BLE，仅 AlarmManager |

### Android 版本兼容

| API 级别 | 所需权限 |
|----------|---------|
| 23-30 | `BLUETOOTH` + `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` |
| 31+ | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` |

### 实现

`BleClient` 增加两个公开静态方法：

```kotlin
object BleClient {
    /** 检查当前 Android 版本所需的全部 BLE 权限是否已授予 */
    fun hasPermissions(context: Context): Boolean

    /** 返回当前 Android 版本所需但尚未授予的权限列表 */
    fun getMissingPermissions(context: Context): Array<String>
}
```

`startPairing` 流程：

```
1. 检查相机权限 → 缺失则 requestPermission(CAMERA)
2. 扫码成功
3. 检查 BLE 权限 → 缺失则 requestPermission(permissions)
4. 连接 GATT → 发送 REGISTER
```

`sendNotification` 流程：

```
1. 检查 BLE 权限 → 缺失立即回调 SdkError.PermissionDenied(list)
2. 检查蓝牙状态 → 未开启回调 SdkError.BluetoothDisabled
3. 连接 → 发送 NOTIFY
```

`AndroidManifest.xml` 中已声明全部权限（BLUETOOTH/BLUETOOTH_ADMIN/BLUETOOTH_SCAN/BLUETOOTH_CONNECT/ACCESS_FINE_LOCATION），合并到宿主 App。

---

## 3. 连接复用（本期不做）

### 方案说明（留待后续）

配对成功后保持 GATT 连接 30s，后续 `sendNotification` 直接复用，省一次连接开销（~300ms-1s）。`close()` 强制断开。

### 推迟理由

- MVP 阶段每次都走"连接→发帧→断开"已可用
- 连接复用增加状态机复杂度（连接超时/断开重连/异常恢复）
- 等项目有真机联调后再评估是否必要

---

## 4. 结构化错误码

### 问题

所有回调 `onError(error: String)`，调用方无法按类型分支。

### 方案

定义 `SdkError` 密封类，String 降级为附加描述：

```kotlin
sealed class SdkError(val message: String) {
    class BluetoothDisabled   : SdkError("蓝牙未开启")
    class BluetoothUnavailable : SdkError("设备不支持蓝牙")
    class PermissionDenied(permissions: List<String>) : SdkError("缺少权限: $permissions")
    class NotPaired           : SdkError("未配对的设备")
    class DeviceNotFound(mac: String) : SdkError("未找到设备: $mac")
    class ServiceNotFound     : SdkError("GATT 服务未找到")
    class ConnectionFailed(cause: String) : SdkError("连接失败: $cause")
    class WriteFailed(cause: String) : SdkError("写入失败: $cause")
    class EncryptionFailed    : SdkError("加密失败")
    class Timeout             : SdkError("操作超时")
    class Closed              : SdkError("SDK 已关闭")
    class Unknown(cause: String) : SdkError(cause)
}
```

### 现有回调接口迁移

| 接口 | 当前签名 | 新签名 |
|------|---------|--------|
| `PairingCallback` | `onError(error: String)` | `onError(error: SdkError)` |
| `SendCallback` | `onError(error: String)` | `onError(error: SdkError)` |
| `ConnectionCallback` | `onError(error: String)` | `onError(error: SdkError)` |
| `WriteCallback` | `onWriteError(error: String)` | `onWriteError(error: SdkError)` |
| `ReminderCallback` | 无 error | 不变（闹钟用 `onSynced(success)` 表达结果） |

### 调用方用法

```kotlin
sdk.sendNotification("title", "body", object : SendCallback {
    override fun onSuccess() { /* ok */ }
    override fun onError(error: SdkError) {
        when (error) {
            is SdkError.BluetoothDisabled -> promptEnableBluetooth()
            is SdkError.NotPaired -> startPairing()
            is SdkError.Timeout -> retry()
            else -> showToast(error.message)
        }
    }
})
```

---

## 5. 生命周期管理

### 问题

SDK 持有 BLE GATT 连接但没有 `close()`/`release()`。Activity 退出时连接可能泄露。

### 方案

```kotlin
class BleNotificationSDK {
    /**
     * 关闭 SDK，断开所有活跃连接，取消所有等待中的回调。
     * 关闭后调用其他方法返回 [SdkError.Closed]。
     * 调用方可在适当时生命周期节点调用（如 Activity.onDestroy）。
     */
    fun close()
}
```

`close()` 内部：
1. 断开当前活跃的 `BluetoothGatt`（`disconnect()` + `close()`）
2. 取消所有延迟任务（ACK 超时 timer）
3. 取消所有进行的扫码（`QrScanner.stop()`）
4. 标记 `closed = true`，后续 API 调用立即返回 `SdkError.Closed`
5. 不清除 `instance`（再次 `init()` 会重置状态新建实例）

---

## 接口变更汇总

### BleNotificationSDK

| 方法 | 变更 |
|------|------|
| `init(context)` | 不变 |
| `getInstance()` | 不变 |
| `startPairing(activity, appName, callback)` | `callback.onError(String→SdkError)` |
| `sendNotification(title, body, callback)` | `callback` 参数 `onError(String→SdkError)` |
| `isPaired(packageName)` | 不变 |
| `getPairedDevices()` | 新增 |
| `unpair(packageName)` | 新增 |
| `setReminder(...)` | 不变 |
| `cancelReminder(taskId)` | 不变 |
| `close()` | 新增 |

### PairingManager

| 变更 | 说明 |
|------|------|
| 构造参数 | 新增 `Context`（用于 SharedPreferences） |
| `savePairing/isPaired/getPairedMac` | 改为读写 SharedPreferences |
| `getPairedDevices/unpair` | 新增 |

### BleClient

| 变更 | 说明 |
|------|------|
| `hasPermissions(context): Boolean` | 新增静态方法 |
| `getMissingPermissions(context): Array<String>` | 新增静态方法 |
| 移除 | 所有 `@Suppress("MissingPermission")` |
| `connect()` 内部 | 连接前检查权限，失败走 `SdkError.PermissionDenied` |

### 回调接口

| 接口 | 变更 |
|------|------|
| `PairingCallback.onError` | `String → SdkError` |
| `SendCallback.onError` | `String → SdkError` |
| `ConnectionCallback.onError` | `String → SdkError` |
| `WriteCallback.onWriteError` | `String → SdkError` |

---

## 文件变更范围

| 文件 | 变更类型 |
|------|----------|
| `sdk/BleNotificationSDK.kt` | 新增 `getPairedDevices/unpair/close`，回调签名迁移 |
| `pairing/PairingManager.kt` | 构造加 Context，SharedPreferences 持久化，新增 `getPairedDevices/unpair` |
| `ble/BleClient.kt` | 新增 `hasPermissions/getMissingPermissions`，移除 `@Suppress`，权限内聚 |
| `ble/Callbacks.kt` 或各回调接口文件 | 全部 `String→SdkError` |
| 新建 `sdk/SdkError.kt` | 密封类定义 |
| 测试文件 | 对应更新 |
