use std::sync::Mutex;
use tauri::State;

pub struct BleState {
    pub is_running: Mutex<bool>,
}

impl Default for BleState {
    fn default() -> Self {
        Self {
            is_running: Mutex::new(false),
        }
    }
}

#[tauri::command]
pub fn start_gatt_server(state: State<BleState>) -> Result<String, String> {
    let mut is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    
    if *is_running {
        return Err("Server already running".to_string());
    }
    
    // TODO: Implement GATT server startup
    // This will be implemented when we add BLE support
    
    *is_running = true;
    Ok("GATT server started".to_string())
}

#[tauri::command]
pub fn stop_gatt_server(state: State<BleState>) -> Result<String, String> {
    let mut is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    
    if !*is_running {
        return Err("Server not running".to_string());
    }
    
    // TODO: Implement GATT server shutdown
    
    *is_running = false;
    Ok("GATT server stopped".to_string())
}

#[tauri::command]
pub fn get_status(state: State<BleState>) -> Result<String, String> {
    let is_running = state.is_running.lock().map_err(|e| e.to_string())?;
    Ok(if *is_running { "Running" } else { "Stopped" }.to_string())
}
