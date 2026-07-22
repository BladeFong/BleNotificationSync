# Android SDK 模块代码审查报告 (code-review-excellence)

## 1. 架构分层与 API 暴露
- **单一职责违背 (SRP)**：`BleNotificationSDK` 类承载了过多的职责（Fragment 路由、权限请求、BLE 连接调度）。建议将权限请求与 UI 路由解耦为专门的逻辑类。
- **UI 与宿主高耦合**：Fragment 路由直接查找 `android.R.id.content` 并强依赖 `FragmentActivity` (`activity.supportFragmentManager.beginTransaction().replace()`)，这在第三方集成时极易崩溃。建议通过接口回调或解耦的导航方案实现。
- **全局状态导致竞态条件**：`BleClient` 是一个单例 `object`，但内部维护了易变状态 (`servicesDone`, `mtuDone`, `readyCalled`, `activeScanCallback`)。多设备连接或重连时会发生状态污染，不具备线程安全性。

## 2. 蓝牙 BLE/GATT 资源释放与异常处理
- **魔术数字延迟与不可靠的资源释放**：在 `BleNotificationSDK.kt` 与 `BleForegroundService.kt` 中，均使用 `Handler.postDelayed({ gatt.close() }, 3000)` 来释放 GATT 资源。未等待 `onCharacteristicWrite` 成功回调确认就直接关闭连接会导致数据丢失或底层状态泄漏。
- **废弃的 API 调用**：使用了 `characteristic.value = frame; gatt.writeCharacteristic(characteristic)`。在 Android 13 (API 33) 及以上版本中，该方法已废弃，应改用 `writeCharacteristic(BluetoothGattCharacteristic, ByteArray, int)`。
- **异常捕获缺失**：断开连接超时时直接调用 `gatt.close()`，但并未注销回调或保证在适当的线程调用，易导致 `DeadObjectException`。
- **废弃接口**：`BluetoothAdapter.getDefaultAdapter()` 已废弃，应使用 `BluetoothManager.getAdapter()`。

## 3. 线程/协程安全与 Flow 使用规范
- **回调地狱与主线程阻塞**：BLE 扫描和连接充斥着深层 Callback 和 `Handler(Looper.getMainLooper()).postDelayed`。建议迁移至 Kotlin 协程（使用 `suspendCancellableCoroutine`），以便更好地控制超时、异常取消和上下文切换。
- **并发与 Flow 同步**：在 `PairingManager` 中，`pairedDevicesFlow` 使用了 StateFlow，但在非主线程的各种 BLE 异常回调中可能触发 `refreshPairedDevices()` 从而更新 `_pairedDevicesFlow.value`，缺乏统一的分发器 (Dispatcher) 约束，可能导致并发问题。

## 4. UI 兼容性
- **Compose 主题与 MD2 兼容问题**：在 `DeviceManagerFragment.kt` 中，UI 色值（如背景色 `Color(0xFFF5F5F7)`、按键文字色 `Color(0xFFD32F2F)` 等）被硬编码，破坏了 Compose 主题系统，不支持暗色模式适配。
- **WindowInsets 避让脆弱性**：使用了 `statusBarsPadding()`，但前提要求宿主 Activity 正确调用了 `WindowCompat.setDecorFitsSystemWindows`。作为 SDK 无法保证宿主行为，可能导致布局重叠。

## 5. 错误处理与安全性
- **JSON 注入风险**：在 `FrameEncoder.kt` 的 `buildJson` 内部，通过 `""$key":$value"` 直接进行字符串拼接构造 JSON。如果 `title` 或 `body` 中包含英文双引号 `"`，将导致 JSON 格式破坏和潜在解析漏洞，必须使用成熟的序列化库（如 kotlinx.serialization 或 org.json）。
- **硬编码的密盐与存储薄弱**：
  - `NativeCrypto.SALT` 被硬编码为 `"BleNotificationSync"`，降低了 HKDF 的安全性。
  - `PairingManager` 虽然使用了 `EncryptedSharedPreferences`，但内部又把敏感密钥与普通字符通过 `"|"` 手动序列化拼接为字符串保存。这种字符串级别的序列化解析极易出错且降低了安全存取的意义。
- **日志敏感信息泄漏**：`LogRepository.kt` 将所有入参内容以明文直接存入持久化 SharedPreferences 中，存在严重的合规风险（可能持久化保存敏感通知的标题和正文）。
- **Foreground Service API 兼容性崩溃**：在 `BleForegroundService.kt` 中直接用三参数形式的 `startForeground` 并在较低版本传服务类型。不仅对于 Android 14+ 前台类型申请不够完善（缺少权控校验），三参数 `startForeground` API 在低版本系统上也可能导致崩溃，应使用 `ServiceCompat.startForeground`。
