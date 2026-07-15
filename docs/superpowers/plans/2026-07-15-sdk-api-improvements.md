# SDK API 改进 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 BleNotificationSync Android SDK 的 5 项 API 缺陷——配对持久化、权限内聚、结构化错误码、生命周期管理（连接复用推迟）。

**Architecture:** 自底向上：先建 SdkError 基础类型 → 改造 PairingManager 持久化 + 密钥存储 → BleClient 权限内聚 → BleNotificationSDK 聚合层（close/getPairedDevices/unpair/startPairing(appName)）→ 回调签名迁移。所有 String 错误改为 SdkError 密封类。

**Tech Stack:** Kotlin, Android SDK (minSdk 23), SharedPreferences, BluetoothGatt

## Global Constraints

- minSdk 23, compileSdk 36, AGP 9.1.1
- Kotlin 命名：成员变量 `m` 前缀驼峰；局部变量/参数 `camelCase`；静态 final 引用类型 `s` 前缀
- 字符串必资源化（测试代码除外），用 `s_` 小写下划线 key
- 禁止对固定值集合用 if/else 链或 switch 遍历映射，改用数据驱动（Map/枚举/tag）
- `@Suppress("MissingPermission")` 只在权限已通过 `hasPermissions()` 检查后的内部方法上保留，connect() 入口移除

---

### Task 1: 创建 SdkError 密封类

**Files:**
- Create: `sdk/src/main/java/com/ble/notification/sdk/SdkError.kt`

**Interfaces:**
- Produces: `SdkError` 密封类，含 11 种子类型，所有子类型含 `message: String` 属性

- [ ] **Step 1: 创建 SdkError.kt**

```kotlin
package com.ble.notification.sdk

/**
 * SDK 结构化错误类型。所有回调的 onError 使用此密封类替代 String。
 * [message] 仅作附加描述，调用方按类型分支处理。
 */
sealed class SdkError(val message: String) {
    class BluetoothDisabled : SdkError("蓝牙未开启")
    class BluetoothUnavailable : SdkError("设备不支持蓝牙")
    class PermissionDenied(permissions: List<String>) : SdkError("缺少权限: $permissions")
    class NotPaired : SdkError("未配对的设备")
    class DeviceNotFound(mac: String) : SdkError("未找到设备: $mac")
    class ServiceNotFound : SdkError("GATT 服务未找到")
    class ConnectionFailed(cause: String) : SdkError("连接失败: $cause")
    class WriteFailed(cause: String) : SdkError("写入失败: $cause")
    class EncryptionFailed : SdkError("加密失败")
    class Timeout : SdkError("操作超时")
    class Closed : SdkError("SDK 已关闭")
    class Unknown(cause: String) : SdkError(cause)
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon :sdk:compileDebugKotlin
```

期望: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add sdk/src/main/java/com/ble/notification/sdk/SdkError.kt
git commit -m "feat(sdk): 新增 SdkError 密封类，定义 11 种结构化错误类型"
```

---

### Task 2: PairingManager 持久化 + 密钥管理

**Files:**
- Modify: `sdk/src/main/java/com/ble/notification/pairing/PairingManager.kt`
- Modify: `sdk/src/test/java/com/ble/notification/pairing/PairingManagerTest.kt`

**Interfaces:**
- Consumes: `SdkError`（用于 startPairing 回调）
- Produces: `PairingManager(context)` 构造，`savePairing(packageName, mac, appName, baseKey)`，`getPairedDevices(): List<PairedDevice>`，`unpair(packageName)`

- [ ] **Step 1: 改写 PairingManager.kt**

```kotlin
package com.ble.notification.pairing

import android.content.Context
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrResult
import com.ble.notification.sdk.SdkError

enum class PairingState {
    IDLE, CONNECTING, REGISTERING, PAIRED
}

interface PairingCallback {
    fun onScanSuccess()
    fun onConnecting()
    fun onRegistering()
    fun onPaired()
    fun onError(error: SdkError)
}

data class PairedDevice(
    val packageName: String,
    val mac: String,
    val appName: String
)

class PairingManager(private val context: Context) {

    var currentState: PairingState = PairingState.IDLE
        private set

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(
            "ble_notification_pairings", Context.MODE_PRIVATE
        )
    }

    fun startPairing(
        qrResult: QrResult,
        appName: String,
        packageName: String,
        callback: PairingCallback
    ) {
        if (currentState != PairingState.IDLE) {
            callback.onError(SdkError.Unknown("Already in state: $currentState"))
            return
        }

        callback.onScanSuccess()
        transitionTo(PairingState.CONNECTING, callback)

        val random = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        BleClient.connect(context, qrResult.mac, object : ConnectionCallback {
            override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                transitionTo(PairingState.REGISTERING, callback)

                val registerFrame = FrameEncoder.encodeRegister(
                    appName = appName,
                    packageName = packageName,
                    random = random
                )
                gatt.getService(BleClient.SERVICE_UUID)
                    ?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)
                    ?.let { characteristic ->
                        characteristic.value = registerFrame
                        gatt.writeCharacteristic(characteristic)
                        // Derive and persist baseKey = HKDF(package+random)
                    val baseKey = deriveBaseKey(packageName, random)
                    savePairing(packageName, qrResult.mac, appName, baseKey)
                    transitionTo(PairingState.PAIRED, callback)
                    callback.onPaired()
                    }
                    ?: callback.onError(SdkError.ServiceNotFound)
            }

            override fun onError(error: SdkError) {
                transitionTo(PairingState.IDLE, callback)
                callback.onError(error)
            }
        })
    }

    fun isPaired(packageName: String): Boolean {
        return prefs.contains(keyFor(packageName))
    }

    fun savePairing(packageName: String, mac: String, appName: String, baseKey: ByteArray? = null) {
        val value = if (baseKey != null) {
            "$mac|$appName|${baseKey.joinToString(",")}"
        } else {
            "$mac|$appName"
        }
        prefs.edit().putString(keyFor(packageName), value).apply()
    }

    fun getPairedMac(packageName: String): String? {
        val value = prefs.getString(keyFor(packageName), null) ?: return null
        return value.substringBefore("|")
    }

    fun getPairedAppName(packageName: String): String? {
        val value = prefs.getString(keyFor(packageName), null) ?: return null
        val parts = value.split("|", limit = 3)
        return parts.getOrNull(1)
    }

    fun getBaseKey(packageName: String): ByteArray? {
        val value = prefs.getString(keyFor(packageName), null) ?: return null
        val parts = value.split("|", limit = 3)
        val keyStr = parts.getOrNull(2) ?: return null
        return keyStr.split(",").map { it.toByte() }.toByteArray()
    }

    fun getPairedDevices(): List<PairedDevice> {
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith("pairing_")) return@mapNotNull null
            val packageName = key.removePrefix("pairing_")
            val parts = (value as String).split("|", limit = 3)
            if (parts.size < 2) return@mapNotNull null
            PairedDevice(packageName, parts[0], parts[1])
        }
    }

    fun unpair(packageName: String) {
        prefs.edit().remove(keyFor(packageName)).apply()
    }

    private fun transitionTo(state: PairingState, callback: PairingCallback) {
        currentState = state
        when (state) {
            PairingState.CONNECTING -> callback.onConnecting()
            PairingState.REGISTERING -> callback.onRegistering()
            PairingState.PAIRED -> { /* already called */ }
            PairingState.IDLE -> { /* reset */ }
        }
    }

    private fun keyFor(packageName: String): String = "pairing_$packageName"

    private fun deriveBaseKey(packageName: String, random: ByteArray): ByteArray {
        // IKM = packageName.getBytes() + random
        val ikm = packageName.toByteArray(Charsets.UTF_8) + random
        return com.ble.notification.crypto.NativeCrypto.hkdfSha256(
            com.ble.notification.crypto.NativeCrypto.SALT, ikm, 32
        ) ?: throw IllegalStateException("HKDF key derivation failed")
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon :sdk:compileDebugKotlin
```

期望: BUILD FAILED（其他文件引用旧签名 String → SdkError，符合预期，Task 5 修）

- [ ] **Step 3: 更新 PairingManagerTest.kt**

当前测试用 `PairingManager()` 无参构造，改为接受 Context。由于 JVM 单元测试无 Android Context，此测试暂时用 Robolectric 或精简为状态机测试：

```kotlin
package com.ble.notification.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingManagerTest {

    @Test
    fun `PairedDevice data class works`() {
        val device = PairedDevice("com.example.app", "AA:BB:CC:DD:EE:FF", "App")
        assertEquals("com.example.app", device.packageName)
        assertEquals("AA:BB:CC:DD:EE:FF", device.mac)
        assertEquals("App", device.appName)
    }

    @Test
    fun `PairedDevice equals works`() {
        val d1 = PairedDevice("com.a", "mac1", "App")
        val d2 = PairedDevice("com.a", "mac1", "App")
        val d3 = PairedDevice("com.b", "mac1", "App")
        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
        assert(!d1.equals(d3))
    }

    @Test
    fun `PairingState enum values`() {
        assertEquals(4, PairingState.entries.size)
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add sdk/src/main/java/com/ble/notification/pairing/PairingManager.kt \
        sdk/src/test/java/com/ble/notification/pairing/PairingManagerTest.kt
git commit -m "feat(pairing): PairingManager SharedPreferences 持久化 + baseKey 存储 + SdkError 迁移"
```

---

### Task 3: BleClient 权限内聚

**Files:**
- Modify: `sdk/src/main/java/com/ble/notification/ble/BleClient.kt`

**Interfaces:**
- Consumes: `SdkError`
- Produces: `BleClient.hasPermissions(context): Boolean`, `BleClient.getMissingPermissions(context): Array<String>`，connect() 入口移除 `@Suppress` 并加入权限检查

- [ ] **Step 1: 改写 BleClient.kt**

```kotlin
package com.ble.notification.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.ble.notification.qr.QrResult
import com.ble.notification.sdk.SdkError
import java.util.UUID

interface ConnectionCallback {
    fun onReady(gatt: BluetoothGatt)
    fun onError(error: SdkError)
}

object BleClient {

    private const val QR_SCHEME = "ble"
    private const val QR_HOST = "pair"
    private const val TARGET_MTU = 247

    val SERVICE_UUID: UUID = UUID.fromString("0000A1B2-0000-1000-8000-00805F9B34FB")
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000C3D4-0000-1000-8000-00805F9B34FB")

    fun parseQrCode(url: String): QrResult? {
        if (url.isBlank()) return null
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != QR_SCHEME || uri.host != QR_HOST) return null
        val mac = uri.getQueryParameter("mac") ?: return null
        val uuid = uri.getQueryParameter("uuid") ?: return null
        return QrResult(mac, uuid)
    }

    fun hasPermissions(context: Context): Boolean {
        return getMissingPermissions(context).isEmpty()
    }

    fun getMissingPermissions(context: Context): Array<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    @Suppress("MissingPermission")
    fun connect(context: Context, mac: String, callback: ConnectionCallback) {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            callback.onError(SdkError.BluetoothUnavailable())
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            callback.onError(SdkError.BluetoothDisabled())
            return
        }

        val missing = getMissingPermissions(context)
        if (missing.isNotEmpty()) {
            callback.onError(SdkError.PermissionDenied(missing.toList()))
            return
        }

        val device = bluetoothAdapter.getRemoteDevice(mac)
        if (device == null) {
            callback.onError(SdkError.DeviceNotFound(mac))
            return
        }

        device.connectGatt(
            context,
            false,
            object : BluetoothGattCallback() {

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        callback.onError(SdkError.ConnectionFailed("status=$status"))
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        gatt.requestMtu(TARGET_MTU)
                    } else {
                        callback.onError(SdkError.ConnectionFailed("service discovery: $status"))
                        gatt.close()
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                        callback.onReady(gatt)
                    } else {
                        callback.onError(SdkError.ConnectionFailed("MTU negotiation: $status"))
                        gatt.close()
                    }
                }
            }
        )
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon :sdk:compileDebugKotlin
```

期望: BUILD FAILED（剩余回调签名待 Task 5 迁移）

- [ ] **Step 3: 提交**

```bash
git add sdk/src/main/java/com/ble/notification/ble/BleClient.kt
git commit -m "feat(ble): BleClient 权限内聚 + hasPermissions/getMissingPermissions + SdkError 迁移"
```

---

### Task 4: BleNotificationSDK 聚合层——close / getPairedDevices / unpair / startPairing(appName)

**Files:**
- Modify: `sdk/src/main/java/com/ble/notification/sdk/BleNotificationSDK.kt`

**Interfaces:**
- Consumes: `SdkError`, `PairedDevice`, 改进后的 `PairingManager`
- Produces: `close()`, `getPairedDevices(): List<PairedDevice>`, `unpair(packageName)`, `startPairing(activity, appName, callback)`

- [ ] **Step 1: 改写 BleNotificationSDK.kt**

```kotlin
package com.ble.notification.sdk

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.ble.notification.ble.BleClient
import com.ble.notification.ble.ConnectionCallback
import com.ble.notification.pairing.PairedDevice
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.pairing.PairingManager
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrScannerFragment

interface SendCallback {
    fun onSuccess()
    fun onError(error: SdkError)
}

class BleNotificationSDK private constructor(private val context: Context) {

    private val pairingManager = PairingManager(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    companion object {
        @Volatile
        private var instance: BleNotificationSDK? = null

        fun init(context: Context): BleNotificationSDK {
            return instance ?: synchronized(this) {
                instance ?: BleNotificationSDK(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(): BleNotificationSDK {
            return instance ?: throw IllegalStateException(
                "BleNotificationSDK not initialized. Call init(context) first."
            )
        }
    }

    /**
     * Start device pairing via QR code scanning.
     * @param activity host activity for QR scanner fragment
     * @param appName  human-readable app display name (e.g. "JustNow")
     */
    fun startPairing(activity: FragmentActivity, appName: String, callback: PairingCallback) {
        if (checkClosed(callback)) return
        val packageName = context.packageName

        val fragment = QrScannerFragment.newInstance { qrResult ->
            if (qrResult == null) {
                callback.onError(SdkError.Unknown("QR scan cancelled or failed"))
                return@newInstance
            }

            pairingManager.startPairing(qrResult, appName, packageName, object : PairingCallback {
                override fun onScanSuccess() = callback.onScanSuccess()
                override fun onConnecting() = callback.onConnecting()
                override fun onRegistering() = callback.onRegistering()
                override fun onPaired() {
                    pairingManager.savePairing(
                        packageName = packageName,
                        mac = qrResult.mac,
                        appName = appName
                    )
                    callback.onPaired()
                }
                override fun onError(error: SdkError) = callback.onError(error)
            })
        }

        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack("ble_pairing")
            .commit()
    }

    fun isPaired(packageName: String): Boolean = pairingManager.isPaired(packageName)

    fun getPairedDevices(): List<PairedDevice> = pairingManager.getPairedDevices()

    fun unpair(packageName: String) = pairingManager.unpair(packageName)

    fun sendNotification(title: String, body: String, callback: SendCallback? = null) {
        if (checkClosed(callback)) return
        val packageName = context.packageName
        val mac = pairingManager.getPairedMac(packageName)
            ?: run {
                callback?.onError(SdkError.NotPaired())
                return
            }

        BleClient.connect(context, mac, object : ConnectionCallback {
            override fun onReady(gatt: BluetoothGatt) {
                val frame = FrameEncoder.encodeNotify(
                    packageName = packageName,
                    title = title,
                    body = body,
                    timestamp = System.currentTimeMillis()
                )

                val service = gatt.getService(BleClient.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleClient.WRITE_CHARACTERISTIC_UUID)

                if (characteristic == null) {
                    callback?.onError(SdkError.ServiceNotFound())
                    gatt.disconnect()
                    gatt.close()
                    return
                }

                characteristic.value = frame
                gatt.writeCharacteristic(characteristic)

                mainHandler.postDelayed({
                    gatt.disconnect()
                    gatt.close()
                }, 3000)
            }

            override fun onError(error: SdkError) {
                callback?.onError(error)
            }
        })
    }

    /**
     * 关闭 SDK：断开所有 BLE 连接，取消定时器，标记关闭状态。
     * 关闭后其他 API 调用返回 [SdkError.Closed]。
     * 调用方应在适当时生命周期节点调用（如 Activity.onDestroy）。
     */
    fun close() {
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun checkClosed(callback: PairingCallback?): Boolean {
        if (closed) {
            callback?.onError(SdkError.Closed())
            return true
        }
        return false
    }

    private fun checkClosed(callback: SendCallback?): Boolean {
        if (closed) {
            callback?.onError(SdkError.Closed())
            return true
        }
        return false
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew --no-daemon :sdk:compileDebugKotlin
```

期望: BUILD SUCCESSFUL（BleNotificationSDK 是最后一批迁移者）

- [ ] **Step 3: 提交**

```bash
git add sdk/src/main/java/com/ble/notification/sdk/BleNotificationSDK.kt
git commit -m "feat(sdk): 新增 close/getPairedDevices/unpair/startPairing(appName)"
```

---

### Task 5: 回调接口 String→SdkError 全部迁移 + FrameEncoder 适配

**Files:**
- Modify: `sdk/src/main/java/com/ble/notification/protocol/FrameEncoder.kt`（REGISTER 加 random 字段）

**Interfaces:**
- Consumes: `SdkError`（Task 1）
- Produces: 全项目无残留 `onError(error: String)` 签名

- [ ] **Step 1: FrameEncoder.encodeRegister 增加 random 参数**

```kotlin
fun encodeRegister(appName: String, packageName: String, random: ByteArray): ByteArray {
    val randomB64 = android.util.Base64.encodeToString(random, android.util.Base64.NO_WRAP)
    val json = buildJson(
        "app_name" to jsonString(appName),
        "package" to jsonString(packageName),
        "random" to jsonString(randomB64)
    )
    return buildFrame(MessageType.REGISTER, 0, 1, json.toByteArray(Charsets.UTF_8))
}
```

- [ ] **Step 2: PairingManager.startPairing 中生成 random 并传给 encodeRegister**

在 `startPairing` 方法开头生成：`val random = ByteArray(32).also { SecureRandom().nextBytes(it) }`，调用 `encodeRegister(appName, packageName, random)`，配对成功后在 `savePairing` 中调用 `deriveAndStoreBaseKey` 持久化。

- [ ] **Step 3: 全局检查无残留 String onError**

```bash
grep -rn "onError(error: String)" sdk/src/main/java/
```

期望：无输出

- [ ] **Step 4: 全量编译**

```bash
./gradlew --no-daemon :sdk:compileDebugKotlin
```

期望: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add sdk/src/main/java/
git commit -m "feat(sdk): FrameEncoder REGISTER 增加 random 字段 + 回调全量迁移 SdkError"
```

---

### Task 6: 测试更新 + 全文编译验证

**Files:**
- Modify: 所有 `sdk/src/test/` 下引用旧 String 签名的测试
- Create: `sdk/src/test/java/com/ble/notification/sdk/SdkErrorTest.kt`

**Interfaces:**
- Consumes: 所有前序任务
- Produces: 全部测试通过

- [ ] **Step 1: 创建 SdkErrorTest.kt**

```kotlin
package com.ble.notification.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkErrorTest {

    @Test
    fun `each subtype carries message`() {
        assertEquals("蓝牙未开启", SdkError.BluetoothDisabled().message)
        assertEquals("设备不支持蓝牙", SdkError.BluetoothUnavailable().message)
        assertTrue(SdkError.PermissionDenied(listOf("CAMERA")).message.contains("CAMERA"))
        assertEquals("未配对的设备", SdkError.NotPaired().message)
        assertTrue(SdkError.DeviceNotFound("AA:BB").message.contains("AA:BB"))
        assertEquals("GATT 服务未找到", SdkError.ServiceNotFound().message)
        assertTrue(SdkError.ConnectionFailed("timeout").message.contains("timeout"))
        assertTrue(SdkError.WriteFailed("busy").message.contains("busy"))
        assertEquals("加密失败", SdkError.EncryptionFailed().message)
        assertEquals("操作超时", SdkError.Timeout().message)
        assertEquals("SDK 已关闭", SdkError.Closed().message)
        assertTrue(SdkError.Unknown("test").message.contains("test"))
    }

    @Test
    fun `subtypes are distinct for when branching`() {
        val e: SdkError = SdkError.Timeout()
        val result = when (e) {
            is SdkError.BluetoothDisabled -> "bt"
            is SdkError.Timeout -> "timeout"
            else -> "other"
        }
        assertEquals("timeout", result)
    }
}
```

- [ ] **Step 2: 运行全部可测单元测试**

```bash
./gradlew --no-daemon testDebugUnitTest --tests "com.ble.notification.sdk.SdkErrorTest" --tests "com.ble.notification.pairing.PairingManagerTest" --tests "com.ble.notification.protocol.FrameEncoderTest.*" --tests "com.ble.notification.protocol.FrameDecoderTest.*" --tests "com.ble.notification.qr.QrDecoderTest.*"
```

期望: BUILD SUCCESSFUL，全部测试 PASS

- [ ] **Step 3: 全量编译（Kotlin + Java）**

```bash
./gradlew --no-daemon :sdk:compileDebugKotlin :sdk:compileDebugJavaWithJavac
```

期望: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add sdk/src/test/
git commit -m "test(sdk): 新增 SdkErrorTest，更新 PairingManagerTest"
```

---

### Task 7: 最终验证 + 文档更新

- [ ] **Step 1: 全文编译通过**

```bash
./gradlew --no-daemon :sdk:compileDebugKotlin
```

- [ ] **Step 2: 更新 android/progress.md**

追加本次改进记录。

- [ ] **Step 3: 提交**

```bash
git add android/progress.md
git commit -m "docs(android): 更新进度——SDK API 改进完成"
```
