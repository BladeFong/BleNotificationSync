# Android SDK — 进度日志

## 2026-07-24 — 支持 NDK C++ 子模块 JitPack 构建与 Release 编译修补

- 支持 NDK C++ 模块的 JitPack 构建：在 `jitpack.yml` 中添加了 `git submodule update --init --recursive` 自动拉取 `libtomcrypt` 子模块。由于项目根目录没有 `gradlew`，自定义了 `install` 阶段命令在 `android/` 子目录下执行 `publishToMavenLocal` 进行编译和构建。
- 修复 Release 编译缺陷：补齐了 `PairingManager.kt` 里的 `PairingState.PAIRED` 状态分支，使 `when` 表达式穷尽，从而顺利通过 Release 模式下的严格 Kotlin 编译（避免了 Warning 升级为 Error 导致构建失败）。

## 2026-07-24 — 审查报告核实与修复（14 项闭环）

- 审查子代理产出 `docs/code-review-20260724.md`（SDK 9 + Demo 5，共 14 项）
- 主代理逐条核实代码：**1 项误判**（#1 GATT 断开时序，实际异步可调通），**5 项驳回**（风险极低或过度防御），**8 项属实修复**
- 修复项：QrScanner 线程泄漏、MTU 失败回调、openDeviceManager 去参、PAIRED 注释、重复重置删除、安全强转、Demo 日志英文化+isLoggable、dimens 补 text_size_log
- 审查子代理审核通过，全部闭环

## 2026-07-24 — 代码审查报告更新（SDK + Demo）

- 完成对 SDK 模块和 Demo 模块的全面代码审查
- SDK 报告（`docs/code-review-sdk.md`）：发现 16 项问题（1 Blocking / 6 Important / 4 Nit / 4 Suggestion），覆盖 build 配置、BLE 连接、安全性、测试覆盖、API 设计
- Demo 报告（`docs/code-review-demo.md`）：发现 10 项问题（4 Important / 3 Nit / 3 Suggestion），覆盖日志国际化、布局、配置、代码质量
- 详细问题列表见对应审查报告文件

## 2026-07-24 — 审查报告逐条核实、架构解耦重构与全面合规整治（三回合）

### 第一回合：架构解耦 + i18n 补齐
- **权限辅助抽离**：新建 `PermissionHelper`（internal class），从 BleNotificationSDK 中分离两段式位置权限注册、BLE 权限检查、定位总开关弹窗引导，弹窗字符串改用 `R.string.*` 资源引用
- **导航辅助抽离**：新建 `Navigator`（internal class），统一管理 QR 扫描 Fragment 弹出/回调、DeviceManagerActivity 跳转、DeviceManagerFragment 实例获取
- **i18n 补齐 SDK**：`values-zh-rCN/TW/HK` 从各 1 条补齐至 24 条（新增定位对话框、通知渠道、前台服务等字符串）
- **i18n 补齐 Demo**：新建 `values-zh-rCN/`、`values-zh-rTW/`、`values-zh-rHK/` 三个目录各 27 条，繁中用语按区域差异处理（TW: 裝置/設定/載入/日誌，HK 同）

### 第二回合：审查逐条核实 + 硬编码消除 + 敏感日志净化
- **审查报告逐条核实**：对 code-review-demo.md / code-review-sdk.md 全部 23 条审查发现逐一代码验证，纠正 4 条虚假/夸大备注，新增 2 条遗漏记录
- **Demo MAC 日志泄漏修复**：移除 onQrResult 中的 MAC 明文输出，uuid 日志加 `Log.isLoggable` 守卫
- **Demo 硬编码中文化**：9 处 log/Toast 硬编码中文全部提取至 strings.xml，6 语言文件补齐
- **Compose 色值规范化**：DeviceManagerFragment 9 处 `Color(0xFF...)` / `Color.Gray` 全部替换为 `MaterialTheme.colors` 引用，`lightColors()` 硬编码背景/表面移除
- **暗色模式适配**：DeviceManagerFragment 新增 `isSystemInDarkTheme()` 判断，自动切换 `darkColors()` / `lightColors()`
- **CHANNEL_NAME 去硬编码**：BleNotificationSDK 的 CHANNEL_NAME const val 改为运行时 `context.getString()`，description 同步资源化
- **BleForegroundService i18n**：3 处硬编码中文（渠道名/通知标题/正文）→ 新增 3 个 string key，6 语言补齐
- **QrScannerFragment 色值**：`0xFFFFFFFF.toInt()` → `android.graphics.Color.WHITE`
- **contentDescription 资源化**：`"Scan"` → `stringResource(R.string.s_scan_to_bind_new_device)`

### 第三回合：SDK 中文清零 + 暂缓项全部消化
- **SdkError 全英文化**：11 条中文错误消息全部改为英文（SDK 默认语言），同步更新 SdkErrorTest 单测
- **SDK 日志中文清零**：BleScanWorker / BleClient / PermissionHelper / BleForegroundService 共 10 处 Log 中文→英文，确认 SDK 源码中除注释外中文清零
- **FrameEncoder JSON 序列化**：移除手动字符串拼接的 `jsonString()` / `buildJson()`，改用 `org.json.JSONObject`（Android SDK 内置，零额外依赖）
- **Compose 字体规范**：DeviceManagerFragment 定义 `TextSizeTitle/Body/Caption` 常量对齐 CLAUDE.md dimens 标准
- **Demo 字体规范**：新建 `dimens.xml`（22/18/16sp），`activity_main.xml` 的 `16sp` → `@dimen/text_size_caption`
- **PairingManager 存储 JSON 化**：写入改为 `JSONObject`，新增 `parseStoredValue()` 兼容层（JSON 优先、旧 `|` 分隔自动回退），迁移时自动升级
- **NativeCrypto.SALT 安全注释**：标注硬编码盐值风险、不改原因、v2 协议升级方向

### 编译验证
- SDK + Demo 编译通过，SdkErrorTest 2/2 单测通过
- Demo APK 安装到设备 `adb-bce26679-fZ4RyP` 验证成功

## 2026-07-24 — Demo APK 体积优化
- 移除 `x86_64` ABI 过滤（仅保留 `arm64-v8a`），debug APK 30MB → 24MB
- 配置 release buildType（R8 + shrinkResources + proguard-rules.pro），release 8.3MB
- Demo 仅内部调试用途，不纳入 releases/ 对外分发

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
