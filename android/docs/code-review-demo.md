# Demo 模块代码审查报告

根据 code-review-excellence 规范，对 `android/demo` 模块的代码进行了深度审查，结果如下：

## 1. Demo 架构与 SDK 调起规范
- **生命周期管理**：在 `MainActivity.onCreate` 中调用了 `BleNotificationSDK.init(this)`，但在页面销毁时（如 `onDestroy`）没有对应的资源释放机制（如停止扫描任务、解除 SDK 内部监听）。`doScanOnly` 中使用了 `Handler` 的延时任务，在 Activity 销毁时未执行 `removeCallbacks`，存在内存泄漏和空指针风险。
- **SDK 实例获取**：SDK 初始化及调用依赖于 `init` 的返回值，若 `init` 每次返回新对象会有逻辑漏洞，需确保 SDK 内部为单例模式设计。

## 2. 蓝牙与定位权限申请流程
- **两段式权限申请**：在 `onCreate` 中针对 API 33+ 申请 `POST_NOTIFICATIONS` 权限时，直接调用了老旧的 `requestPermissions`，且没有在申请前判断是否已授权或提供合理的 Rationale（权限申请原因说明）。建议迁移到 Activity Result API (`registerForActivityResult`)，实现更现代的两段式权限请求。
- **定位总开关与 onResume 校验**：`onResume` 中调用了 `sdk.ensurePermissions(this)` 进行幂等校验，但当系统的位置服务总开关（GPS）关闭时，Demo 侧缺少友好的 UI 引导用户前往设置开启，体验闭环不完整。

## 3. UI 交互与主题匹配
- **主题色兼容性**：`activity_main.xml` 中的日志展示区域 (`@+id/tv_log`) 父布局使用了硬编码的背景色 `#F0F0F0`。该颜色无法兼容深色模式（Dark Mode），在夜间模式下会显得过于突兀。建议使用 `?attr/colorSurfaceVariant` 等系统/主题属性。
- **多语言与硬编码**：UI 中的大量文本（如 "PC 设备管理 (加载中…)"、"扫描 BLE 设备 (10秒)"等）以及代码中的 Toast 提示未提取到 `strings.xml` 中，不利于国际化和长期维护。

## 4. 日志记录与展示
- **敏感 MAC 地址泄漏**：在 `startPairing` 方法的回调中，`onQrResult` 和后续连接流程直接执行了 `log("QR: mac=$mac uuid=$uuid")` 与 `log("连接 $mac …")`，这会将用户的设备 MAC 地址明文输出到 UI 及缓存文件中。应增加 MAC 地址脱敏处理（如掩码处理 `XX:XX:**:**:XX:XX`）。

## 5. 代码质量与可维护性
- **过时的系统 API**：`doScanOnly` 获取蓝牙适配器使用了 `BluetoothAdapter.getDefaultAdapter()`，该方法在 API 31+ 中已废弃，推荐改用 `getSystemService(BluetoothManager::class.java).adapter`。
- **异常防护缺失**：在请求精确闹钟权限跳转系统设置时 `startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))`，未进行 `try-catch` 异常捕获处理。部分定制系统可能阉割了该 Intent 从而引发 `ActivityNotFoundException` 导致崩溃。
- **View 组件安全**：按键状态更新与异步延时任务（10 秒的蓝牙扫描等）直接操作 UI，缺乏 `isFinishing` 或 `isDestroyed` 校验。
