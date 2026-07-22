# 多 PC 绑定 — 模块进度日志

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
