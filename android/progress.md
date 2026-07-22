# Android SDK — 进度日志

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

## 2026-07-22 — 多 PC 绑定与管理 Fragment 方案头脑风暴与 Spec 编写

- 完成多 PC 绑定的需求分析与架构设计。
- 确定以 PC 的固定 UUID 作为持久化 Key（`pairing_$uuid`）。
- 确定 Compose Material 2 风格内置管理 UI，适配宿主主题色，仅显示设备名。
- 完成详细设计文档 `docs/superpowers/specs/2026-07-22-multi-pc-pairing-design.md`。

## 2026-07-15 — SDK API 改进实现

### 实现内容（7 Tasks，全部完成）
- **Task 1**: SdkError 密封类 — 11 种子类型
- **Task 2**: PairingManager SharedPreferences 持久化 + baseKey HKDF 派生与存储 + random 生成
- **Task 3**: BleClient 权限内聚 — `hasPermissions()`/`getMissingPermissions()`，移除 `@Suppress`
- **Task 4**: BleNotificationSDK — `close()`/`getPairedDevices()`/`unpair()`/`startPairing(appName)`
- **Task 5**: FrameEncoder.encodeRegister 加 `random` 参数(base64)
- **Task 6**: SdkErrorTest + 测试编译修复
- **Task 7**: 文档更新

### 编译与测试
- Kotlin main source: BUILD SUCCESSFUL
- Test source: BUILD SUCCESSFUL
- 纯 JVM 测试: SdkErrorTest(2) / PairingManagerTest(3) / QrDecoderTest(10) 全 PASS
- Android 依赖/Native 依赖测试: BleClientTest(5 fail) / FrameEncoder NOTIFY(3 fail) / FrameDecoder NOTIFY(1 fail) — JVM 无框架，预存

### 接口变更
| 变更 | 说明 |
|------|------|
| `SdkError` | 新增密封类，11 种子类型，替换所有 `String onError` |
| `PairingManager(Context)` | 构造加 Context，SharedPreferences 持久化 |
| `startPairing(activity, appName, callback)` | 新增 appName 参数 |
| `getPairedDevices(): List<PairedDevice>` | 新增 |
| `unpair(packageName)` | 新增 |
| `close()` | 新增，断开连接 + 取消回调 + 标记关闭 |
| `BleClient.hasPermissions(context)` | 新增静态方法 |
| `BleClient.getMissingPermissions(context)` | 新增静态方法 |
| `encodeRegister(appName, packageName, random)` | 新增 random 参数(base64) |
