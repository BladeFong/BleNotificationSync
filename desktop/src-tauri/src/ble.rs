use std::collections::HashMap;
use std::sync::Mutex as StdMutex;
use tauri::{AppHandle, Emitter, Manager};
use ble_peripheral_rust::{Peripheral, PeripheralImpl};
use ble_peripheral_rust::gatt::{
    service::Service,
    characteristic::Characteristic,
    properties::{CharacteristicProperty, AttributePermission},
    peripheral_event::{PeripheralEvent, RequestResponse, WriteRequestResponse, ReadRequestResponse},
};
use uuid::Uuid;

use crate::{config, crypto, protocol, storage};

const SERVICE_UUID: &str = "9e1d51a4-9c86-4447-9759-f6222b0f4b36";
const CHAR_WRITE_UUID: &str = "f4788cde-8025-4c07-b352-87db1b272fdf";
const CHAR_NOTIFY_UUID: &str = "e7f22370-d86b-4e1a-8289-8d77bfb534ee";

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

// ── 内部函数 ──

fn start_internal(state: &BleState, tx: tokio::sync::oneshot::Sender<()>) -> Result<(), String> {
    let mut is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    if *is_running { return Err("Server already running".to_string()); }
    *is_running = true;
    if let Ok(mut guard) = state.shutdown_tx.lock() {
        *guard = Some(tx);
    }
    Ok(())
}

fn stop_internal(state: &BleState) -> Result<(), String> {
    let mut is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    if !*is_running { return Err("Server not running".to_string()); }
    *is_running = false;
    if let Ok(mut guard) = state.shutdown_tx.lock() {
        if let Some(tx) = guard.take() {
            let _ = tx.send(());
        }
    }
    Ok(())
}

// ── 分片重组 ──

struct FragmentEntry {
    slots: Vec<Option<Vec<u8>>>,
    created_at: std::time::Instant,
}

pub struct FragmentBuffer {
    buffers: HashMap<(u8, u8), FragmentEntry>,
}

impl FragmentBuffer {
    pub fn new() -> Self { Self { buffers: HashMap::new() } }

    pub fn insert(&mut self, msg_type: u8, total_seq: u8, seq: u8, payload: Vec<u8>) -> Option<Vec<u8>> {
        if total_seq <= 1 { return Some(payload); }
        let key = (msg_type, total_seq);
        let entry = self.buffers.entry(key).or_insert_with(|| FragmentEntry {
            slots: vec![None; total_seq as usize],
            created_at: std::time::Instant::now(),
        });
        if (seq as usize) < entry.slots.len() {
            entry.slots[seq as usize] = Some(payload);
        }
        if !entry.slots.is_empty() && entry.slots.iter().all(|s| s.is_some()) {
            let mut full = Vec::new();
            for s in entry.slots.iter() {
                if let Some(data) = s {
                    full.extend_from_slice(data);
                }
            }
            self.buffers.remove(&key);
            Some(full)
        } else { None }
    }

    /// 清理超过 max_age 未集齐的分片（防止丢包内存泄漏）
    pub fn cleanup_stale(&mut self, max_age: std::time::Duration) {
        let now = std::time::Instant::now();
        self.buffers.retain(|_, entry| now.duration_since(entry.created_at) < max_age);
    }
}

// ── 消息处理 ──

pub async fn handle_full_message(app_handle: &AppHandle, msg_type: u8, payload: &[u8]) {
    let _ = app_handle.emit("log-message", format!("收到消息: 类型={:02X}, 长度={}", msg_type, payload.len()));
    match msg_type {
        protocol::MSG_REGISTER => handle_register(app_handle, payload).await,
        protocol::MSG_NOTIFY => handle_notify(app_handle, payload).await,
        protocol::MSG_ICON_DATA => handle_icon_data(app_handle, payload).await,
        protocol::MSG_ICON_END => handle_icon_end(app_handle, payload).await,
        _ => {}
    }
}

async fn handle_register(app_handle: &AppHandle, payload: &[u8]) {
    #[derive(serde::Deserialize)]
    struct RegisterData {
        app_name: String,
        package: String,
        random: String,
        #[serde(default)]
        android_id: Option<String>,
        #[serde(default)]
        device_name: Option<String>,
    }
    let data: RegisterData = match serde_json::from_slice(payload) {
        Ok(d) => d,
        Err(e) => { let _ = app_handle.emit("log-message", format!("REGISTER 解析失败: {}", e)); return; }
    };
    let random_bytes: [u8; 32] = match hex::decode(&data.random) {
        Ok(b) if b.len() == 32 => { let mut arr = [0u8; 32]; arr.copy_from_slice(&b); arr }
        _ => { let _ = app_handle.emit("log-message", "REGISTER: random 格式错误"); return; }
    };
    let base_key = crypto::derive_key(&data.package, &random_bytes);

    // 使用 android_id 作为设备唯一标识（向后兼容：旧版无 android_id 时用 PC MAC）
    let device_id = data.android_id.clone().unwrap_or_else(|| get_ble_mac());
    let device_name = data.device_name.clone().unwrap_or_else(|| "Unknown".to_string());

    let storage_state = app_handle.state::<storage::StorageState>();
    {
        let mut last_pkg = storage_state.last_registered_package.lock().unwrap();
        *last_pkg = Some(data.package.clone());
    }
    let pair_key = format!("{}:{}", device_id, data.package);
    {
        let mut devices = storage_state.devices.lock().unwrap();
        // 如果该设备的该 App 已存在，更新设备名（支持动态更新）
        if let Some(existing) = devices.get_mut(&pair_key) {
            existing.device_name = device_name.clone();
            existing.app_name = data.app_name.clone();
            existing.paired_at = chrono::Utc::now().to_rfc3339();
        } else {
            devices.insert(pair_key.clone(), storage::PairedDevice {
                device_id: device_id.clone(),
                device_name: device_name.clone(),
                app_name: data.app_name.clone(),
                package_name: data.package.clone(),
                paired_at: chrono::Utc::now().to_rfc3339(),
            });
        }
    }
    storage::sync_devices_to_config(&storage_state);
    match config::store_base_key(&device_id, &data.package, &base_key) {
        Ok(()) => { let _ = app_handle.emit("log-message", format!("密钥存储成功: device_id={}", device_id)); }
        Err(e) => { let _ = app_handle.emit("log-message", format!("密钥存储失败: {}", e)); }
    }

    let _ = app_handle.emit("log-message",
        format!("设备已绑定: {} [{}] ({})", data.app_name, data.package, device_name));
    let _ = app_handle.emit("device-registered", serde_json::json!({
        "device_id": device_id, "device_name": device_name,
        "app_name": data.app_name, "package": data.package,
    }).to_string());
}

async fn handle_icon_data(app_handle: &AppHandle, payload: &[u8]) {
    let _ = app_handle.emit("log-message", format!("BLE 收到图标数据帧 (0x04)，Payload 长度: {}", payload.len()));
    let storage_state = app_handle.state::<storage::StorageState>();
    let package = {
        let guard = storage_state.last_registered_package.lock().unwrap();
        guard.clone()
    };
    if let Some(pkg) = package {
        let app_handle_clone = app_handle.clone();
        let payload_vec = payload.to_vec();
        tokio::task::spawn_blocking(move || {
            match config::save_app_icon(&pkg, &payload_vec) {
                Ok(path) => {
                    let path_str = path.to_string_lossy().to_string();
                    let _ = app_handle_clone.emit("log-message", format!("App 图标保存成功: {} -> {}", pkg, path_str));
                }
                Err(e) => {
                    let _ = app_handle_clone.emit("log-message", format!("App 图标保存失败: {}", e));
                }
            }
        });
    } else {
        let _ = app_handle.emit("log-message", "收到 App 图标数据，但未找到匹配包名".to_string());
    }
}

async fn handle_icon_end(app_handle: &AppHandle, _payload: &[u8]) {
    let _ = app_handle.emit("log-message", "BLE 收到图标传输完成帧 (0x05)".to_string());
}

async fn handle_notify(app_handle: &AppHandle, payload: &[u8]) {
    let _ = app_handle.emit("log-message", format!("开始处理通知，Payload 长度: {}", payload.len()));
    if payload.len() < 14 {
        let _ = app_handle.emit("log-message", format!("通知 Payload 过短: {}", payload.len()));
        return;
    }
    let pkg_len = payload[0] as usize;
    if payload.len() < 1 + pkg_len + 12 + 1 {
        let _ = app_handle.emit("log-message", format!("通知 Payload 长度不足: payload_len={}, pkg_len={}", payload.len(), pkg_len));
        return;
    }
    let package = String::from_utf8_lossy(&payload[1..1 + pkg_len]).to_string();
    let nonce = &payload[1 + pkg_len..1 + pkg_len + 12];
    let ciphertext = &payload[1 + pkg_len + 12..];

    let _ = app_handle.emit("log-message", format!("解析包名: {}, Nonce 长度: {}, 密文长度: {}", package, nonce.len(), ciphertext.len()));

    let storage_state = app_handle.state::<storage::StorageState>();
    let matching_device_ids: Vec<String> = {
        let devices = storage_state.devices.lock().unwrap();
        devices.iter()
            .filter(|(_, d)| d.package_name == package)
            .map(|(_, d)| d.device_id.clone())
            .collect()
    };

    if matching_device_ids.is_empty() {
        let _ = app_handle.emit("log-message", format!("未找到包名为 {} 的已绑定设备", package));
        return;
    }

    let mut decrypted_plaintext: Option<Vec<u8>> = None;
    for dev_id in &matching_device_ids {
        if let Ok(base_key) = config::get_base_key(dev_id, &package) {
            if let Ok(plaintext) = crypto::decrypt(&base_key, nonce, ciphertext) {
                let _ = app_handle.emit("log-message", format!("解密成功: device_id={}", dev_id));
                decrypted_plaintext = Some(plaintext);
                break;
            }
        }
    }

    let plaintext = match decrypted_plaintext {
        Some(p) => p,
        None => {
            let _ = app_handle.emit("log-message", format!("解密失败: 尝试了 {} 个匹配设备的密钥均失败", matching_device_ids.len()));
            return;
        }
    };

    match String::from_utf8(plaintext) {
        Ok(json_str) => {
            #[derive(serde::Deserialize)]
            struct NotifyData { title: String, body: String }
            match serde_json::from_str::<NotifyData>(&json_str) {
                Ok(data) => {
                    let _ = app_handle.emit("log-message", format!("通知: {} - {}", data.title, data.body));
                    crate::notify::send(app_handle, &data.title, &data.body, Some(&package));
                }
                Err(e) => {
                    let _ = app_handle.emit("log-message", format!("解析通知 JSON 失败: {}, Raw: {}", e, json_str));
                }
            }
        }
        Err(e) => {
            let _ = app_handle.emit("log-message", format!("明文转换为 UTF-8 失败: {:?}", e));
        }
    }
}


// ── MAC 获取 ──

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

// ── 公共入口 ──

pub fn start_service(app_handle: &AppHandle) -> Result<(), String> {
    let state = app_handle.state::<BleState>();
    
    // 先停止之前的服务（如果有）
    if let Ok(mut is_running) = state.is_running.lock() {
        *is_running = false;
    }
    if let Ok(mut guard) = state.shutdown_tx.lock() {
        if let Some(tx) = guard.take() {
            let _ = tx.send(());
        }
    }
    
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

    let characteristic = Characteristic {
        uuid: char_uuid,
        properties: vec![
            CharacteristicProperty::Write,
            CharacteristicProperty::WriteWithoutResponse,
        ],
        permissions: vec![AttributePermission::Writeable],
        ..Default::default()
    };

    let service = Service {
        uuid: srv_uuid,
        primary: true,
        characteristics: vec![characteristic],
    };

    peripheral
        .add_service(&service)
        .await
        .map_err(|e| format!("Add service failed: {:?}", e))?;
    let _ = app_handle.emit("log-message", "BLE: GATT 服务与特征值创建成功");

    // 4. 启动广播
    peripheral
        .start_advertising("BleSyncPC", &[srv_uuid])
        .await
        .map_err(|e| format!("Start advertising failed: {:?}", e))?;
    
    // 等待广播状态更新
    tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    
    let is_adv = peripheral.is_advertising().await.unwrap_or(false);
    let _ = app_handle.emit("log-message", format!("BLE: 广播已开启，状态：{}", if is_adv { "广播中" } else { "未广播" }));

    // 5. 监听事件
    tokio::select! {
        _ = handle_ble_events(app_handle.clone(), event_rx) => {}
        _ = &mut shutdown_rx => {
            let _ = app_handle.emit("log-message", "BLE: 正在关闭服务，停止广播");
        }
    }

    // 6. 清理资源
    let _ = peripheral.stop_advertising().await;
    let _ = app_handle.emit("log-message", "BLE: 服务已清理");

    Ok(())
}

async fn handle_ble_events(
    app_handle: AppHandle,
    mut event_rx: tokio::sync::mpsc::Receiver<PeripheralEvent>,
) {
    let mut fragments = FragmentBuffer::new();
    let mut event_count: u64 = 0;

    while let Some(event) = event_rx.recv().await {
        match event {
            PeripheralEvent::WriteRequest {
                request: _,
                offset: _,
                value,
                responder,
            } => {
                // 1. 回复客户端（防范 WinRT COM 异常）
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    let _ = responder.send(WriteRequestResponse {
                        response: RequestResponse::Success,
                    });
                }));
                if result.is_err() {
                    let _ = app_handle.emit("log-message", "[BLE] WinRT responder.send 崩溃（已恢复，不影响服务）");
                }

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
                request: _,
                offset: _,
                responder,
            } => {
                let _ = responder.send(ReadRequestResponse {
                    value: vec![].into(),
                    response: RequestResponse::Success,
                });
            }
            _ => {}
        }

        // 每 100 个事件清理一次超过 30 秒未集齐的分片
        event_count += 1;
        if event_count % 100 == 0 {
            fragments.cleanup_stale(std::time::Duration::from_secs(30));
        }
    }
}

pub fn stop_service(app_handle: &AppHandle) -> Result<(), String> {
    let state = app_handle.state::<BleState>();
    stop_internal(&state)?;
    
    let _ = app_handle.emit("log-message", "GATT 服务已停止");
    let _ = app_handle.emit("ble-status-sync", false);
    update_menu_checked(app_handle, false);
    Ok(())
}

pub fn is_service_running(app_handle: &AppHandle) -> bool {
    let state = app_handle.state::<BleState>();
    state.is_running.lock().map(|g| *g).unwrap_or(false)
}

fn update_menu_checked(app_handle: &AppHandle, checked: bool) {
    if let Some(app_state) = app_handle.try_state::<crate::AppState>() {
        app_state.set_ble_service_checked(checked);
    }
}

// ── Tauri 命令 ──

#[tauri::command]
pub fn start_gatt_server(app_handle: AppHandle) -> Result<String, String> {
    start_service(&app_handle)?;
    Ok("GATT server started".to_string())
}

#[tauri::command]
pub fn stop_gatt_server(app_handle: AppHandle) -> Result<String, String> {
    stop_service(&app_handle)?;
    Ok("GATT server stopped".to_string())
}

#[tauri::command]
pub fn get_status(app_handle: AppHandle) -> Result<String, String> {
    Ok(if is_service_running(&app_handle) { "Running" } else { "Stopped" }.to_string())
}

#[derive(serde::Serialize)]
pub struct DeviceInfo {
    pub name: String,
}

#[tauri::command]
pub fn get_device_info() -> Result<DeviceInfo, String> {
    let name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "UnknownPC".to_string());
    Ok(DeviceInfo { name })
}

#[tauri::command]
pub fn get_mac_address() -> Result<String, String> {
    Ok(get_ble_mac())
}

// ── 测试 ──

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_fragment_single() {
        let mut fb = FragmentBuffer::new();
        assert_eq!(fb.insert(1, 1, 0, vec![1, 2, 3]), Some(vec![1, 2, 3]));
    }
    #[test]
    fn test_fragment_multi() {
        let mut fb = FragmentBuffer::new();
        assert!(fb.insert(1, 3, 0, vec![1, 2]).is_none());
        assert!(fb.insert(1, 3, 1, vec![3, 4]).is_none());
        assert_eq!(fb.insert(1, 3, 2, vec![5, 6]), Some(vec![1, 2, 3, 4, 5, 6]));
    }
    #[test]
    fn test_fragment_out_of_order() {
        let mut fb = FragmentBuffer::new();
        assert!(fb.insert(2, 3, 2, vec![5]).is_none());
        assert!(fb.insert(2, 3, 0, vec![1]).is_none());
        assert_eq!(fb.insert(2, 3, 1, vec![3]), Some(vec![1, 3, 5]));
    }
}
