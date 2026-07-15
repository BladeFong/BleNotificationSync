use tauri::{AppHandle, Emitter, Manager};
use crate::ble;
use crate::storage;

/// Handle all tray menu events
pub fn handle_menu_event(app_handle: &AppHandle, event_id: &str) {
    match event_id {
        "show" => show_window(app_handle),
        "ble_service" => toggle_ble_service(app_handle),
        "autostart" => toggle_autostart(app_handle),
        "silent" => toggle_silent_mode(app_handle),
        "quit" => quit_app(app_handle),
        _ => {}
    }
}

/// Add log message to frontend
fn add_log(app_handle: &AppHandle, is_running: bool, message: &str) {
    let _ = app_handle.emit("log-message", message);
}

/// Show main window and sync state
pub fn show_window(app_handle: &AppHandle) {
    if let Some(window) = app_handle.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
    sync_ble_status(app_handle);
}

/// Toggle BLE service start/stop
pub fn toggle_ble_service(app_handle: &AppHandle) {
    let state = app_handle.state::<ble::BleState>();
    let mut is_running = state.is_running.lock().unwrap();

    if *is_running {
        *is_running = false;
        sync_ble_status(app_handle);
        add_log(app_handle, false, "GATT 服务已停止");
    } else {
        *is_running = true;
        sync_ble_status(app_handle);
        add_log(app_handle, true, "GATT 服务已启动");
    }
}

/// Toggle autostart setting
fn toggle_autostart(app_handle: &AppHandle) {
    let current = storage::get_autostart().unwrap_or(false);
    let _ = storage::set_autostart(!current);
}

/// Toggle silent mode setting
fn toggle_silent_mode(app_handle: &AppHandle) {
    let current = storage::get_silent_mode().unwrap_or(false);
    let _ = storage::set_silent_mode(!current);
}

/// Quit application
fn quit_app(app_handle: &AppHandle) {
    // Stop BLE service before exiting
    let state = app_handle.state::<ble::BleState>();
    let mut is_running = state.is_running.lock().unwrap();
    if *is_running {
        *is_running = false;
    }
    std::process::exit(0);
}

/// Sync BLE status to frontend
pub fn sync_ble_status(app_handle: &AppHandle) {
    let state = app_handle.state::<ble::BleState>();
    let is_running = *state.is_running.lock().unwrap();
    let _ = app_handle.emit("ble-status-sync", is_running);
}

/// Start BLE service directly (for silent mode)
pub fn start_ble_service(app_handle: &AppHandle) {
    let state = app_handle.state::<ble::BleState>();
    let mut is_running = state.is_running.lock().unwrap();
    if !*is_running {
        *is_running = true;
    }
}
