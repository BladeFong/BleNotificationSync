# Demo App — 进度日志

## 2026-07-15 — 设计与实现

- **Status:** complete ✅
- 设计文档：`demo/DESIGN.md`
- 实现计划：`demo/PLAN.md`（7 Tasks）
- 图标：矢量勾选方框（ic_todo.xml）+ adaptive icon

### Task 1: Settings + Build 配置
- **Status:** ✅ done

### Task 2: AndroidManifest.xml
- **Status:** ✅ done

### Task 3: 图标 VectorDrawable + Adaptive Icon
- **Status:** ✅ done

### Task 4: 字符串资源
- **Status:** ✅ done

### Task 5: Layout activity_main.xml
- **Status:** ✅ done

### Task 6: MainActivity.kt
- **Status:** ✅ done

### Task 7: 全量编译验证
- **Status:** ✅ done
- 编译：BUILD SUCCESSFUL (:sdk + :demo)

### 额外补充
- SDK 补全：ReminderScheduler + AlarmReceiver + setReminder/cancelReminder + ReminderCallback（之前被 revert 丢失）
- SDK Manifest 补全：BLE 权限 + POST_NOTIFICATIONS + AlarmReceiver 注册
- SDK 补全：QrScanner/QrScannerFragment CameraX 实现（之前被 revert）+ SDK res 资源文件

### 真机验证调试
- ActionBar 兼容 edge-to-edge：NoActionBar + FrameLayout 外层容器 + inset padding + enableEdgeToEdge，标题正常显示
- 扫码：CameraX + ML Kit 二维码扫描正常
- 编译：BUILD SUCCESSFUL（:sdk + :demo）

### 联调修复
- random 编码 base64→hex（对齐桌面 hex::decode）
- BLE 扫描加定位权限（部分厂商 API 31+ 仍需定位）
- 保存实际连接 MAC（gatt.device.address）而非 QR MAC
- 直连优先 + 失败自动扫描 UUID 策略——电脑重启 MAC 变更不需要重新绑定
- AlarmManager 权限跳转 + POST_NOTIFICATIONS 运行时权限
- BLE 回调线程安全（runOnUiThread）
- PairingManager 状态重置修复
