# 多 PC 绑定 — 模块进度日志

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
