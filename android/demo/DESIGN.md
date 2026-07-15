# Demo App 设计

## 目的

集成 `:sdk` 的测试用 App，验证扫码绑定 / 解除绑定 / 闹钟通知流程。

## 模块

`android/demo/` — `com.ble.notification.demo`，`com.android.application`，依赖 `project(":sdk")`。

## UI

单 Activity (`MainActivity`)，纵向 LinearLayout：

```
┌─ [✓] BleNotificationSync Demo ───┐
│                                    │
│  [扫描绑定]    (isPaired → 置灰)   │
│  [解除绑定]    (!isPaired → 置灰)  │
│                                    │
│  ┌─────────────────────────────┐   │
│  │ 默认文本: "测试通知消息"      │   │
│  └─────────────────────────────┘   │
│                                    │
│  [发送提醒 (10秒后)]               │
│  ── setReminder → finish() ──     │
└────────────────────────────────────┘
```

## 图标

`res/drawable/ic_todo.xml` — XML VectorDrawable：圆角方框 + 内部勾选标记（✓），主题色填充。

`mipmap-anydpi-v26/ic_launcher.xml` — 引用 `ic_todo` 作为 adaptive icon foreground。

## 流程

1. `onCreate` → `BleNotificationSDK.init(this)` → `isPaired()` 更新按钮状态
2. 扫描绑定 → `startPairing(this, "BleNotifyDemo")` → onPaired → 更新按钮
3. 解除绑定 → `unpair(packageName)` → 更新按钮
4. 发送 → `setReminder(id, title, body, System.currentTimeMillis() + 10_000)` → `finish()`

## 文件

| 文件 | 说明 |
|------|------|
| `demo/build.gradle.kts` | Android application 插件，compileSdk 36，minSdk 23，依赖 `:sdk` |
| `demo/src/main/AndroidManifest.xml` | MainActivity 声明，LAUNCHER intent-filter |
| `demo/src/main/java/com/ble/notification/demo/MainActivity.kt` | 全部 UI 逻辑 |
| `demo/src/main/res/layout/activity_main.xml` | LinearLayout + 2 Button + EditText + Button |
| `demo/src/main/res/values/strings.xml` | 字符串资源 |
| `demo/src/main/res/values/colors.xml` | 主题色 |
| `demo/src/main/res/drawable/ic_todo.xml` | 矢量勾选图标 |
| `demo/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | adaptive icon |
| `settings.gradle.kts` | 加 `include(":demo")` |
