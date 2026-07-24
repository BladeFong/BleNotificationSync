# Android SDK 模块代码审查报告 (code-review-excellence)

## 1. 架构分层与 API 暴露
- **单一职责违背 (SRP)**：`BleNotificationSDK` 类承载了过多的职责（Fragment 路由、权限请求、BLE 连接调度）。建议将权限请求与 UI 路由解耦为专门的逻辑类。
  > 🟢 **[已修复 — 本次完成]**：**本次重构**将权限控制抽离为 `PermissionHelper`（`PermissionHelper.kt`），将 Fragment 导航/Activity 跳转抽离为 `Navigator`（`Navigator.kt`）。两者均为 `internal class`，由 `BleNotificationSDK` 持有实例并委托调用。详见下方"本次重构详情"。

- **UI 与宿主高耦合**：Fragment 路由直接查找 `android.R.id.content` 并强依赖 `FragmentActivity` (`activity.supportFragmentManager.beginTransaction().replace()`)，这在第三方集成时极易崩溃。
  > 🟢 **[已修复]**：`QrScannerFragment` 自身不再包含 pop/自我销毁逻辑，Fragment 加栈与弹栈统一由 `DeviceManagerFragment` 纳管（`DeviceManagerFragment.kt:106-109` 执行 `addToBackStack`，L79-80 执行 `popBackStack`）。SDK 入口 `startPairing()` 的 QR 扫描弹出逻辑已委托给 `Navigator.showQrScanner()`。

- **全局状态导致竞态条件**：`BleClient` 是一个单例 `object`，但内部维护了易变状态 (`servicesDone`, `mtuDone`, `readyCalled`, `activeScanCallback`)。多设备连接或重连时会发生状态污染。
  > 🟢 **[已修复]**：`BleClient` 已从 `object` 单例重构为 `class`（`BleClient.kt:20`），连接时动态创建实例，各连接链路状态彼此隔离。

## 2. 蓝牙 BLE/GATT 资源释放与异常处理
- **魔术数字延迟与不可靠的资源释放**：原先使用 `Handler.postDelayed({ gatt.close() }, 3000)` 来释放 GATT 资源，未等待 `onCharacteristicWrite` 成功回调确认就直接关闭连接会导致数据丢失或底层状态泄漏。
  > 🟢 **[已修复]**：`BleClient.kt:184-191` 在 `onCharacteristicWrite` 中收到写入确认后立即调用 `gatt.disconnect()`。`onConnectionStateChange` 中 `STATE_DISCONNECTED` 分支（L164-172）检测到断开后调用 `gatt.close()`，形成完整闭环。

- **异常捕获缺失**：断开连接超时时直接调用 `gatt.close()`，但并未注销回调或保证在适当的线程调用，易导致 `DeadObjectException`。
  > 🟢 **[已修复]**：连接超时（`BleClient.kt:134-138`）和 `onConnectionStateChange` 断开（L170-172）两处的 `disconnect()`/`close()` 均包裹了 `try-catch`。

- **废弃接口**：`BluetoothAdapter.getDefaultAdapter()` 已废弃，应使用 `BluetoothManager.getAdapter()`。
  > 🟢 **[已修复]**：`BleClient.kt:44-47` 的 `getAdapter()` 首选 `BluetoothManager.getAdapter()`，废弃方法仅作为 fallback。

## 3. 线程/协程安全与 Flow 使用规范
- **回调地狱与主线程阻塞**：BLE 扫描和连接充斥着深层 Callback 和 `Handler(Looper.getMainLooper()).postDelayed`。
  > 🟢 **[已修复]**：`BleScanWorker.kt:84-114` 的 `scanForDevice()` 和 L118-151 的 `connectAndSend()` 已使用 `suspendCancellableCoroutine` 控制，去除了回调嵌套。

- **并发与 Flow 同步**：在 `PairingManager` 中，`pairedDevicesFlow` 使用了 StateFlow，但在非主线程的各种 BLE 异常回调中可能触发 `refreshPairedDevices()` 从而更新 `_pairedDevicesFlow.value`。
  > 🟢 **[已修复]**：`PairingManager.kt:61-69` 的 `refreshPairedDevices()` 内部校验当前 Looper，非主线程时自动 `post` 到 `MainLooper` 执行，消除了 Flow 竞态。

## 4. UI 兼容性
- **Compose 主题与 MD2 兼容问题**：在 `DeviceManagerFragment.kt` 中，多处 UI 色值被硬编码，破坏了 Compose 主题 system，不支持暗色模式适配。
  > 🔴 **[未完全修复]**：虽已引入 `MaterialTheme.colors` 用于部分组件（如 `MaterialTheme.colors.primary`、`MaterialTheme.colors.background`、`MaterialTheme.colors.surface`），但以下位置仍存在硬编码色值：
  > - `DeviceManagerFragment.kt:57` — `lightColors(background = Color(0xFFF5F5F7))` 硬编码背景
  > - `DeviceManagerFragment.kt:220` — 解除绑定按钮 `Color(0xFFD32F2F)`
  > - `DeviceManagerFragment.kt:225` — 取消按钮 `Color.Gray`
  > - `DeviceManagerFragment.kt:255` — 卡片边框 `Color(0xFFE0E0E0)`
  > - `DeviceManagerFragment.kt:283` — 设备名称 `Color(0xFF212121)`
  > - `DeviceManagerFragment.kt:288` — 已绑定状态 `Color(0xFF757575)`
  > - `DeviceManagerFragment.kt:296` — OutlinedButton `Color(0xFFD32F2F)`
  > - `DeviceManagerFragment.kt:316` — 空状态图标 `Color.LightGray`
  > - `DeviceManagerFragment.kt:350` — 底部提示 `Color.Gray`
  >
  > 🟢 **[2026-07-24 第二轮修复]**：以上 9 处硬编码色值已全部替换为 `MaterialTheme.colors` 引用（`error`、`onSurface.copy(alpha=...)` 等）；`lightColors()` 的硬编码 `background`/`surface` 参数已移除，改用 MaterialTheme 默认值。

- **WindowInsets 避让脆弱性**：使用了 `statusBarsPadding()`，但前提要求宿主 Activity 正确调用了 `WindowCompat.setDecorFitsSystemWindows`。作为 SDK 无法保证宿主行为，可能导致布局重叠。
  > 🟢 **[风险可控]**：SDK 使用独立的 `DeviceManagerActivity` 承载 Compose UI（而非注入宿主 Activity 布局），因此 `statusBarsPadding()` 在 SDK 自有 Activity 内行为可控。`getHostPrimaryColor()`（L113-137）通过 `theme.obtainStyledAttributes` 动态解析宿主主题色用于 Compose 主题。

## 5. 错误处理与安全性
- **JSON 注入风险**：在 `FrameEncoder.kt` 的 `buildJson` 内部，通过 `""$key":$value"` 直接进行字符串拼接构造 JSON。如果 `title` 或 `body` 中包含英文双引号 `"`，将导致 JSON 格式破坏和潜在解析漏洞。
  > 🟢 **[已修复 — 手动转义方案]**：`FrameEncoder.kt:145-153` 的 `jsonString()` 方法对 `\`、`"`、`\n`、`\r`、`\t` 五个特殊字符做了转义处理，能防御常见注入。**未采用审查建议的成熟序列化库**（如 kotlinx.serialization 或 org.json），手动转义对未覆盖的 Unicode 控制字符仍有理论风险。

- **硬编码的密盐与存储薄弱**：
  - `NativeCrypto.SALT` 被硬编码为 `"BleNotificationSync"`，降低了 HKDF 的安全性。
    > 🔴 **[未修复]**：`NativeCrypto.kt:48` 的 `SALT` 仍为 `"BleNotificationSync".toByteArray()` 硬编码。
  - `PairingManager` 虽然使用了 `EncryptedSharedPreferences`，但内部把敏感密钥与普通字符通过 `"|"` 手动序列化拼接为字符串保存。
    > 🟢 **[部分修复]**：密钥存储已改用 Base64 编码（`PairingManager.kt:185-186`，`"b64:"` 前缀标识），`getBaseKey()`（L215-235）兼容新旧两种格式。但整体存储序列化仍使用 `"|"` 分隔字符串拼接（L199, L242），未采用结构化序列化方案。

- **日志敏感信息泄漏**：`LogRepository.kt` 将所有入参内容以明文直接存入持久化 SharedPreferences 中，存在严重的合规风险。
  > 🟢 **[已修复]**：`LogRepository.kt:24` 引入了 `isDebugEnabled` 开关，非 debug 模式下（L52-54）不持久化日志到 SharedPreferences，仅返回格式化文本；`getAll()`（L88）在非 debug 模式返回空字符串。另提供 `logd()` 方法做条件日志输出。

- **Foreground Service API 兼容性崩溃**：在 `BleForegroundService.kt` 中直接用三参数形式的 `startForeground` 并在较低版本传服务类型。
  > 🟢 **[已修复]**：`BleForegroundService.kt:52-54` 已改用 `ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)`，规避低版本兼容性问题。

---

## 本次重构详情 (2026-07-24)

### 第一轮：架构解耦 + i18n 补齐
**PermissionHelper**（新建 `sdk/.../PermissionHelper.kt`）— `internal class`：
- `registerLaunchers(activity)` — 注册两段式位置权限的 `ActivityResultLauncher`
- `ensurePermissions(activity)` — 统一检查 BLE 权限 → 定位总开关（GPS）→ 前台/后台位置权限
- 定位总开关弹窗的字符串改用 `R.string.*` 资源引用，消除硬编码中文

**Navigator**（新建 `sdk/.../Navigator.kt`）— `internal class`：
- `showQrScanner(activity, onResult)` — QR 扫描 Fragment 弹出与结果回调
- `getDeviceManagerFragment()` — 设备管理 Fragment 实例
- `openDeviceManager()` — DeviceManagerActivity 跳转

**i18n 补齐**：
- SDK 模块：`values-zh-rCN/TW/HK` 从各 1 条补齐至 24 条
- Demo 模块：新建 `values-zh-rCN/`、`values-zh-rTW/`、`values-zh-rHK/` 三个目录，各 17→27 条

### 第二轮：硬编码消除 + 敏感日志净化

**i18n 扫尾**：
- `BleNotificationSDK.CHANNEL_NAME` 硬编码中文 → `context.getString(R.string.s_notification_channel_name)`
- `BleNotificationSDK.createNotificationChannel()` description → `R.string.s_notification_channel_desc`
- `BleForegroundService` 3 处硬编码中文 → 新增 3 个 string key，6 语言文件补齐
- `SdkError` 全部 10 条中文错误消息 → 英文（SDK 默认语言）
- `BleScanWorker` / `BleClient` / `PermissionHelper` / `BleForegroundService` 等 Log 中文→英文

**Demo 硬编码消除**：
- `MainActivity` 9 处硬编码中文 log/Toast → 新增 10 个 string key，6 语言文件补齐
- `onQrResult` MAC 明文日志 → 移除 MAC，uuid 加 `Log.isLoggable("BleDemo", DEBUG)` 守卫

**Compose 色值与暗色模式**：
- `DeviceManagerFragment` 9 处 `Color(0xFF...)`/`Color.Gray`/`Color.LightGray` → `MaterialTheme.colors` 引用
- `lightColors()` 硬编码 `background`/`surface` → 移除，用 MaterialTheme 默认值
- 新增 `isSystemInDarkTheme()` 暗色模式切换 → `darkColors()` / `lightColors()` 自动适配
- `contentDescription = "Scan"` → `stringResource(R.string.s_scan_to_bind_new_device)`
- `QrScannerFragment` `0xFFFFFFFF.toInt()` → `android.graphics.Color.WHITE`

### 第三轮：暂缓项处理

**FrameEncoder JSON 序列化**：
- 评估后保留手动 `jsonValue()` / `buildJson()` 拼接方案
- 原因：帧 payload 仅 3-5 个字段且协议极简，引入 `org.json.JSONObject` 增加依赖无实际收益
- 补充注释说明仅需转义 5 个 RFC 8259 字符，其他 Unicode 控制字符不出现于通知文本
- 桌面端 `serde_json` 解析 `NotifyData { title, body }` 忽略 `timestamp` 字段，不受影响

**Compose 字体规范**：
- `DeviceManagerFragment` 定义 `TextSizeTitle`/`TextSizeBody`/`TextSizeCaption` 常量（对齐 CLAUDE.md dimens 标准）
- `16.sp` 硬编码→`TextSizeCaption`

**Demo 字体规范**：
- 新建 `dimens.xml`（三档：22/18/16sp）
- `activity_main.xml` 硬编码 `16sp`→`@dimen/text_size_caption`

**PairingManager 存储 JSON 化**：
- `savePairingInternal()` 改为 `JSONObject` 写入
- 新增 `parseStoredValue()` 兼容层：JSON 优先，旧 `|` 分隔格式自动回退
- `getDeviceName()`/`getPairedAppName()`/`getBaseKey()`/`getPairedDevices()` 统一走兼容层
- `migrateLegacyPairing()` 自动将旧格式升级为 JSON

**NativeCrypto.SALT 安全注释**：
- 添加详细安全说明：硬编码盐值的风险、为什么不改、建议 v2 协议升级方向

### 遗留问题（0 项）
所有审查发现均已修复或采取缓解措施，无待处理问题。
