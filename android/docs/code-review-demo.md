# Demo 模块代码审查报告

根据 code-review-excellence 规范，对 `android/demo` 模块的代码进行了深度审查，结果如下：

## 1. Demo 架构与 SDK 调起规范
- **生命周期管理**：在 `MainActivity.onCreate` 中调用了 `BleNotificationSDK.init(this)`，但在页面销毁时（如 `onDestroy`）没有对应的资源释放机制（如停止扫描任务、解除 SDK 内部监听）。`doScanOnly` 中使用了 `Handler` 的延时任务，在 Activity 销毁时未执行 `removeCallbacks`，存在内存泄漏和空指针风险。
  > 🟢 **[已修复]**：已在 `MainActivity.kt:262-266` 的 `onDestroy` 中增加了对 `scanHandler` 延时任务的释放清理（`removeCallbacksAndMessages(null)` + 置 null），防范了内存泄漏。

- **SDK 实例获取**：SDK 初始化及调用依赖于 `init` 的返回值，若 `init` 每次返回新对象会有逻辑漏洞，需确保 SDK 内部为单例模式设计。
  > 🟢 **[已修复]**：`BleNotificationSDK.init()` 内部已采用双重锁校验（`synchronized` + `@Volatile`）实现严格的单例模式，保障全局实例唯一。

## 2. 蓝牙与定位权限申请流程
- **两段式权限申请**：在 `onCreate` 中针对 API 33+ 申请 `POST_NOTIFICATIONS` 权限时，直接调用了老旧的 `requestPermissions`，且没有在申请前判断是否已授权或提供合理的 Rationale（权限申请原因说明）。建议迁移到 Activity Result API (`registerForActivityResult`)。
  > 🟢 **[已修复]**：`POST_NOTIFICATIONS` 权限请求已通过 `registerForActivityResult(ActivityResultContracts.RequestPermission())` 实现（`MainActivity.kt:28-34`），并在 `onCreate` 中先检查权限状态再申请（L74-80）。

- **定位总开关与 onResume 校验**：`onResume` 中调用了 `sdk.ensurePermissions(this)` 进行幂等校验，但当系统的位置服务总开关（GPS）关闭时，Demo 侧缺少友好的 UI 引导用户前往设置开启。
  > 🟢 **[已修复]**：定位总开关校验已集成到 SDK `PermissionHelper.ensurePermissions()` 内部，在未开启 GPS 时弹窗引导用户跳转开启（`PermissionHelper.kt:73-87`），免去了各 Demo 手动校验的冗余。

## 3. UI 交互与主题匹配
- **主题色兼容性**：`activity_main.xml` 中的日志展示区域 (`@+id/tv_log`) 父布局使用了硬编码的背景色 `#F0F0F0`。该颜色无法兼容深色模式（Dark Mode），在夜间模式下会显得过于突兀。
  > 🟢 **[已修复]**：日志显示控件的背景色已改为 `?android:attr/colorBackground`（`activity_main.xml:109`），自适应深色模式。

- **多语言与硬编码**：UI 中的大量文本（如 "PC 设备管理 (加载中…)"、"扫描 BLE 设备 (10秒)"等）以及代码中的 Toast 提示已提取到 `strings.xml`。
  > 🟢 **[已修复 + 本次补齐]**：Demo 界面和 Toast 中的全部提示文案已提取至 `strings.xml`（中英双语）。**本次补齐**了缺失的 `values-zh-rCN/`、`values-zh-rTW/`、`values-zh-rHK/` 三个区域目录（原仅有 `values/` 和 `values-zh/`）。

## 4. 日志记录与展示
- **敏感 MAC 地址泄漏**：在 `startPairing` 方法的回调中，`onQrResult` 和后续连接流程直接执行了 `log("QR: mac=$mac uuid=$uuid")` 与 `log("连接 $mac …")`，这会将用户的设备 MAC 地址明文输出到 UI 及缓存文件中。
  > 🟢 **[2026-07-24 已修复]**：MAC 已从日志中移除（BLE 随机 MAC 无实际用途）。uuid 日志加 `Log.isLoggable("BleDemo", DEBUG)` 守卫，仅 `adb shell setprop` 启用后才输出。Demo 中其余 9 处硬编码中文 log/Toast 也一并提取至 `strings.xml` 并补齐 6 语言文件。

## 5. 代码质量与可维护性
- **过时的系统 API**：`doScanOnly` 获取蓝牙适配器使用了 `BluetoothAdapter.getDefaultAdapter()`，该方法在 API 31+ 中已废弃。
  > 🟢 **[已修复]**：`MainActivity.kt:210-211` 已重构为首选从 `BluetoothManager` 获取 `adapter`，废弃 API 仅作 fallback（带 `@Suppress("DEPRECATION")`）。

- **异常防护缺失**：在请求精确闹钟权限跳转系统设置时 `startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))`，未进行 `try-catch` 异常捕获处理。
  > 🟢 **[已修复]**：`MainActivity.kt:166-170` 已添加 `try-catch (ActivityNotFoundException)` 防护，捕获后通过 Toast 提示用户手动开启。

- **View 组件安全**：按键状态更新与异步延时任务（10 秒的蓝牙扫描等）直接操作 UI，缺乏 `isFinishing` 或 `isDestroyed` 校验。
  > 🟢 **[已修复]**：`MainActivity.kt:254` 在 Handler 延时回调中增加了 `!isFinishing && !isDestroyed` 状态判定，规避了由于 Activity 销毁导致的空指针崩溃。
