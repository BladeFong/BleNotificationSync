# BLE Notification Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建一个跨平台 BLE 闹钟通知同步开源项目，包含协议规范、Android SDK、Windows 端和 macOS 端。

**Architecture:** Android 端作为 GATT Client 闹钟触发时推送通知，PC/Mac 端作为 GATT Server 接收并弹出系统通知。通过二维码配对，每次通知独立连接用完即断。

**Tech Stack:** Kotlin (Android SDK), C# / .NET 8 (Windows), Swift (macOS), BLE GATT

## Global Constraints

- 协议 UUID: Service `0000A1B2-0000-1000-8000-00805F9B34FB`, Characteristic `0000C3D4-0000-1000-8000-00805F9B34FB`
- MTU 协商目标: 247 字节
- 数据帧格式: Magic(0xAA 0xBB) + MsgType(1B) + Seq(1B) + TotalSeq(1B) + Payload(0~240B)
- 仅支持 Mode B（闹钟推送），不支持 Mode A（通知镜像）
- 无保活 Service，每次通知独立连接
- 二维码配对，无需 OS 蓝牙配对

---

## Phase 1: 协议规范文档

### Task 1.1: 编写协议规范

**Files:**
- Create: `docs/protocol.md`

**Interfaces:**
- Consumes: 设计文档中的协议定义
- Produces: 完整的协议规范文档

- [ ] **Step 1: 创建协议文档框架**

```markdown
# BLE Notification Sync Protocol

## 1. Overview
## 2. GATT Service Definition
## 3. Data Frame Format
## 4. Message Types
## 5. Payload Formats
## 6. Pairing Flow
## 7. Error Handling
```

- [ ] **Step 2: 编写 GATT 服务定义**

```markdown
## 2. GATT Service Definition

| Item | Value |
|------|-------|
| Service UUID | `0000A1B2-0000-1000-8000-00805F9B34FB` |
| Write Characteristic | `0000C3D4-0000-1000-8000-00805F9B34FB` |
| Properties | WRITE_NO_RESPONSE |
```

- [ ] **Step 3: 编写数据帧格式**

```markdown
## 3. Data Frame Format

```
+--------+--------+--------+--------+-------------------+
| Magic  | MsgType| Seq    |TotalSeq| Payload           |
| 2B     | 1B     | 1B     | 1B     | 0~240B            |
| 0xAA 0xBB|      |        |        |                   |
+--------+--------+--------+--------+-------------------+
```

- Magic: Fixed header `0xAA 0xBB`
- MsgType: Message type identifier
- Seq: Current packet sequence (0-based)
- TotalSeq: Total number of packets
- Payload: JSON data bytes
```

- [ ] **Step 4: 编写消息类型定义**

```markdown
## 4. Message Types

| Value | Name | Direction | Description |
|-------|------|-----------|-------------|
| 0x01 | REGISTER | Android→PC | Send app info during pairing |
| 0x02 | NOTIFY | Android→PC | Push notification |
| 0x03 | ACK | PC→Android | Acknowledge receipt |
| 0x04 | ICON_DATA | Android→PC | Icon chunk data |
| 0x05 | ICON_END | Android→PC | Icon transfer complete |
```

- [ ] **Step 5: 编写 Payload 格式**

```markdown
## 5. Payload Formats

### REGISTER
```json
{
  "app_name": "string",
  "package": "string"
}
```

### NOTIFY
```json
{
  "title": "string",
  "body": "string",
  "package": "string",
  "timestamp": 1720000000000
}
```

### ACK
```json
{
  "code": 0,
  "msg": "ok"
}
```

### ICON_DATA
Base64 encoded icon chunk, max 240 bytes per chunk.

### ICON_END
```json
{
  "total_size": 12345
}
```
```

- [ ] **Step 6: 编写配对流程**

```markdown
## 6. Pairing Flow

### QR Code Content
```
ble://pair?mac=XX:XX:XX:XX:XX:XX&uuid=0000A1B2-0000-1000-8000-00805F9B34FB
```

### Sequence
1. Android scans QR code → gets MAC + UUID
2. Android connects to GATT Server
3. Android requests MTU (247)
4. Android sends REGISTER (app_name, package)
5. Android sends ICON_DATA × N (icon chunks)
6. Android sends ICON_END
7. PC/Mac stores app info and icon
8. PC/Mac sends ACK
```

- [ ] **Step 7: 编写错误处理**

```markdown
## 7. Error Handling

### Connection Errors
| Scenario | Handling |
|----------|----------|
| GATT connect fail | Retry 3 times, 1s interval |
| MTU negotiation fail | Fallback to default 23 bytes |
| GATT_BUSY | Serial queue wait |
| Send timeout | Retry 3 times, then fail |

### Android Side
| Scenario | Handling |
|----------|----------|
| Alarm triggers, BLE disconnected | Try reconnect, fail → local notification only |
| Scan fail | Callback onError |
| Pairing timeout | Callback onError, state rollback |

### PC/Mac Side
| Scenario | Handling |
|----------|----------|
| Computer sleep | GATT Server disconnects, auto-restart on wake |
| Multiple phones | Supported, each phone stores independently |
| Icon transfer interrupted | Re-transfer on next pairing |
| Notification permission | Guide user to authorize on first launch |
```

- [ ] **Step 8: 提交协议文档**

```bash
git add docs/protocol.md
git commit -m "docs: add BLE notification sync protocol specification"
```

---

## Phase 2: Android SDK (Kotlin)

### Task 2.1: 创建 Android 项目结构

**Files:**
- Create: `android/build.gradle.kts`
- Create: `android/sdk/build.gradle.kts`
- Create: `android/sdk/src/main/AndroidManifest.xml`
- Create: `android/sdk/src/main/java/com/blenotify/sdk/BleNotificationSDK.kt`

**Interfaces:**
- Consumes: 协议规范
- Produces: Android SDK 项目骨架

- [ ] **Step 1: 创建根 build.gradle.kts**

```kotlin
// android/build.gradle.kts
plugins {
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}
```

- [ ] **Step 2: 创建 SDK 模块 build.gradle.kts**

```kotlin
// android/sdk/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blenotify.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
```

- [ ] **Step 3: 创建 AndroidManifest.xml**

```xml
<!-- android/sdk/src/main/AndroidManifest.xml -->
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
</manifest>
```

- [ ] **Step 4: 创建 SDK 入口类骨架**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/BleNotificationSDK.kt
package com.blenotify.sdk

import android.app.Activity

class BleNotificationSDK private constructor() {

    companion object {
        @Volatile
        private var instance: BleNotificationSDK? = null

        fun getInstance(): BleNotificationSDK {
            return instance ?: synchronized(this) {
                instance ?: BleNotificationSDK().also { instance = it }
            }
        }
    }

    fun startPairing(activity: Activity, callback: PairingCallback) {
        TODO("Implement pairing")
    }

    fun isPaired(): Boolean {
        TODO("Implement")
    }

    fun setReminder(
        taskId: String,
        title: String,
        body: String,
        triggerAt: Long,
        actions: List<ReminderAction> = emptyList(),
        callback: ReminderCallback? = null
    ) {
        TODO("Implement reminder")
    }

    fun cancelReminder(taskId: String) {
        TODO("Implement")
    }

    fun sendNotification(
        title: String,
        body: String,
        callback: SendCallback? = null
    ) {
        TODO("Implement send")
    }
}
```

- [ ] **Step 5: 创建回调接口**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/Callbacks.kt
package com.blenotify.sdk

interface PairingCallback {
    fun onScanSuccess(mac: String)
    fun onConnecting()
    fun onRegistering()
    fun onPaired()
    fun onError(error: PairingError)
}

interface ReminderCallback {
    fun onScheduled(id: String)
    fun onTriggered(id: String)
    fun onSynced(id: String, success: Boolean)
}

interface SendCallback {
    fun onSuccess()
    fun onError(error: SendError)
}

data class ReminderAction(
    val label: String,
    val actionId: String
)

enum class PairingError {
    SCAN_FAILED,
    CONNECTION_FAILED,
    REGISTRATION_FAILED,
    TIMEOUT
}

enum class SendError {
    NOT_PAIRED,
    CONNECTION_FAILED,
    SEND_FAILED,
    TIMEOUT
}
```

- [ ] **Step 6: 提交项目骨架**

```bash
git add android/
git commit -m "feat(android): scaffold Android SDK project structure"
```

---

### Task 2.2: 实现协议层

**Files:**
- Create: `android/sdk/src/main/java/com/blenotify/sdk/protocol/BleFrame.kt`
- Create: `android/sdk/src/main/java/com/blenotify/sdk/protocol/FrameParser.kt`
- Create: `android/sdk/src/main/java/com/blenotify/sdk/protocol/MessageType.kt`
- Create: `android/sdk/src/test/java/com/blenotify/sdk/protocol/FrameParserTest.kt`

**Interfaces:**
- Consumes: 协议规范
- Produces: 可用的帧解析器

- [ ] **Step 1: 创建消息类型枚举**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/protocol/MessageType.kt
package com.blenotify.sdk.protocol

enum class MessageType(val value: Byte) {
    REGISTER(0x01),
    NOTIFY(0x02),
    ACK(0x03),
    ICON_DATA(0x04),
    ICON_END(0x05);

    companion object {
        fun fromByte(value: Byte): MessageType {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown message type: $value")
        }
    }
}
```

- [ ] **Step 2: 创建帧数据类**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/protocol/BleFrame.kt
package com.blenotify.sdk.protocol

data class BleFrame(
    val msgType: MessageType,
    val seq: Int,
    val totalSeq: Int,
    val payload: ByteArray
) {
    companion object {
        val MAGIC = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        const val HEADER_SIZE = 5 // Magic(2) + MsgType(1) + Seq(1) + TotalSeq(1)
        const val MAX_PAYLOAD_SIZE = 240
    }

    fun toBytes(): ByteArray {
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = MAGIC[0]
        frame[1] = MAGIC[1]
        frame[2] = msgType.value
        frame[3] = seq.toByte()
        frame[4] = totalSeq.toByte()
        payload.copyInto(frame, HEADER_SIZE)
        return frame
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleFrame) return false
        return msgType == other.msgType &&
                seq == other.seq &&
                totalSeq == other.totalSeq &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = msgType.hashCode()
        result = 31 * result + seq
        result = 31 * result + totalSeq
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
```

- [ ] **Step 3: 创建帧解析器**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/protocol/FrameParser.kt
package com.blenotify.sdk.protocol

object FrameParser {

    fun parse(data: ByteArray): BleFrame? {
        if (data.size < BleFrame.HEADER_SIZE) return null
        if (data[0] != BleFrame.MAGIC[0] || data[1] != BleFrame.MAGIC[1]) return null

        val msgType = MessageType.fromByte(data[2])
        val seq = data[3].toInt() and 0xFF
        val totalSeq = data[4].toInt() and 0xFF
        val payload = data.copyOfRange(BleFrame.HEADER_SIZE, data.size)

        return BleFrame(msgType, seq, totalSeq, payload)
    }

    fun split(msgType: MessageType, payload: ByteArray): List<BleFrame> {
        val frames = mutableListOf<BleFrame>()
        val totalSeq = (payload.size + BleFrame.MAX_PAYLOAD_SIZE - 1) / BleFrame.MAX_PAYLOAD_SIZE

        for (i in 0 until totalSeq) {
            val start = i * BleFrame.MAX_PAYLOAD_SIZE
            val end = minOf(start + BleFrame.MAX_PAYLOAD_SIZE, payload.size)
            val chunk = payload.copyOfRange(start, end)
            frames.add(BleFrame(msgType, i, totalSeq, chunk))
        }

        return frames
    }
}
```

- [ ] **Step 4: 编写单元测试**

```kotlin
// android/sdk/src/test/java/com/blenotify/sdk/protocol/FrameParserTest.kt
package com.blenotify.sdk.protocol

import org.junit.Assert.*
import org.junit.Test

class FrameParserTest {

    @Test
    fun `parse valid frame`() {
        val payload = """{"title":"test"}""".toByteArray()
        val frame = BleFrame(MessageType.NOTIFY, 0, 1, payload)
        val bytes = frame.toBytes()

        val parsed = FrameParser.parse(bytes)

        assertNotNull(parsed)
        assertEquals(MessageType.NOTIFY, parsed?.msgType)
        assertEquals(0, parsed?.seq)
        assertEquals(1, parsed?.totalSeq)
        assertArrayEquals(payload, parsed?.payload)
    }

    @Test
    fun `parse returns null for invalid magic`() {
        val data = byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x01)
        val parsed = FrameParser.parse(data)
        assertNull(parsed)
    }

    @Test
    fun `parse returns null for too short data`() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val parsed = FrameParser.parse(data)
        assertNull(parsed)
    }

    @Test
    fun `split large payload into frames`() {
        val payload = ByteArray(300) { it.toByte() }
        val frames = FrameParser.split(MessageType.ICON_DATA, payload)

        assertEquals(2, frames.size)
        assertEquals(0, frames[0].seq)
        assertEquals(2, frames[0].totalSeq)
        assertEquals(240, frames[0].payload.size)

        assertEquals(1, frames[1].seq)
        assertEquals(2, frames[1].totalSeq)
        assertEquals(60, frames[1].payload.size)
    }

    @Test
    fun `split small payload into single frame`() {
        val payload = ByteArray(100) { it.toByte() }
        val frames = FrameParser.split(MessageType.NOTIFY, payload)

        assertEquals(1, frames.size)
        assertEquals(0, frames[0].seq)
        assertEquals(1, frames[0].totalSeq)
        assertEquals(100, frames[0].payload.size)
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
cd android && ./gradlew :sdk:testDebugUnitTest
```

- [ ] **Step 6: 提交协议层**

```bash
git add android/sdk/src/
git commit -m "feat(android): implement BLE frame protocol and parser"
```

---

### Task 2.3: 实现 BLE 通信层

**Files:**
- Create: `android/sdk/src/main/java/com/blenotify/sdk/ble/BleConnection.kt`
- Create: `android/sdk/src/main/java/com/blenotify/sdk/ble/GattCallbackHandler.kt`
- Create: `android/sdk/src/main/java/com/blenotify/sdk/ble/SendQueue.kt`

**Interfaces:**
- Consumes: FrameParser, BleFrame
- Produces: BleConnection with send/receive capabilities

- [ ] **Step 1: 创建发送队列**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/ble/SendQueue.kt
package com.blenotify.sdk.ble

import com.blenotify.sdk.protocol.BleFrame
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class SendQueue(
    private val onSend: (BleFrame) -> Boolean
) {
    private val queue = ConcurrentLinkedQueue<BleFrame>()
    private val isSending = AtomicBoolean(false)

    fun enqueue(frames: List<BleFrame>) {
        frames.forEach { queue.offer(it) }
        processNext()
    }

    private fun processNext() {
        if (isSending.compareAndSet(false, true)) {
            val frame = queue.poll()
            if (frame != null) {
                val success = onSend(frame)
                isSending.set(false)
                if (success) {
                    processNext()
                }
            } else {
                isSending.set(false)
            }
        }
    }

    fun clear() {
        queue.clear()
        isSending.set(false)
    }
}
```

- [ ] **Step 2: 创建 GATT 回调处理器**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/ble/GattCallbackHandler.kt
package com.blenotify.sdk.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice

class GattCallbackHandler(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onServicesDiscovered: (List<BluetoothGattService>) -> Unit,
    private val onMtuChanged: (Int) -> Unit
) : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        when (newState) {
            android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                gatt?.discoverServices()
            }
            android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                onDisconnected()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            gatt?.services?.let { onServicesDiscovered(it) }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            onMtuChanged(mtu)
        }
    }
}
```

- [ ] **Step 3: 创建 BLE 连接管理器**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/ble/BleConnection.kt
package com.blenotify.sdk.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import com.blenotify.sdk.protocol.BleFrame
import com.blenotify.sdk.protocol.FrameParser
import com.blenotify.sdk.protocol.MessageType
import java.util.UUID

class BleConnection(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000A1B2-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000C3D4-0000-1000-8000-00805F9B34FB")
        const val REQUESTED_MTU = 247
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetCharacteristic: BluetoothGattCharacteristic? = null
    private val sendQueue = SendQueue { frame -> writeFrame(frame) }

    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onDataReceived: ((ByteArray) -> Unit)? = null

    fun connect(
        device: BluetoothDevice,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit
    ) {
        val callback = GattCallbackHandler(
            onConnected = {
                bluetoothGatt?.requestMtu(REQUESTED_MTU)
                onConnected()
            },
            onDisconnected = onDisconnected,
            onServicesDiscovered = { services ->
                targetCharacteristic = services
                    .flatMap { it.characteristics }
                    .find { it.uuid == CHARACTERISTIC_UUID }
            },
            onMtuChanged = { /* MTU negotiated */ }
        )

        bluetoothGatt = device.connectGatt(context, false, callback)
    }

    fun sendFrame(frame: BleFrame) {
        sendQueue.enqueue(listOf(frame))
    }

    fun sendFrames(frames: List<BleFrame>) {
        sendQueue.enqueue(frames)
    }

    fun disconnect() {
        sendQueue.clear()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        targetCharacteristic = null
    }

    private fun writeFrame(frame: BleFrame): Boolean {
        val characteristic = targetCharacteristic ?: return false
        val gatt = bluetoothGatt ?: return false

        characteristic.value = frame.toBytes()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return gatt.writeCharacteristic(characteristic)
    }
}
```

- [ ] **Step 4: 提交 BLE 通信层**

```bash
git add android/sdk/src/main/java/com/blenotify/sdk/ble/
git commit -m "feat(android): implement BLE connection manager"
```

---

### Task 2.4: 实现 SDK API

**Files:**
- Modify: `android/sdk/src/main/java/com/blenotify/sdk/BleNotificationSDK.kt`
- Create: `android/sdk/src/main/java/com/blenotify/sdk/PairingManager.kt`
- Create: `android/sdk/src/main/java/com/blenotify/sdk/ReminderManager.kt`

**Interfaces:**
- Consumes: BleConnection, FrameParser
- Produces: 完整的 SDK API

- [ ] **Step 1: 实现配对管理器**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/PairingManager.kt
package com.blenotify.sdk

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.blenotify.sdk.ble.BleConnection
import com.blenotify.sdk.protocol.FrameParser
import com.blenotify.sdk.protocol.MessageType
import org.json.JSONObject

class PairingManager(
    private val context: Context,
    private val connection: BleConnection
) {
    private var currentCallback: PairingCallback? = null

    fun startPairing(activity: Activity, callback: PairingCallback) {
        currentCallback = callback
        // TODO: Launch QR scanner activity
        // For now, simulate scan success
        callback.onScanSuccess("XX:XX:XX:XX:XX:XX")
    }

    fun onScanResult(mac: String) {
        currentCallback?.onConnecting()

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val device = adapter.getRemoteDevice(mac)

        connection.connect(
            device = device,
            onConnected = {
                currentCallback?.onRegistering()
                sendRegister()
            },
            onDisconnected = {
                currentCallback?.onError(PairingError.CONNECTION_FAILED)
            }
        )
    }

    private fun sendRegister() {
        val json = JSONObject().apply {
            put("app_name", getAppName())
            put("package", context.packageName)
        }
        val payload = json.toString().toByteArray()
        val frames = FrameParser.split(MessageType.REGISTER, payload)
        connection.sendFrames(frames)
        // TODO: Wait for ACK and send icon
    }

    private fun getAppName(): String {
        val pm = context.packageManager
        val appInfo = context.applicationInfo
        return pm.getApplicationLabel(appInfo).toString()
    }
}
```

- [ ] **Step 2: 实现闹钟管理器**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/ReminderManager.kt
package com.blenotify.sdk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class ReminderManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun setReminder(
        taskId: String,
        title: String,
        body: String,
        triggerAt: Long,
        callback: ReminderCallback?
    ) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("task_id", taskId)
            putExtra("title", title)
            putExtra("body", body)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }

        callback?.onScheduled(taskId)
    }

    fun cancelReminder(taskId: String) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
}
```

- [ ] **Step 3: 实现完整 SDK**

```kotlin
// android/sdk/src/main/java/com/blenotify/sdk/BleNotificationSDK.kt
package com.blenotify.sdk

import android.app.Activity
import android.content.Context
import com.blenotify.sdk.ble.BleConnection
import com.blenotify.sdk.protocol.FrameParser
import com.blenotify.sdk.protocol.MessageType
import org.json.JSONObject

class BleNotificationSDK private constructor(private val context: Context) {

    private val connection = BleConnection(context)
    private val pairingManager = PairingManager(context, connection)
    private val reminderManager = ReminderManager(context)

    companion object {
        @Volatile
        private var instance: BleNotificationSDK? = null

        fun init(context: Context): BleNotificationSDK {
            return instance ?: synchronized(this) {
                instance ?: BleNotificationSDK(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(): BleNotificationSDK {
            return instance ?: throw IllegalStateException("SDK not initialized. Call init() first.")
        }
    }

    fun startPairing(activity: Activity, callback: PairingCallback) {
        pairingManager.startPairing(activity, callback)
    }

    fun isPaired(): Boolean {
        // TODO: Check stored pairing info
        return false
    }

    fun setReminder(
        taskId: String,
        title: String,
        body: String,
        triggerAt: Long,
        actions: List<ReminderAction> = emptyList(),
        callback: ReminderCallback? = null
    ) {
        reminderManager.setReminder(taskId, title, body, triggerAt, callback)
    }

    fun cancelReminder(taskId: String) {
        reminderManager.cancelReminder(taskId)
    }

    fun sendNotification(
        title: String,
        body: String,
        callback: SendCallback? = null
    ) {
        if (!isPaired()) {
            callback?.onError(SendError.NOT_PAIRED)
            return
        }

        val json = JSONObject().apply {
            put("title", title)
            put("body", body)
            put("package", context.packageName)
            put("timestamp", System.currentTimeMillis())
        }
        val payload = json.toString().toByteArray()
        val frames = FrameParser.split(MessageType.NOTIFY, payload)
        connection.sendFrames(frames)
        callback?.onSuccess()
    }

    fun disconnect() {
        connection.disconnect()
    }
}
```

- [ ] **Step 4: 提交 SDK API**

```bash
git add android/sdk/src/main/java/com/blenotify/sdk/
git commit -m "feat(android): implement complete SDK API"
```

---

## Phase 3: Windows 端 (C# .NET)

### Task 3.1: 创建 .NET 项目结构

**Files:**
- Create: `windows/BleNotificationWin.sln`
- Create: `windows/BleNotificationWin/BleNotificationWin.csproj`
- Create: `windows/BleNotificationWin/Program.cs`

**Interfaces:**
- Consumes: 协议规范
- Produces: Windows 应用项目骨架

- [ ] **Step 1: 创建解决方案**

```bash
cd windows
dotnet new sln -n BleNotificationWin
dotnet new winforms -n BleNotificationWin -o BleNotificationWin
dotnet sln add BleNotificationWin/BleNotificationWin.csproj
```

- [ ] **Step 2: 添加 BLE 依赖**

```xml
<!-- windows/BleNotificationWin/BleNotificationWin.csproj -->
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net8.0-windows</TargetFramework>
    <UseWindowsForms>true</UseWindowsForms>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Windows.Devices.Bluetooth" Version="1.0.1" />
    <PackageReference Include="Microsoft.Toolkit.Uwp.Notifications" Version="7.1.3" />
  </ItemGroup>
</Project>
```

- [ ] **Step 3: 创建入口点**

```csharp
// windows/BleNotificationWin/Program.cs
using System;
using System.Windows.Forms;

namespace BleNotificationWin
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new TrayApp());
        }
    }
}
```

- [ ] **Step 4: 提交项目骨架**

```bash
git add windows/
git commit -m "feat(windows): scaffold .NET project structure"
```

---

### Task 3.2: 实现 GATT Server

**Files:**
- Create: `windows/BleNotificationWin/GattServerService.cs`
- Create: `windows/BleNotificationWin/Protocol/MessageType.cs`
- Create: `windows/BleNotificationWin/Protocol/BleFrame.cs`

**Interfaces:**
- Consumes: 协议规范
- Produces: Windows GATT Server

- [ ] **Step 1: 创建消息类型**

```csharp
// windows/BleNotificationWin/Protocol/MessageType.cs
namespace BleNotificationWin.Protocol
{
    public enum MessageType : byte
    {
        REGISTER = 0x01,
        NOTIFY = 0x02,
        ACK = 0x03,
        ICON_DATA = 0x04,
        ICON_END = 0x05
    }
}
```

- [ ] **Step 2: 创建帧解析器**

```csharp
// windows/BleNotificationWin/Protocol/BleFrame.cs
using System;

namespace BleNotificationWin.Protocol
{
    public class BleFrame
    {
        public static readonly byte[] MAGIC = new byte[] { 0xAA, 0xBB };
        public const int HEADER_SIZE = 5;
        public const int MAX_PAYLOAD_SIZE = 240;

        public MessageType MsgType { get; set; }
        public int Seq { get; set; }
        public int TotalSeq { get; set; }
        public byte[] Payload { get; set; }

        public static BleFrame Parse(byte[] data)
        {
            if (data == null || data.Length < HEADER_SIZE)
                return null;

            if (data[0] != MAGIC[0] || data[1] != MAGIC[1])
                return null;

            return new BleFrame
            {
                MsgType = (MessageType)data[2],
                Seq = data[3],
                TotalSeq = data[4],
                Payload = data[HEADER_SIZE..]
            };
        }

        public byte[] ToBytes()
        {
            var frame = new byte[HEADER_SIZE + Payload.Length];
            frame[0] = MAGIC[0];
            frame[1] = MAGIC[1];
            frame[2] = (byte)MsgType;
            frame[3] = (byte)Seq;
            frame[4] = (byte)TotalSeq;
            Payload.CopyTo(frame, HEADER_SIZE);
            return frame;
        }
    }
}
```

- [ ] **Step 3: 创建 GATT Server**

```csharp
// windows/BleNotificationWin/GattServerService.cs
using System;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Bluetooth.Advertisement;

namespace BleNotificationWin
{
    public class GattServerService
    {
        private GattServiceProvider _serviceProvider;
        private GattLocalCharacteristic _writeCharacteristic;
        private BluetoothLEAdvertisementPublisher _publisher;

        public event Action<byte[]> DataReceived;

        public async Task StartAsync()
        {
            var serviceResult = await GattServiceProvider.CreateAsync(
                new Guid("0000A1B2-0000-1000-8000-00805F9B34FB"));

            if (serviceResult.Error != BluetoothError.Success)
                throw new Exception("Failed to create GATT service");

            _serviceProvider = serviceResult.ServiceProvider;

            var characteristicResult = await _serviceProvider.Service.CreateCharacteristicAsync(
                new Guid("0000C3D4-0000-1000-8000-00805F9B34FB"),
                new GattLocalCharacteristicParameters
                {
                    WriteProtectionLevel = GattProtectionLevel.Plain,
                    CharacteristicProperties = GattCharacteristicProperties.WriteWithoutResponse
                });

            if (characteristicResult.Error != BluetoothError.Success)
                throw new Exception("Failed to create characteristic");

            _writeCharacteristic = characteristicResult.Characteristic;
            _writeCharacteristic.WriteRequested += OnWriteRequested;

            _serviceProvider.StartAdvertising(new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = true
            });

            _publisher = new BluetoothLEAdvertisementPublisher();
            _publisher.Advertisement.ServiceUuids.Add(
                new Guid("0000A1B2-0000-1000-8000-00805F9B34FB"));
            _publisher.Start();
        }

        private async void OnWriteRequested(
            GattSession session,
            GattWriteRequestedEventArgs args)
        {
            var deferral = args.GetDeferral();
            try
            {
                var request = await args.GetRequestAsync();
                var reader = Windows.Storage.Streams.DataReader.FromBuffer(request.Value);
                var data = new byte[reader.UnconsumedBufferLength];
                reader.ReadBytes(data);
                DataReceived?.Invoke(data);
                request.Respond();
            }
            finally
            {
                deferral.Complete();
            }
        }

        public void Stop()
        {
            _serviceProvider?.StopAdvertising();
            _publisher?.Stop();
        }
    }
}
```

- [ ] **Step 4: 提交 GATT Server**

```bash
git add windows/BleNotificationWin/
git commit -m "feat(windows): implement GATT server and protocol"
```

---

### Task 3.3: 实现通知管理器

**Files:**
- Create: `windows/BleNotificationWin/NotificationManager.cs`

**Interfaces:**
- Consumes: BleFrame
- Produces: Toast 通知

- [ ] **Step 1: 实现通知管理器**

```csharp
// windows/BleNotificationWin/NotificationManager.cs
using Microsoft.Toolkit.Uwp.Notifications;
using System.Text.Json;

namespace BleNotificationWin
{
    public class NotificationManager
    {
        public void ShowNotification(string jsonPayload)
        {
            var data = JsonSerializer.Deserialize<NotificationData>(jsonPayload);

            new ToastContentBuilder()
                .AddText(data?.Title ?? "Notification")
                .AddText(data?.Body ?? "")
                .Show();
        }

        private class NotificationData
        {
            public string Title { get; set; }
            public string Body { get; set; }
            public string Package { get; set; }
            public long Timestamp { get; set; }
        }
    }
}
```

- [ ] **Step 2: 提交通知管理器**

```bash
git add windows/BleNotificationWin/NotificationManager.cs
git commit -m "feat(windows): implement toast notification manager"
```

---

### Task 3.4: 实现托盘应用

**Files:**
- Create: `windows/BleNotificationWin/TrayApp.cs`

**Interfaces:**
- Consumes: GattServerService, NotificationManager
- Produces: 系统托盘应用

- [ ] **Step 1: 实现托盘应用**

```csharp
// windows/BleNotificationWin/TrayApp.cs
using System;
using System.Drawing;
using System.Windows.Forms;
using BleNotificationWin.Protocol;

namespace BleNotificationWin
{
    public class TrayApp : Form
    {
        private NotifyIcon _trayIcon;
        private GattServerService _gattServer;
        private NotificationManager _notificationManager;

        public TrayApp()
        {
            _notificationManager = new NotificationManager();
            _gattServer = new GattServerService();
            _gattServer.DataReceived += OnDataReceived;

            _trayIcon = new NotifyIcon
            {
                Icon = SystemIcons.Application,
                Visible = true,
                Text = "BLE Notification Sync"
            };

            var menu = new ContextMenuStrip();
            menu.Items.Add("Status: Running", null, null);
            menu.Items.Add("-");
            menu.Items.Add("Exit", null, (s, e) =>
            {
                _gattServer.Stop();
                _trayIcon.Visible = false;
                Application.Exit();
            });

            _trayIcon.ContextMenuStrip = menu;

            _gattServer.StartAsync().Wait();
        }

        private void OnDataReceived(byte[] data)
        {
            var frame = BleFrame.Parse(data);
            if (frame?.MsgType == MessageType.NOTIFY)
            {
                var json = System.Text.Encoding.UTF8.GetString(frame.Payload);
                _notificationManager.ShowNotification(json);
            }
        }

        protected override void OnLoad(EventArgs e)
        {
            Visible = false;
            ShowInTaskbar = false;
            base.OnLoad(e);
        }
    }
}
```

- [ ] **Step 2: 提交托盘应用**

```bash
git add windows/BleNotificationWin/TrayApp.cs
git commit -m "feat(windows): implement system tray application"
```

---

## Phase 4: macOS 端 (Swift)

### Task 4.1: 创建 Xcode 项目结构

**Files:**
- Create: `macos/BleNotificationMac/BleNotificationMacApp.swift`
- Create: `macos/BleNotificationMac/Info.plist`

**Interfaces:**
- Consumes: 协议规范
- Produces: macOS 应用项目骨架

- [ ] **Step 1: 创建 SwiftUI App**

```swift
// macos/BleNotificationMac/BleNotificationMacApp.swift
import SwiftUI

@main
struct BleNotificationMacApp: App {
    var body: some Scene {
        MenuBarExtra("BLE Sync", systemImage: "antenna.radiowaves.left.and.right") {
            ContentView()
        }
    }
}
```

- [ ] **Step 2: 创建 ContentView**

```swift
// macos/BleNotificationMac/ContentView.swift
import SwiftUI

struct ContentView: View {
    @StateObject private var serverManager = ServerManager()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("BLE Notification Sync")
                .font(.headline)

            Divider()

            HStack {
                Circle()
                    .fill(serverManager.isRunning ? .green : .red)
                    .frame(width: 10, height: 10)
                Text(serverManager.isRunning ? "Running" : "Stopped")
            }

            Divider()

            Button("Exit") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding()
        .frame(width: 200)
    }
}
```

- [ ] **Step 3: 提交项目骨架**

```bash
git add macos/
git commit -m "feat(macos): scaffold SwiftUI project structure"
```

---

### Task 4.2: 实现 GATT Server

**Files:**
- Create: `macos/BleNotificationMac/PeripheralManager.swift`
- Create: `macos/BleNotificationMac/Protocol/BleFrame.swift`

**Interfaces:**
- Consumes: 协议规范
- Produces: macOS GATT Server

- [ ] **Step 1: 创建帧解析器**

```swift
// macos/BleNotificationMac/Protocol/BleFrame.swift
import Foundation

enum MessageType: UInt8 {
    case register = 0x01
    case notify = 0x02
    case ack = 0x03
    case iconData = 0x04
    case iconEnd = 0x05
}

struct BleFrame {
    static let magic: [UInt8] = [0xAA, 0xBB]
    static let headerSize = 5
    static let maxPayloadSize = 240

    let msgType: MessageType
    let seq: Int
    let totalSeq: Int
    let payload: Data

    static func parse(_ data: Data) -> BleFrame? {
        let bytes = [UInt8](data)
        guard bytes.count >= headerSize else { return nil }
        guard bytes[0] == magic[0], bytes[1] == magic[1] else { return nil }

        guard let msgType = MessageType(rawValue: bytes[2]) else { return nil }

        return BleFrame(
            msgType: msgType,
            seq: Int(bytes[3]),
            totalSeq: Int(bytes[4]),
            payload: Data(bytes[headerSize...])
        )
    }

    func toData() -> Data {
        var bytes = [UInt8]()
        bytes.append(contentsOf: Self.magic)
        bytes.append(msgType.rawValue)
        bytes.append(UInt8(seq))
        bytes.append(UInt8(totalSeq))
        bytes.append(contentsOf: [UInt8](payload))
        return Data(bytes)
    }
}
```

- [ ] **Step 2: 创建 PeripheralManager**

```swift
// macos/BleNotificationMac/PeripheralManager.swift
import Foundation
import CoreBluetooth

class PeripheralManager: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    private var peripheralManager: CBPeripheralManager?
    private var serviceUUID: CBUUID?
    private var characteristicUUID: CBUUID?

    @Published var isRunning = false

    var onDataReceived: ((Data) -> Void)?

    func start() {
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    func stop() {
        peripheralManager?.stopAdvertising()
        peripheralManager = nil
        isRunning = false
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { return }

        serviceUUID = CBUUID(string: "0000A1B2-0000-1000-8000-00805F9B34FB")
        characteristicUUID = CBUUID(string: "0000C3D4-0000-1000-8000-00805F9B34FB")

        let characteristic = CBMutableCharacteristic(
            type: characteristicUUID!,
            properties: .writeWithoutResponse,
            value: nil,
            permissions: .writeable
        )

        let service = CBMutableService(type: serviceUUID!, primary: true)
        service.characteristics = [characteristic]

        peripheral.add(service)
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID!]
        ])

        DispatchQueue.main.async {
            self.isRunning = true
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if let value = request.value {
                onDataReceived?(value)
            }
            peripheral.respond(to: request, withResult: .success)
        }
    }
}
```

- [ ] **Step 3: 提交 GATT Server**

```bash
git add macos/BleNotificationMac/
git commit -m "feat(macos): implement CBPeripheralManager and protocol"
```

---

### Task 4.3: 实现通知服务

**Files:**
- Create: `macos/BleNotificationMac/NotificationService.swift`

**Interfaces:**
- Consumes: BleFrame
- Produces: UserNotifications

- [ ] **Step 1: 实现通知服务**

```swift
// macos/BleNotificationMac/NotificationService.swift
import Foundation
import UserNotifications

class NotificationService: NSObject, UNUserNotificationCenterDelegate {
    override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
        requestPermission()
    }

    func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(
            options: [.alert, .sound]
        ) { granted, error in
            if let error = error {
                print("Notification permission error: \(error)")
            }
        }
    }

    func showNotification(jsonPayload: String) {
        guard let data = jsonPayload.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let title = json["title"] as? String else {
            return
        }

        let body = json["body"] as? String ?? ""

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}
```

- [ ] **Step 2: 提交通知服务**

```bash
git add macos/BleNotificationMac/NotificationService.swift
git commit -m "feat(macos): implement UserNotifications service"
```

---

### Task 4.4: 实现 ServerManager

**Files:**
- Create: `macos/BleNotificationMac/ServerManager.swift`

**Interfaces:**
- Consumes: PeripheralManager, NotificationService
- Produces: 统一的服务器管理器

- [ ] **Step 1: 实现 ServerManager**

```swift
// macos/BleNotificationMac/ServerManager.swift
import Foundation

class ServerManager: ObservableObject {
    private let peripheralManager = PeripheralManager()
    private let notificationService = NotificationService()

    @Published var isRunning: Bool = false

    init() {
        peripheralManager.onDataReceived = { [weak self] data in
            self?.handleData(data)
        }
        peripheralManager.start()
    }

    private func handleData(_ data: Data) {
        guard let frame = BleFrame.parse(data) else { return }

        switch frame.msgType {
        case .notify:
            if let json = String(data: frame.payload, encoding: .utf8) {
                notificationService.showNotification(jsonPayload: json)
            }
        case .register:
            // Handle registration
            break
        default:
            break
        }
    }
}
```

- [ ] **Step 2: 提交 ServerManager**

```bash
git add macos/BleNotificationMac/ServerManager.swift
git commit -m "feat(macos): implement ServerManager to coordinate components"
```

---

## Phase 5: 联调测试与文档

### Task 5.1: 编写 README

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: 所有设计文档和代码
- Produces: 项目说明文档

- [ ] **Step 1: 编写 README**

```markdown
# BLE Notification Sync

跨平台 BLE 闹钟通知同步开源项目。通过低功耗蓝牙将 Android 手机的闹钟通知推送到 Windows/macOS 电脑。

## Features

- 零云端、零账号、纯本地
- 二维码快速配对
- 支持 APP 图标传输
- 系统原生通知

## Supported Platforms

| Platform | Role | Technology |
|----------|------|------------|
| Android | GATT Client | Kotlin SDK |
| Windows | GATT Server | C# .NET 8 |
| macOS | GATT Server | Swift |

## Quick Start

### Android

```kotlin
// Initialize SDK
BleNotificationSDK.init(context)

// Pair with PC
sdk.startPairing(activity, callback)

// Set reminder
sdk.setReminder(
    taskId = "task_123",
    title = "Meeting",
    body = "Product review at 10:00",
    triggerAt = System.currentTimeMillis() + 3600000
)
```

### Windows

1. Build and run `BleNotificationWin.sln`
2. App will appear in system tray
3. Scan QR code from Android to pair

### macOS

1. Build and run `BleNotificationMac.xcodeproj`
2. App will appear in menu bar
3. Scan QR code from Android to pair

## Protocol

See [docs/protocol.md](docs/protocol.md) for detailed protocol specification.

## License

MIT
```

- [ ] **Step 2: 提交 README**

```bash
git add README.md
git commit -m "docs: add project README"
```

---

### Task 5.2: 初始化 Git 仓库

**Files:**
- Create: `.gitignore`

**Interfaces:**
- Consumes: 项目文件
- Produces: Git 仓库

- [ ] **Step 1: 创建 .gitignore**

```gitignore
# Android
*.iml
.gradle
/local.properties
/.idea
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
android/build/
android/sdk/build/

# Windows
[Bb]in/
[Oo]bj/
*.user
*.suo
.vs/

# macOS
*.xcodeproj/project.xcworkspace/
*.xcodeproj/xcuserdata/
*.xcuserstate
DerivedData/
build/

# IDE
.vscode/
.idea/
```

- [ ] **Step 2: 初始化 Git**

```bash
cd /mnt/androiddev/MultiPlatformProjects/BleNotificationSync
git init
git add .
git commit -m "feat: initial project structure with protocol spec, Android SDK, Windows and macOS apps"
```

---

## Summary

| Phase | Tasks | Deliverables |
|-------|-------|--------------|
| Phase 1 | 1 | docs/protocol.md |
| Phase 2 | 4 | android/sdk/ (Kotlin AAR) |
| Phase 3 | 4 | windows/BleNotificationWin/ (.NET) |
| Phase 4 | 4 | macos/BleNotificationMac/ (Swift) |
| Phase 5 | 2 | README.md, .gitignore, Git init |

**Total:** 15 tasks
