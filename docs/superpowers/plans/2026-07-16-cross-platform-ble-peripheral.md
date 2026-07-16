# 跨平台 BLE 外设服务实现计划

> **对于代理助手：** 必须使用 superpowers:subagent-driven-development (推荐) 或 superpowers:executing-plans 来逐个任务执行此计划。步骤使用复选框 (`- [ ]`) 语法进行跟踪。

**目标:** 移除平台专属的 WinRT 验证代码，使用 `ble-peripheral-rust` 库在 `ble.rs` 中提供 Windows & macOS 统一的 BLE 广播与 GATT 服务。

**架构:** 统一使用 `ble-peripheral-rust` 提供的 API 来初始化外设、添加服务、开启广播，并在后台异步消费 `PeripheralEvent` 事件。监听到客户端 Write 帧时响应接收成功，随后送入分片缓冲区进行消息重组。

**技术栈:** Rust, tauri v2, ble-peripheral-rust, tokio, uuid

## 全局约束

- 目标平台最低版本：Windows 10 1709+ / macOS 13+。
- 主服务 UUID：`0000A1B2-0000-1000-8000-00805F9B34FB`。
- 写入特征值 UUID：`0000C3D4-0000-1000-8000-00805F9B34FB`，属性为 `WriteWithoutResponse`。
- 命名与协议规则：严格执行二维码和帧格式协议，解密方案对齐 Android 端的 AES-GCM + HKDF。

---

### Task 1: 清理 WinRT 特定实现与配置

**文件：**
- 修改：[lib.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/lib.rs)
- 删除：[ble_winrt.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble_winrt.rs)

**接口：**
- 消耗：无
- 产生：移除 `ble_winrt` 模块引用

- [ ] **Step 1: 在 `lib.rs` 中移除 `mod ble_winrt` 的引用**

修改 [lib.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/lib.rs)（删除第 2-3 行）：
```rust
// 移除这部分代码：
#[cfg(target_os = "windows")]
mod ble_winrt;
```

- [ ] **Step 2: 物理删除 `ble_winrt.rs` 文件**

运行命令：
```bash
rm desktop/src-tauri/src/ble_winrt.rs
```

- [ ] **Step 3: 运行编译检查**

运行命令：
```bash
cargo check --manifest-path desktop/src-tauri/Cargo.toml
```
预期结果：编译报错，因为 `ble.rs` 仍在调用 `crate::ble_winrt::get_ble_mac`。这证明清理生效。

- [ ] **Step 4: 提交代码**

运行命令：
```bash
git add desktop/src-tauri/src/lib.rs
git commit -m "chore: remove winrt specific module and file"
```

---

### Task 2: 在 `ble.rs` 中实现跨平台的 MAC 地址获取逻辑

**文件：**
- 修改：[ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs)

**接口：**
- 消耗：无
- 产生：`fn get_ble_mac() -> String` (支持 Windows 和 macOS)

- [ ] **Step 1: 重写 `ble.rs` 中的 `get_ble_mac()` 方法**

修改 [ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs) 中的 `get_ble_mac` 函数，提供 Windows（通过 PowerShell）及 macOS（通过 system_profiler 解析 Address）的物理 MAC 获取方案：

```rust
fn get_ble_mac() -> String {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const NO_WINDOW: u32 = 0x08000000;
        let commands = [
            r#"$a=Get-CimInstance Win32_NetworkAdapter|?{$_.Name-like'*Bluetooth*'-or$_.ProductName-like'*Bluetooth*'};if($a){$a[0].MACAddress}"#,
            r#"$r=ls 'HKLM:\SYSTEM\CurrentControlSet\Services\BTHPORT\Parameters\Radios' -EA 0;if($r){$v=$r[0].GetValue('Address');if($v-is[array]){($v|%{$_.ToString('X2')})-join':'}}"#,
            r#"(Get-NetAdapter|?{$_.InterfaceDescription-like'*Bluetooth*'}).MacAddress"#,
        ];
        for cmd in commands {
            if let Ok(output) = std::process::Command::new("powershell")
                .args(&["-NoProfile", "-Command", cmd])
                .creation_flags(NO_WINDOW)
                .output()
            {
                let s = String::from_utf8_lossy(&output.stdout).trim().to_uppercase().replace('-', ":");
                if !s.is_empty() && s.contains(':') {
                    return s;
                }
            }
        }
    }

    #[cfg(target_os = "macos")]
    {
        if let Ok(output) = std::process::Command::new("system_profiler")
            .arg("SPBluetoothDataType")
            .output()
        {
            let s = String::from_utf8_lossy(&output.stdout);
            for line in s.lines() {
                if line.contains("Address:") {
                    let parts: Vec<&str> = line.split("Address:").collect();
                    if parts.len() > 1 {
                        let mac = parts[1].trim().to_uppercase().replace('-', ":");
                        if !mac.is_empty() {
                            return mac;
                        }
                    }
                }
            }
        }
    }

    "00:00:00:00:00:00".to_string()
}
```

- [ ] **Step 2: 运行编译验证**

运行命令：
```bash
cargo check --manifest-path desktop/src-tauri/Cargo.toml
```
预期结果：编译通过（其他未修改的占位逻辑除外）。

- [ ] **Step 3: 提交代码**

运行命令：
```bash
git add desktop/src-tauri/src/ble.rs
git commit -m "feat: implement cross-platform get_ble_mac for Win and macOS"
```

---

### Task 3: 在 `ble.rs` 中重构状态管理，加入 `shutdown_tx` 通道

**文件：**
- 修改：[ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs)

**接口：**
- 消耗：无
- 产生：`BleState` 包含 `shutdown_tx` 以支持优雅退出

- [ ] **Step 1: 修改 `BleState` 定义与 `Default` 实现**

修改 [ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs) 中的 `BleState` 部分：

```rust
pub struct BleState {
    pub is_running: StdMutex<bool>,
    pub shutdown_tx: StdMutex<Option<tokio::sync::oneshot::Sender<()>>>,
}

impl Default for BleState {
    fn default() -> Self {
        Self {
            is_running: StdMutex::new(false),
            shutdown_tx: StdMutex::new(None),
        }
    }
}
```

- [ ] **Step 2: 修改 `start_internal` 与 `stop_internal` 函数，添加通道处理**

在 [ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs) 中更新这两个函数签名与内容：

```rust
fn start_internal(state: &BleState, tx: tokio::sync::oneshot::Sender<()>) -> Result<(), String> {
    let mut is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    if *is_running {
        return Err("Server already running".to_string());
    }
    *is_running = true;
    if let Ok(mut guard) = state.shutdown_tx.lock() {
        *guard = Some(tx);
    }
    Ok(())
}

fn stop_internal(state: &BleState) -> Result<(), String> {
    let mut is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    if !*is_running {
        return Err("Server not running".to_string());
    }
    *is_running = false;
    if let Ok(mut guard) = state.shutdown_tx.lock() {
        if let Some(tx) = guard.take() {
            let _ = tx.send(());
        }
    }
    Ok(())
}
```

- [ ] **Step 3: 运行编译验证**

运行命令：
```bash
cargo check --manifest-path desktop/src-tauri/Cargo.toml
```
预期结果：由于 `start_service` 暂未升级以提供 `tx`，编译会报错，这是正常的。

- [ ] **Step 4: 提交代码**

运行命令：
```bash
git add desktop/src-tauri/src/ble.rs
git commit -m "feat: support shutdown_tx channel in BleState"
```

---

### Task 4: 实现 `ble-peripheral-rust` 服务创建与广播开启

**文件：**
- 修改：[ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs)

**接口：**
- 消耗：`BleState`
- 产生：GATT 广播及特征值创建

- [ ] **Step 1: 在 `ble.rs` 文件头部引入 `ble-peripheral-rust` 与 `uuid` 的必要包**

修改 [ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs) 头部：
```rust
use ble_peripheral_rust::{
    Peripheral, PeripheralEvent, Service, Characteristic, Uuid,
    properties::CharacteristicProperties,
    RequestResponse, WriteRequestResponse, ReadRequestResponse,
};
```

- [ ] **Step 2: 重新实现 `start_service` 开启 BLE 外设服务及广播**

在 [ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs) 中重写 `start_service` 函数：

```rust
pub fn start_service(app_handle: &AppHandle) -> Result<(), String> {
    let state = app_handle.state::<BleState>();
    let (tx, rx) = tokio::sync::oneshot::channel::<()>();

    start_internal(&state, tx)?;

    let _ = app_handle.emit("log-message", "正在启动服务...");
    let _ = app_handle.emit("ble-status-sync", true);
    update_menu_checked(app_handle, true);

    let app_handle_clone = app_handle.clone();
    tauri::async_runtime::spawn(async move {
        let app = app_handle_clone.clone();
        if let Err(e) = run_peripheral_task(app, rx).await {
            let _ = app_handle_clone.emit("log-message", format!("BLE 启动失败: {}", e));
            // 发生异常时重置状态
            let state = app_handle_clone.state::<BleState>();
            if let Ok(mut is_running) = state.is_running.lock() {
                *is_running = false;
            }
            let _ = app_handle_clone.emit("ble-status-sync", false);
            update_menu_checked(&app_handle_clone, false);
        }
    });

    Ok(())
}
```

- [ ] **Step 3: 实现 `run_peripheral_task` 函数**

在 [ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs) 中添加 `run_peripheral_task` 函数，包含外设服务定义与广播发布：

```rust
async fn run_peripheral_task(
    app_handle: AppHandle,
    mut shutdown_rx: tokio::sync::oneshot::Receiver<()>,
) -> Result<(), String> {
    let (event_tx, event_rx) = tokio::sync::mpsc::channel::<PeripheralEvent>(256);
    
    // 1. 初始化 Peripheral
    let mut peripheral = Peripheral::new(event_tx)
        .await
        .map_err(|e| format!("Failed to create peripheral: {:?}", e))?;

    // 2. 等待蓝牙适配器就绪
    while !peripheral.is_powered().await.map_err(|e| format!("{:?}", e))? {
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    let _ = app_handle.emit("log-message", "BLE: 适配器就绪");

    // 3. 构建 GATT 服务与特征值
    let srv_uuid = Uuid::parse_str(SERVICE_UUID).map_err(|e| format!("Invalid service UUID: {}", e))?;
    let char_uuid = Uuid::parse_str(CHAR_WRITE_UUID).map_err(|e| format!("Invalid characteristic UUID: {}", e))?;

    let mut properties = CharacteristicProperties::new();
    properties.write_without_response = true;

    let characteristic = Characteristic {
        uuid: char_uuid,
        properties,
        ..Default::default()
    };

    let service = Service {
        uuid: srv_uuid,
        primary: true,
        characteristics: vec![characteristic],
    };

    peripheral.add_service(&service).await;
    let _ = app_handle.emit("log-message", "BLE: GATT 服务与特征值创建成功");

    // 4. 启动广播
    peripheral
        .start_advertising("BleSyncPC", &[srv_uuid])
        .await
        .map_err(|e| format!("Start advertising failed: {:?}", e))?;
    let _ = app_handle.emit("log-message", "BLE: 广播已开启");

    // 5. 监听事件
    tokio::select! {
        _ = handle_ble_events(app_handle.clone(), event_rx) => {}
        _ = &mut shutdown_rx => {
            let _ = app_handle.emit("log-message", "BLE: 正在关闭服务，停止广播");
        }
    }

    Ok(())
}
```

- [ ] **Step 4: 运行编译验证**

运行命令：
```bash
cargo check --manifest-path desktop/src-tauri/Cargo.toml
```
预期结果：编译报错，提示 `handle_ble_events` 未定义，表明前置逻辑已经跑通。

- [ ] **Step 5: 提交代码**

运行命令：
```bash
git add desktop/src-tauri/src/ble.rs
git commit -m "feat: implement peripheral startup and service declaration"
```

---

### Task 5: 实现 BLE 异步事件监听与分片重组分发

**文件：**
- 修改：[ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs)

**接口：**
- 消耗：`tokio::sync::mpsc::Receiver<PeripheralEvent>`
- 产生：调用协议层重组分发逻辑 `handle_full_message`

- [ ] **Step 1: 实现 `handle_ble_events` 事件循环函数**

在 [ble.rs](file:///home/lanef/Android/MultiPlatformProjects/BleNotificationSync/desktop/src-tauri/src/ble.rs) 中编写 `handle_ble_events` 函数：

```rust
async fn handle_ble_events(
    app_handle: AppHandle,
    mut event_rx: tokio::sync::mpsc::Receiver<PeripheralEvent>,
) {
    let mut fragments = FragmentBuffer::new();

    while let Some(event) = event_rx.recv().await {
        match event {
            PeripheralEvent::WriteRequest {
                request,
                offset,
                value,
                responder,
            } => {
                // 1. 回复客户端
                responder.send(WriteRequestResponse {
                    response: RequestResponse::Success,
                });

                // 2. 数据送入分片处理器
                if !value.is_empty() {
                    if let Some(frame) = protocol::parse_frame(&value) {
                        let _ = app_handle.emit(
                            "log-message",
                            format!(
                                "BLE 收到帧: 类型 {:02X}, 包序号 {}/{}",
                                frame.msg_type, frame.seq, frame.total_seq
                            ),
                        );
                        if let Some(full) = fragments.insert(
                            frame.msg_type,
                            frame.total_seq,
                            frame.seq,
                            frame.payload,
                        ) {
                            handle_full_message(&app_handle, frame.msg_type, &full).await;
                        }
                    }
                }
            }
            PeripheralEvent::ReadRequest {
                request,
                offset,
                responder,
            } => {
                responder.send(ReadRequestResponse {
                    value: vec![].into(),
                    response: RequestResponse::Success,
                });
            }
            _ => {}
        }
    }
}
```

- [ ] **Step 2: 编译测试项目**

运行命令：
```bash
cargo build --manifest-path desktop/src-tauri/Cargo.toml
```
预期结果：编译成功（Build Success）。

- [ ] **Step 3: 提交代码**

运行命令：
```bash
git add desktop/src-tauri/src/ble.rs
git commit -m "feat: implement async BLE event loop and frame reassembly"
```

---

### Task 6: 本地编译与完整性验证

- [ ] **Step 1: 进行最终的完整版本编译**

运行命令：
```bash
cargo build --manifest-path desktop/src-tauri/Cargo.toml --release
```
预期结果：Release 模式下编译通过且没有警告。

- [ ] **Step 2: 检查文件和 Git 状态**

运行命令：
```bash
git status
```
确认没有多余的 WinRT 专有文件未清理。
