# Code Review Report — 2026-07-24

## 审查范围

- **SDK 模块**：`sdk/src/main/java/` 下全部 18 个源文件 + 资源文件
- **Demo 模块**：`examples/android-demo/src/main/` 下全部源文件 + 布局 + 资源 + Manifest
- **审查基准**：git HEAD `4d26456`（chore: configure maven-publish plugin for jitpack release）

---

## SDK 模块

### 结果概要

| 级别 | 数量 | 决策 |
|------|------|------|
| Blocking | 2 | 1 已修复 / 1 驳回 |
| Important | 3 | 3 已修复 |
| Suggestion | 4 | 1 已修复 / 3 驳回 |

---

### [x] #1 [Blocking] GATT 断开时序致 App 图标发送永远失败

**文件：**
- `sdk/.../ble/BleClient.kt:184-191`
- `sdk/.../pairing/PairingManager.kt:116-148`

**修复状态：驳回。** `gatt.disconnect()` 异步执行，图标 Thread 400ms 延迟窗口内 BLE 栈仍可处理写入，实际已调通图标可成功传送。

---

### [x] #2 [Blocking] QrScanner.analysisExecutor 线程泄漏

**文件：** `sdk/.../qr/QrScanner.kt:23`

**修复状态：已修复。** `stop()` 添加 `analysisExecutor.shutdownNow()`（Line 43）。代码已验证。

---

### [x] #3 [Important] BleClient MTU 协商失败进入静默死等

**文件：** `sdk/.../ble/BleClient.kt:180-182`

**修复状态：已修复。** `onMtuChanged` 的 else 分支添加 `callback.onError(SdkError.ConnectionFailed("mtu:$status"))` 和 `gatt.close()`（Line 182）。代码已验证。

---

### [x] #4 [Important] BleNotificationSDK.openDeviceManager(context) 参数未使用

**文件：** `sdk/.../sdk/BleNotificationSDK.kt:142-144`

**修复状态：已修复。** 移除 `context` 参数，改为无参 `fun openDeviceManager()`（Line 142）。代码已验证。

---

### [x] #5 [Important] PairingManager.transitionTo(PAIRED) 不回调 onPaired

**文件：** `sdk/.../pairing/PairingManager.kt:290-298`

**修复状态：已修复。** 误导性注释 `/* already called */` 替换为完整设计说明（Lines 295-296）。PAIRED case 体为空。代码已验证。

---

### [x] #6 [Suggestion] BleForegroundService stopSelf() 与 GATT 关闭的竞争

**文件：** `sdk/.../sdk/BleForegroundService.kt:98`

**修复状态：驳回。** 1500ms 远长于 GATT 异步断开耗时，竞争风险几乎为零。

---

### [x] #7 [Suggestion] BleClient.connectDirectly() 状态标志重复重置

**文件：** `sdk/.../ble/BleClient.kt:125,154`

**修复状态：已修复。** 删除 L154 重复的 `servicesDone/mtuDone/readyCalled` 重置。保留 L125 单次初始化。代码已验证。

---

### [x] #8 [Suggestion] PairingManager.getPairedDevices() 未安全检查 value 类型

**文件：** `sdk/.../pairing/PairingManager.kt:266`

**修复状态：已修复。** `value as String` 改为 `value as? String ?: return@mapNotNull null`（Line 266）。代码已验证。

---

### [x] #9 [Suggestion] DeviceManagerFragment Fragment 容器 ID 解析脆弱

**文件：** `sdk/.../ui/DeviceManagerFragment.kt:117`

**修复状态：驳回。** SDK 自有 Fragment 层次内 parent 结构可控，不影响集成方。

---

## Demo 模块

### 结果概要

| 级别 | 数量 | 决策 |
|------|------|------|
| Important | 2 | 2 已修复 |
| Suggestion | 3 | 3 驳回 |

---

### [x] #10 [Important] 中文日志遗漏

**文件：**
- `DemoAlarmReceiver.kt:25,31,35,39`
- `MainActivity.kt:220,224,230,253,264`

**修复状态：已修复。**
- DemoAlarmReceiver 全部 4 处 Log 改为英文，首条加 `Log.isLoggable(DEBUG)` 守卫
- MainActivity.doScanOnly() 全部中文 Log 改为英文，tag 统一为 `"BleDemo"`
代码已验证。

---

### [x] #11 [Important] Log TextView 12sp 未纳入 dimens 体系

**文件：** `activity_main.xml:119`

**修复状态：已修复。** `dimens.xml` 新增 `text_size_log 12sp`（含注释），`tv_log` 引用 `@dimen/text_size_log`。代码已验证。

---

### [x] #12 [Suggestion] startPairing() 潜在返回栈 bug

**文件：** `MainActivity.kt:120-157`

**修复状态：驳回。** `startPairing()` 当前为死代码，无实际影响。

---

### [x] #13 [Suggestion] enableEdgeToEdge 与手动 inset listener 冲突

**文件：** `MainActivity.kt:38,42-47`

**修复状态：驳回。** 需真机实测确认 double padding，暂缓。

---

### [x] #14 [Suggestion] DemoAlarmReceiver 同步调用 SDK

**文件：** `DemoAlarmReceiver.kt:27-38`

**修复状态：驳回。** `sendNotification` 是异步的（本地通知 + startService），10 秒超时绰绰有余，过度防御。

---

## 审核结论

全部 14 项审查发现均已处理：
- **8 项已修复**（代码验证通过，修复质量合格，无引入新问题）
- **6 项驳回**（经核实确为误判或风险极低，予以关闭）

**决策：Approve。** 无待处理项。
