use std::collections::HashMap;
use std::sync::Mutex as StdMutex;
use tauri::{AppHandle, Emitter, Manager};

use crate::{config, crypto, protocol, storage};

const SERVICE_UUID: &str = "0000A1B2-0000-1000-8000-00805F9B34FB";
const CHAR_WRITE_UUID: &str = "0000C3D4-0000-1000-8000-00805F9B34FB";
const CHAR_NOTIFY_UUID: &str = "0000D5E6-0000-1000-8000-00805F9B34FB";

pub struct BleState {
    pub is_running: StdMutex<bool>,
}

impl Default for BleState {
    fn default() -> Self {
        Self { is_running: StdMutex::new(false) }
    }
}

// ── 内部函数 ──

fn start_internal(state: &BleState) -> Result<(), String> {
    let mut is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    if *is_running { return Err("Server already running".to_string()); }
    *is_running = true;
    Ok(())
}

fn stop_internal(state: &BleState) -> Result<(), String> {
    let mut is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    if !*is_running { return Err("Server not running".to_string()); }
    *is_running = false;
    Ok(())
}

// ── 分片重组（pub 供 ble_winrt 使用） ──

pub struct FragmentBuffer {
    buffers: HashMap<(u8, u8), Vec<Option<Vec<u8>>>>,
}

impl FragmentBuffer {
    pub fn new() -> Self { Self { buffers: HashMap::new() } }

    pub fn insert(&mut self, msg_type: u8, total_seq: u8, seq: u8, payload: Vec<u8>) -> Option<Vec<u8>> {
        if total_seq <= 1 { return Some(payload); }
        let key = (msg_type, total_seq);
        let slots = self.buffers.entry(key).or_insert_with(|| vec![None; total_seq as usize]);
        if (seq as usize) < slots.len() {
            slots[seq as usize] = Some(payload);
        }
        if slots.iter().all(|s| s.is_some()) {
            let mut full = Vec::new();
            for s in slots.iter() { full.extend_from_slice(s.as_ref().unwrap()); }
            self.buffers.remove(&key);
            Some(full)
        } else { None }
    }
}

// ── 消息处理（pub 供 ble_winrt 使用） ──

pub async fn handle_full_message(app_handle: &AppHandle, msg_type: u8, payload: &[u8]) {
    match msg_type {
        protocol::MSG_REGISTER => handle_register(app_handle, payload).await,
        protocol::MSG_NOTIFY => handle_notify(app_handle, payload).await,
        _ => {}
    }
}

async fn handle_register(app_handle: &AppHandle, payload: &[u8]) {
    #[derive(serde::Deserialize)]
    struct RegisterData { app_name: String, package: String, random: String }
    let data: RegisterData = match serde_json::from_slice(payload) {
        Ok(d) => d,
        Err(e) => { let _ = app_handle.emit("log-message", format!("REGISTER 解析失败: {}", e)); return; }
    };
    let random_bytes: [u8; 32] = match hex::decode(&data.random) {
        Ok(b) if b.len() == 32 => { let mut arr = [0u8; 32]; arr.copy_from_slice(&b); arr }
        _ => { let _ = app_handle.emit("log-message", "REGISTER: random 格式错误"); return; }
    };
    let base_key = crypto::derive_key(&data.package, &random_bytes);
    let mac = get_ble_mac();

    let storage_state = app_handle.state::<storage::StorageState>();
    {
        let mut devices = storage_state.devices.lock().unwrap();
        devices.insert(mac.clone(), storage::PairedDevice {
            mac: mac.clone(), app_name: data.app_name.clone(),
            package_name: data.package.clone(), paired_at: chrono::Utc::now().to_rfc3339(),
        });
    }
    sync_to_config(&storage_state);
    let _ = config::store_base_key(&mac, &data.package, &base_key);

    let _ = app_handle.emit("log-message",
        format!("设备已绑定: {} [{}] ({})", data.app_name, data.package, mac));
    let _ = app_handle.emit("device-registered", serde_json::json!({
        "mac": mac, "app_name": data.app_name, "package": data.package,
    }).to_string());
}

async fn handle_notify(app_handle: &AppHandle, payload: &[u8]) {
    if payload.len() < 14 { return; }
    let pkg_len = payload[0] as usize;
    if payload.len() < 1 + pkg_len + 12 + 1 { return; }
    let package = String::from_utf8_lossy(&payload[1..1 + pkg_len]);
    let nonce = &payload[1 + pkg_len..1 + pkg_len + 12];
    let ciphertext = &payload[1 + pkg_len + 12..];

    let storage_state = app_handle.state::<storage::StorageState>();
    let mac = {
        let devices = storage_state.devices.lock().unwrap();
        devices.iter().find(|(_, d)| d.package_name == package).map(|(m, _)| m.clone())
    };
    let mac = match mac {
        Some(m) => m,
        None => { let _ = app_handle.emit("log-message", format!("未找到设备: {}", package)); return; }
    };
    let base_key = match config::get_base_key(&mac, &package) {
        Ok(k) => k,
        Err(_) => return,
    };
    let plaintext = match crypto::decrypt(&base_key, nonce, ciphertext) {
        Ok(p) => p,
        Err(e) => { let _ = app_handle.emit("log-message", format!("解密失败: {}", e)); return; }
    };
    if let Ok(json_str) = String::from_utf8(plaintext) {
        #[derive(serde::Deserialize)]
        struct NotifyData { title: String, content: String }
        if let Ok(data) = serde_json::from_str::<NotifyData>(&json_str) {
            let _ = app_handle.emit("log-message", format!("通知: {} - {}", data.title, data.content));
            crate::notify::send(app_handle, &data.title, &data.content);
        }
    }
}

fn sync_to_config(state: &storage::StorageState) {
    let devices = state.devices.lock().unwrap();
    let silent = state.silent_mode.lock().unwrap();
    let _ = config::save_config(&config::AppConfig {
        silent_mode: *silent,
        devices: devices.values().map(|d| config::DeviceEntry {
            mac: d.mac.clone(), app_name: d.app_name.clone(),
            package_name: d.package_name.clone(), paired_at: d.paired_at.clone(),
        }).collect(),
    });
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
    start_internal(&state)?;

    let _ = app_handle.emit("log-message", "正在启动服务...");
    let _ = app_handle.emit("ble-status-sync", true);
    update_menu_checked(app_handle, true);

    #[cfg(target_os = "windows")]
    {
        let app_handle = app_handle.clone();
        tauri::async_runtime::spawn(async move {
            match crate::ble_winrt::start_ble_peripheral(&app_handle).await {
                Ok(()) => {}
                Err(e) => {
                    let _ = app_handle.emit("log-message", format!("BLE 启动失败: {}", e));
                }
            }
        });
    }

    Ok(())
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
