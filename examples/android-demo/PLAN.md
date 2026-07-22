# Demo App Implementation Plan

> **For agentic workers:** 按 Task 顺序逐一实现。Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建 `:demo` Android application 模块，集成 `:sdk`，实现扫码绑定/解除绑定/10 秒闹钟通知的验证流程。

**Architecture:** 单 Activity + LinearLayout，Compose 不用。所有 UI 逻辑在 `MainActivity` 内，通过 `BleNotificationSDK` API 驱动。

**Tech Stack:** Kotlin, Android SDK (minSdk 23), XML layouts, VectorDrawable

## Global Constraints

- minSdk 23, compileSdk 36, AGP 9.1.1
- 模块名 `:demo`，包名 `com.ble.notification.demo`
- 依赖 `project(":sdk")`
- 使用 `s_` 前缀字符串 key
- 布局用 `res/layout/` XML，禁止代码硬编码布局
- 字符串必须资源化
- 字体用 `textAppearance` 或 dimen 资源
- 图标 XML VectorDrawable

---

### Task 1: Settings + Build 配置

**Files:**
- Modify: `settings.gradle.kts`
- Create: `demo/build.gradle.kts`

**Interfaces:**
- Produces: `:demo` 可被 Gradle 识别和编译

- [ ] **Step 1: settings.gradle.kts 加 `:demo`**

在 `settings.gradle.kts` 末尾 `include(":sdk")` 后加一行：

```kotlin
include(":demo")
```

- [ ] **Step 2: 创建 demo/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "com.ble.notification.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ble.notification.demo"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}
```

- [ ] **Step 3: 验证 sync**

```bash
./gradlew --no-daemon :demo:compileDebugKotlin
```

期望: BUILD FAILED（源文件未创建，Task 后续）

- [ ] **Step 4: 提交**

```bash
git add settings.gradle.kts demo/build.gradle.kts
git commit -m "feat(demo): 创建 :demo 模块 + settings/build.gradle 配置"
```

---

### Task 2: AndroidManifest.xml

**Files:**
- Create: `demo/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: Demo App 可被系统识别并启动

- [ ] **Step 1: 创建 AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/s_app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.Light.DarkActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 2: 提交**

```bash
git add demo/src/main/AndroidManifest.xml
git commit -m "feat(demo): 创建 AndroidManifest.xml — LAUNCHER MainActivity"
```

---

### Task 3: 图标 — ic_todo VectorDrawable + Adaptive Icon

**Files:**
- Create: `demo/src/main/res/drawable/ic_todo.xml`
- Create: `demo/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `demo/src/main/res/values/colors.xml`

**Interfaces:**
- Produces: 应用图标（勾选方框）

- [ ] **Step 1: 创建 colors.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="color_primary">#2196F3</color>
    <color name="color_primary_dark">#1976D2</color>
    <color name="color_accent">#FF9800</color>
    <color name="icon_background">#FFFFFF</color>
    <color name="icon_foreground">#2196F3</color>
</resources>
```

- [ ] **Step 2: 创建 ic_todo.xml — 勾选方框图标**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="24"
    android:viewportHeight="24">

    <!-- 圆角方框 -->
    <path
        android:fillColor="@color/icon_foreground"
        android:pathData="M6,2 L18,2 C20.2,2 22,3.8 22,6 L22,18 C22,20.2 20.2,22 18,22 L6,22 C3.8,22 2,20.2 2,18 L2,6 C2,3.8 3.8,2 6,2 Z" />

    <!-- 勾选标记 ✓ -->
    <path
        android:fillColor="@color/icon_background"
        android:pathData="M10,16.5 L6.5,13 L5,14.5 L10,19.5 L19,7 L17.5,5.5 Z" />
</vector>
```

- [ ] **Step 3: 创建 mipmap-anydpi-v26/ic_launcher.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_background"/>
    <foreground android:drawable="@drawable/ic_todo"/>
</adaptive-icon>
```

- [ ] **Step 4: 提交**

```bash
git add demo/src/main/res/drawable/ic_todo.xml \
        demo/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
        demo/src/main/res/values/colors.xml
git commit -m "feat(demo): 矢量 todo 图标 + adaptive icon + colors"
```

---

### Task 4: 字符串资源

**Files:**
- Create: `demo/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: 所有 UI 字符串引用

- [ ] **Step 1: 创建 strings.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="s_app_name">BLE 通知同步测试</string>
    <string name="s_scan_pair">扫描绑定</string>
    <string name="s_unpair">解除绑定</string>
    <string name="s_send_reminder">发送提醒 (10秒后)</string>
    <string name="s_default_text">测试通知消息</string>
</resources>
```

- [ ] **Step 2: 提交**

```bash
git add demo/src/main/res/values/strings.xml
git commit -m "feat(demo): 创建 strings.xml"
```

---

### Task 5: Layout — activity_main.xml

**Files:**
- Create: `demo/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Produces: `MainActivity` 的 `R.layout.activity_main` 布局引用
- 控件 id：`btn_scan_pair`, `btn_unpair`, `et_message`, `btn_send`

- [ ] **Step 1: 创建 activity_main.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center_horizontal"
    android:orientation="vertical"
    android:padding="24dp">

    <Space
        android:layout_width="match_parent"
        android:layout_height="48dp" />

    <Button
        android:id="@+id/btn_scan_pair"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/s_scan_pair" />

    <Space
        android:layout_width="match_parent"
        android:layout_height="12dp" />

    <Button
        android:id="@+id/btn_unpair"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/s_unpair" />

    <Space
        android:layout_width="match_parent"
        android:layout_height="24dp" />

    <EditText
        android:id="@+id/et_message"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/s_default_text"
        android:inputType="text"
        android:textSize="18sp" />

    <Space
        android:layout_width="match_parent"
        android:layout_height="24dp" />

    <Button
        android:id="@+id/btn_send"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/s_send_reminder" />

</LinearLayout>
```

- [ ] **Step 2: 提交**

```bash
git add demo/src/main/res/layout/activity_main.xml
git commit -m "feat(demo): 创建 activity_main.xml 布局"
```

---

### Task 6: MainActivity.kt — 全部业务逻辑

**Files:**
- Create: `demo/src/main/java/com/ble/notification/demo/MainActivity.kt`

**Interfaces:**
- Consumes: `BleNotificationSDK.init/getInstance/isPaired/startPairing/unpair/setReminder`
- Produces: 完整的绑定 → 解绑 → 闹钟验证流程

- [ ] **Step 1: 创建 MainActivity.kt**

```kotlin
package com.ble.notification.demo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ble.notification.pairing.PairingCallback
import com.ble.notification.sdk.BleNotificationSDK
import com.ble.notification.sdk.ReminderCallback
import com.ble.notification.sdk.SdkError

class MainActivity : AppCompatActivity() {

    private lateinit var sdk: BleNotificationSDK
    private lateinit var btnScanPair: Button
    private lateinit var btnUnpair: Button
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sdk = BleNotificationSDK.init(this)

        btnScanPair = findViewById(R.id.btn_scan_pair)
        btnUnpair = findViewById(R.id.btn_unpair)
        etMessage = findViewById(R.id.et_message)
        btnSend = findViewById(R.id.btn_send)

        btnScanPair.setOnClickListener { startPairing() }
        btnUnpair.setOnClickListener { doUnpair() }
        btnSend.setOnClickListener { doSendReminder() }

        updateButtonStates()
    }

    private fun startPairing() {
        sdk.startPairing(this, "BLE通知测试", object : PairingCallback {
            override fun onScanSuccess() {
                Toast.makeText(this@MainActivity, "扫码成功，连接中…", Toast.LENGTH_SHORT).show()
            }
            override fun onConnecting() {
                Toast.makeText(this@MainActivity, "正在连接…", Toast.LENGTH_SHORT).show()
            }
            override fun onRegistering() {
                Toast.makeText(this@MainActivity, "正在注册…", Toast.LENGTH_SHORT).show()
            }
            override fun onPaired() {
                Toast.makeText(this@MainActivity, "绑定成功！", Toast.LENGTH_SHORT).show()
                runOnUiThread { updateButtonStates() }
            }
            override fun onError(error: SdkError) {
                Toast.makeText(this@MainActivity, "绑定失败: ${error.message}", Toast.LENGTH_LONG).show()
                runOnUiThread { updateButtonStates() }
            }
        })
    }

    private fun doUnpair() {
        sdk.unpair(packageName)
        Toast.makeText(this, "已解除绑定", Toast.LENGTH_SHORT).show()
        updateButtonStates()
    }

    private fun doSendReminder() {
        val message = etMessage.text.toString().ifBlank {
            getString(R.string.s_default_text)
        }
        val triggerAt = System.currentTimeMillis() + 10_000

        sdk.setReminder(
            taskId = "demo_${System.currentTimeMillis()}",
            title = "BLE 通知测试",
            body = message,
            triggerAt = triggerAt,
            callback = object : ReminderCallback {
                override fun onScheduled(taskId: String) {
                    Toast.makeText(this@MainActivity, "提醒已设置，10 秒后触发", Toast.LENGTH_SHORT).show()
                    finish()
                }
                override fun onTriggered(taskId: String) {}
                override fun onSynced(taskId: String, success: Boolean) {}
            }
        )
    }

    private fun updateButtonStates() {
        val paired = sdk.isPaired(packageName)
        btnScanPair.isEnabled = !paired
        btnUnpair.isEnabled = paired
    }
}
```

- [ ] **Step 2: 编译**

```bash
./gradlew --no-daemon :demo:compileDebugKotlin
```

期望: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add demo/src/main/java/com/ble/notification/demo/MainActivity.kt
git commit -m "feat(demo): MainActivity — 扫码绑定/解绑/闹钟流程"
```

---

### Task 7: 全量编译验证 + 文档

**Files:**
- 无新建

- [ ] **Step 1: 全量编译**

```bash
./gradlew --no-daemon :demo:compileDebugKotlin :sdk:compileDebugKotlin
```

期望: BUILD SUCCESSFUL（sdk + demo 全部通过）

- [ ] **Step 2: 提交**

```bash
git add demo/DESIGN.md demo/PLAN.md
git commit -m "docs(demo): Demo App 设计与实现计划完成"
```
