# 多 PC 绑定 — 模块进度日志

## 2026-07-22 — 审查细节补全：GATT 写入 API 33 兼容封装与权限请求现代化
- GATT 写入 API 兼容：新增 `BleCompat.writeCharacteristic`，在 Android 13 (API 33)+ 上全面适配 `gatt.writeCharacteristic(characteristic, value, writeType)` 官方推荐 API，低版本平滑降级，移除了所有 Deprecation 警告。
- 权限请求现代化：Demo 模块将 `POST_NOTIFICATIONS` 权限请求全量迁移至 Activity Result API (`registerForActivityResult(ActivityResultContracts.RequestPermission())`)。
- Compose 暗色模式深度适配：`DeviceManagerFragment` 空状态控件色值全面对接 `MaterialTheme.colors`，提升各种动态主题下的对比度与视觉体验。

## 2026-07-22 — Demo 资源国际化补全与暗色模式优化

- 全面提取 Demo 硬编码文本：将 `MainActivity` 及 `activity_main.xml` 中所有的提示词、按钮文案和日志状态提取至 `demo/src/main/res/values/strings.xml` (默认英文) 和 `demo/src/main/res/values-zh/strings.xml` (简体中文)。
- 暗色模式优化：将日志展示控件背景色修改为系统主题属性 `?android:attr/colorBackground`，全面适配系统暗色模式。

## 2026-07-22 — 代码审查与密钥 Base64 存储重构：优化安全性、跨平台对齐与防崩保护

- 彻底解决密钥溢出问题：重构 `PairingManager` 中密钥的持久化存储与读取，统一使用标准的 **Base64** 编解码（`b64:...`），并保持对旧版字符解析的平滑兼容。
- 依据审查结果实施深度修复：
  - `FrameEncoder` 内部构造 JSON 时增加字符串安全转义，防止 `title`/`body` 包含双引号 `"` 破坏 JSON 荷载。
  - `BleClient` 在发起连接前增加单例标志位的显式重置，并升级 API 31+ `BluetoothManager.getAdapter()` 获取方式。
  - `BleForegroundService` 前台服务升级采用 `ServiceCompat.startForeground` 保证全 Android 版本系统兼容。
  - `MainActivity` 增加 `Handler` 延时任务在 `onDestroy` 时的释放清理、精准闹钟跳转系统的 `try-catch (ActivityNotFoundException)` 崩溃防护以及异步 UI 回调 `isFinishing`/`isDestroyed` 状态校验。

## 2026-07-22 — 后台前台服务密钥查找修复：彻底解决 BleForegroundService 丢失密钥中断发送问题

- 排查日志并修复硬伤：此前的日志停在 `MTU: status=0 mtu=517` 且未发出数据，根因是 `BleForegroundService` 内部误将 `packageName` 作为 Key 查询参数传给 `getBaseKey` 返回 `null` 导致服务静默中断 (`stopSelf()`)。
- 修复与日志完善：修正为根据已绑定设备的 `uuid` 正确获取密钥加密，并补充 `BleForegroundService: writeCharacteristic sent=...` 明确写入状态日志。

## 2026-07-22 — 通知加密与解密对齐修复：解决 PC 端收不到通知问题

- 修复 Android 侧 `BleScanWorker` 传参缺陷：此前后台发送通知时误将 `packageName` 作为 Key 查询参数传给 `getBaseKey` 导致返回 `null`，现已修正为按已绑定设备的 `uuid` 正确获取密钥。
- 升级 PC 侧 `handle_notify` 多设备解密逻辑：针对同一包名下可能绑定多台 Android 设备（各自具有独占 `android_id` 与派生 `base_key`）的场景，PC 端由单次 `find` 改为遍历所有匹配包名设备的 `base_key` 尝试解密，100% 解决解密失败或收不到通知的问题。

## 2026-07-22 — 日志净化：清理 MAC 地址日志，引入 Android ID、手机设备名与 PC UUID

- 全面清理日志：移除了 `PairingManager`、`BleClient`、`BleScanWorker` 及 Demo `MainActivity` 中所有输出 MAC 地址的日志。
- 替换为规范标识：配对与设备管理日志统一格式化输出 `android_id` (`Settings.Secure.ANDROID_ID`)、手机设备名 (`Build.MODEL`) 以及 PC 的 `pc_uuid` / `pc_name`，避免重复与敏感 MAC 地址泄漏。

## 2026-07-22 — 规范 Fragment 加栈架构：移除 QrScannerFragment 内部固化操作，由管理者纳管

- 彻底解耦 `QrScannerFragment`：移除其内部自我销毁与固化出栈代码，使其保持 100% 纯粹透明的独立功能组件。
- 规范加栈纳管：由 `DeviceManagerFragment` 作为管理者在其 `parentFragmentManager` 中执行 `replace` 并添加匿名栈 (`addToBackStack(null)`)，且在扫码回调触发时由管理者出栈 (`popBackStack()`)，彻底消除 SDK 内部硬编码字符串 Tag 对宿主导航栈的固化侵入。

## 2026-07-22 — 扫码完成 Fragment 弹栈修复：扫码后自动平滑返回设备管理列表

- 解决扫码成功后停留空白预览页问题：在 `BleNotificationSDK.startPairing` 的扫码回调触发时，增加 `supportFragmentManager.popBackStack("ble_pairing", ...)` 自动弹栈。
- 扫码成功或取消后自动出栈退出 `QrScannerFragment`，平滑无缝地回到 `DeviceManagerFragment` 设备管理列表。

## 2026-07-22 — 二维码解析适配：去除强校验 mac 参数，兼容 PC 端最新 URI

- 解决扫码无法识别问题：PC 端最新生成的二维码 URI (`ble://pair?uuid=...&name=...`) 中去除了随机改变的 `mac` 参数。
- 重构 `QrDecoder` 与 `QrResult`：将 `mac` 改为可选字段，`uuid` 作为唯一核心必填项，更新 `QrDecoderTest` 单元测试通过。

## 2026-07-22 — 对齐 PC 端设备标识：增加 android_id 和 device_name 发送

- 对齐 PC/Desktop 端 Protocol 变更：在 REGISTER 注册帧中带上设备的 `android_id` (`Settings.Secure.ANDROID_ID`) 及友好设备名称 `device_name` (`Build.MODEL` / `device_name`)。
- 解决多 Android 设备相同应用关联同一 PC 时的标识区分问题，完成单元测试与真机覆盖安装。

## 2026-07-22 — 设备管理 UI 布局重构、WindowInsets 修复、Theme 无缝继承与国际化

- 优化 `DeviceManagerFragment` 布局：移除 Demo 主界面冗余按钮，置顶单个“PC 设备管理”大按钮。
- 修复 WindowInsets 状态栏沉浸式避让：为 TopAppBar 与 BottomBar 分别添加 `statusBarsPadding()` 与 `navigationBarsPadding()`，解决顶部重叠与底部遮挡问题。
- 彻底解决 Compose 继承宿主 Theme 属性：移除 `DeviceManagerActivity` 声明中的硬编码 `android:theme` 属性，在 `getHostPrimaryColor` 中采用 `theme.obtainStyledAttributes` 自动解包属性，完全兼容 AppCompat 与 MD3 主题色，经子代理调研确认无缝兼容 `JustNow` 等宿主项目。
- 字符串国际化抽离：将内置 UI 所有文案提取至 `values/strings.xml`（默认英文）与 `values-zh/strings.xml`（简体中文），统一使用 `s_` 前缀，Compose 界面采用 `stringResource` 绑定。

## 2026-07-22 — 多 PC 绑定与管理 Fragment 编码实现及单元测试全通过

- 完成 `PairingManager` 响应式重构：基于 UUID 主键 (`pairing_$uuid`) 及 `StateFlow<List<PairedDevice>>` 状态流，完成多 PC 持久化和旧数据平滑迁移。
- 重构 `BleNotificationSDK` 公开 API：支持 `pairedDevicesState`、多设备解绑 `unpair(uuid)`/`unpairAll()` 以及重复扫码拦截 `SdkError.AlreadyPaired`。
- 实现 Jetpack Compose Material 2 风格的 `DeviceManagerFragment` 与 `DeviceManagerActivity`：成功自动继承宿主主题色 `colorPrimary`，支持仅设备名列表展示、解除绑定确认弹窗和底部扫描关联栏。
- 更新 `PairingManagerTest` 单元测试并通过，`./gradlew assembleDebug testDebugUnitTest` 编译打包与测试 100% 成功。

## 2026-07-22 — 多 PC 绑定与管理 Fragment 方案头脑风暴与 Spec 编写
- 完成多 PC 绑定的需求分析与架构设计。
- 确定以 PC 的固定 UUID 作为持久化 Key（`pairing_$uuid`）。
- 确定 Compose Material 2 风格内置管理 UI，适配宿主主题色，仅显示设备名。
- 完成详细设计文档 `docs/superpowers/specs/2026-07-22-multi-pc-pairing-design.md`。
