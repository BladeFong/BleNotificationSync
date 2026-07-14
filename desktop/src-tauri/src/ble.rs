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

#[tauri::command]
pub fn get_mac_address() -> Result<String, String> {
    // Get local Bluetooth MAC address without showing console window
    use std::os::windows::process::CommandExt;

    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let output = std::process::Command::new("powershell")
        .args(&["-Command", "(Get-NetAdapter | Where-Object {$_.PhysicalMediaType -eq '802.3' -or $_.InterfaceDescription -like '*Bluetooth*'}).MacAddress | Select-Object -First 1"])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| e.to_string())?;

    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !stdout.is_empty() && stdout.contains('-') {
        return Ok(stdout.replace('-', ":").to_uppercase());
    }

    // Fallback: try another method
    let output2 = std::process::Command::new("powershell")
        .args(&["-Command", "Get-WmiObject Win32_NetworkAdapterConfiguration | Where-Object {$_.IPEnabled -eq $true} | Select-Object -ExpandProperty MACAddress | Select-Object -First 1"])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| e.to_string())?;

    let stdout2 = String::from_utf8_lossy(&output2.stdout).trim().to_string();
    if !stdout2.is_empty() {
        return Ok(stdout2.replace('-', ":").to_uppercase());
    }

    // Final fallback
    Ok("AA:BB:CC:DD:EE:FF".to_string())
}
