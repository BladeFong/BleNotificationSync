use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Mutex;
use tauri::State;

use crate::config;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub mac: String,
    pub app_name: String,
    pub package_name: String,
    pub paired_at: String,
}

impl From<config::DeviceEntry> for PairedDevice {
    fn from(e: config::DeviceEntry) -> Self {
        Self {
            mac: e.mac,
            app_name: e.app_name,
            package_name: e.package_name,
            paired_at: e.paired_at,
        }
    }
}

pub struct StorageState {
    pub devices: Mutex<HashMap<String, PairedDevice>>,
    pub silent_mode: Mutex<bool>,
}

impl Default for StorageState {
    fn default() -> Self {
        let cfg = config::load_config();
        let devices = cfg
            .devices
            .into_iter()
            .map(|d| (d.mac.clone(), PairedDevice::from(d)))
            .collect();
        Self {
            devices: Mutex::new(devices),
            silent_mode: Mutex::new(cfg.silent_mode),
        }
    }
}

/// 将当前内存状态写回 JSON 配置
fn sync_devices_to_config(state: &StorageState) {
    let devices = state.devices.lock().unwrap();
    let silent = state.silent_mode.lock().unwrap();
    let cfg = config::AppConfig {
        silent_mode: *silent,
        devices: devices
            .values()
            .map(|d| config::DeviceEntry {
                mac: d.mac.clone(),
                app_name: d.app_name.clone(),
                package_name: d.package_name.clone(),
                paired_at: d.paired_at.clone(),
            })
            .collect(),
    };
    let _ = config::save_config(&cfg);
}

#[tauri::command]
pub fn get_paired_devices(state: State<StorageState>) -> Result<Vec<PairedDevice>, String> {
    let devices = state.devices.lock().map_err(|e| e.to_string())?;
    Ok(devices.values().cloned().collect())
}

#[tauri::command]
pub fn add_paired_device(
    state: State<StorageState>,
    mac: String,
    app_name: String,
    package_name: String,
    key_hex: String,
) -> Result<String, String> {
    let mut devices = state.devices.lock().map_err(|e| e.to_string())?;

    // Decode baseKey from hex
    let key_bytes = hex::decode(&key_hex).map_err(|e| format!("key hex 解码失败: {}", e))?;
    let mut key = [0u8; 32];
    key.copy_from_slice(&key_bytes);

    // Store baseKey in Windows Credential Manager
    config::store_base_key(&mac, &package_name, &key)?;

    let device = PairedDevice {
        mac: mac.clone(),
        app_name: app_name.clone(),
        package_name: package_name.clone(),
        paired_at: chrono::Utc::now().to_rfc3339(),
    };

    devices.insert(mac.clone(), device);
    drop(devices);

    // Persist to JSON config
    sync_devices_to_config(&state);

    Ok(format!("Device {} paired successfully", mac))
}

#[tauri::command]
pub fn remove_paired_device(
    state: State<StorageState>,
    mac: String,
) -> Result<String, String> {
    let mut devices = state.devices.lock().map_err(|e| e.to_string())?;

    // Delete baseKey from Credential Manager
    if let Some(device) = devices.get(&mac) {
        let _ = config::delete_base_key(&mac, &device.package_name);
    }

    devices.remove(&mac);
    drop(devices);

    // Persist to JSON config
    sync_devices_to_config(&state);

    Ok(format!("Device {} removed", mac))
}

/// 获取存储的 baseKey（hex 字符串）
#[tauri::command]
pub fn get_device_key(state: State<StorageState>, mac: String) -> Result<String, String> {
    let devices = state.devices.lock().map_err(|e| e.to_string())?;
    let device = devices.get(&mac).ok_or("Device not found")?;

    config::get_base_key(&mac, &device.package_name)
        .map(|key| hex::encode(key))
}

#[tauri::command]
pub fn set_autostart(enabled: bool) -> Result<String, String> {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        let app_name = "BLE Notification Sync";

        if enabled {
            let exe_path = std::env::current_exe().map_err(|e| e.to_string())?;
            let cmd = format!("\"{}\"", exe_path.display());

            let output = std::process::Command::new("reg")
                .args(&[
                    "add",
                    r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                    "/v", app_name, "/t", "REG_SZ", "/d", &cmd, "/f",
                ])
                .creation_flags(CREATE_NO_WINDOW)
                .output()
                .map_err(|e| e.to_string())?;

            if output.status.success() {
                Ok("Autostart enabled".to_string())
            } else {
                Err("Failed to set autostart".to_string())
            }
        } else {
            let output = std::process::Command::new("reg")
                .args(&[
                    "delete",
                    r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                    "/v", app_name, "/f",
                ])
                .creation_flags(CREATE_NO_WINDOW)
                .output()
                .map_err(|e| e.to_string())?;

            if output.status.success() {
                Ok("Autostart disabled".to_string())
            } else {
                // 可能 key 本身就不存在，忽略错误
                Ok("Autostart disabled".to_string())
            }
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        let _ = enabled;
        Err("Autostart is only supported on Windows".to_string())
    }
}

#[tauri::command]
pub fn get_autostart() -> Result<bool, String> {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        let app_name = "BLE Notification Sync";

        let output = std::process::Command::new("reg")
            .args(&[
                "query",
                r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                "/v", app_name,
            ])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(|e| e.to_string())?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        Ok(stdout.contains(app_name))
    }

    #[cfg(not(target_os = "windows"))]
    Ok(false)
}

#[tauri::command]
pub fn set_silent_mode(state: State<StorageState>, enabled: bool) -> Result<String, String> {
    let mut silent = state.silent_mode.lock().map_err(|e| e.to_string())?;
    *silent = enabled;
    drop(silent);
    // 持久化
    sync_devices_to_config(&state);
    Ok(if enabled { "Silent mode enabled" } else { "Silent mode disabled" }.to_string())
}

#[tauri::command]
pub fn get_silent_mode(state: State<StorageState>) -> Result<bool, String> {
    let silent = state.silent_mode.lock().map_err(|e| e.to_string())?;
    Ok(*silent)
}
