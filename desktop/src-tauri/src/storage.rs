use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Mutex;
use tauri::State;

use crate::crypto;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub mac: String,
    pub app_name: String,
    pub package_name: String,
    pub paired_at: String,
}

pub struct StorageState {
    pub devices: Mutex<HashMap<String, PairedDevice>>,
    pub keys: Mutex<HashMap<String, [u8; 32]>>,
}

impl Default for StorageState {
    fn default() -> Self {
        Self {
            devices: Mutex::new(HashMap::new()),
            keys: Mutex::new(HashMap::new()),
        }
    }
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
) -> Result<String, String> {
    let mut devices = state.devices.lock().map_err(|e| e.to_string())?;
    let mut keys = state.keys.lock().map_err(|e| e.to_string())?;
    
    let device = PairedDevice {
        mac: mac.clone(),
        app_name: app_name.clone(),
        package_name: package_name.clone(),
        paired_at: chrono::Utc::now().to_rfc3339(),
    };
    
    // Derive and store key
    let key = crypto::derive_key(&package_name);
    keys.insert(format!("{}:{}", mac, package_name), key);
    
    devices.insert(mac.clone(), device);
    
    Ok(format!("Device {} paired successfully", mac))
}

#[tauri::command]
pub fn remove_paired_device(
    state: State<StorageState>,
    mac: String,
) -> Result<String, String> {
    let mut devices = state.devices.lock().map_err(|e| e.to_string())?;
    let mut keys = state.keys.lock().map_err(|e| e.to_string())?;

    // Remove all keys for this MAC
    keys.retain(|k, _| !k.starts_with(&format!("{}:", mac)));

    devices.remove(&mac);

    Ok(format!("Device {} removed", mac))
}

#[tauri::command]
pub fn set_autostart(enabled: bool) -> Result<String, String> {
    let app_name = "BLE Notification Sync";

    if enabled {
        // Add to Windows startup registry
        let exe_path = std::env::current_exe().map_err(|e| e.to_string())?;
        let cmd = format!("\"{}\"", exe_path.display());

        std::process::Command::new("reg")
            .args(&[
                "add",
                r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                "/v",
                app_name,
                "/t",
                "REG_SZ",
                "/d",
                &cmd,
                "/f",
            ])
            .output()
            .map_err(|e| e.to_string())?;

        Ok("Autostart enabled".to_string())
    } else {
        // Remove from Windows startup registry
        std::process::Command::new("reg")
            .args(&[
                "delete",
                r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                "/v",
                app_name,
                "/f",
            ])
            .output()
            .map_err(|e| e.to_string())?;

        Ok("Autostart disabled".to_string())
    }
}

#[tauri::command]
pub fn get_autostart() -> Result<bool, String> {
    let app_name = "BLE Notification Sync";

    let output = std::process::Command::new("reg")
        .args(&[
            "query",
            r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
            "/v",
            app_name,
        ])
        .output()
        .map_err(|e| e.to_string())?;

    let stdout = String::from_utf8_lossy(&output.stdout);
    Ok(stdout.contains(app_name))
}
