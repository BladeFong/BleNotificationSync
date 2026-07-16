use tauri::{AppHandle, Emitter, Manager};
use crate::ble;
use crate::config;
use crate::storage;

// ── 菜单事件分派（数据驱动） ──

type MenuHandler = fn(&AppHandle);

static MENU_HANDLERS: &[(&str, MenuHandler)] = &[
    ("show", show_window as MenuHandler),
    ("ble_service", toggle_ble_service as MenuHandler),
    ("autostart", toggle_autostart as MenuHandler),
    ("silent", toggle_silent_mode as MenuHandler),
    ("quit", quit_app as MenuHandler),
];

pub fn handle_menu_event(app_handle: &AppHandle, event_id: &str) {
    for (id, handler) in MENU_HANDLERS {
        if *id == event_id {
            handler(app_handle);
            return;
        }
    }
}

// ── 窗口 ──

/// 显示主窗口并同步当前 BLE 状态到前端
pub fn show_window(app_handle: &AppHandle) {
    if let Some(window) = app_handle.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
    let is_running = ble::is_service_running(app_handle);
    let _ = app_handle.emit("ble-status-sync", is_running);
}

// ── BLE 服务 ──

/// 托盘菜单：切换 BLE 服务启停（日志由 start_service/stop_service 统一 emit）
pub fn toggle_ble_service(app_handle: &AppHandle) {
    let was_running = ble::is_service_running(app_handle);

    let result = if was_running {
        ble::stop_service(app_handle)
    } else {
        ble::start_service(app_handle)
    };

    if let Err(e) = result {
        let _ = app_handle.emit("log-message", format!("操作失败：{}", e));
    }
}

/// 静默模式：直接启动 BLE 服务
pub fn start_ble_service(app_handle: &AppHandle) {
    let _ = ble::start_service(app_handle);
}

// ── 设置 ──

fn toggle_autostart(app_handle: &AppHandle) {
    let current = storage::get_autostart().unwrap_or(false);
    let new = !current;
    match storage::set_autostart(new) {
        Ok(_) => {
            let msg = if new { "开机自启动已开启" } else { "开机自启动已关闭" };
            let _ = app_handle.emit("log-message", msg);
        }
        Err(e) => {
            let _ = app_handle.emit("log-message", format!("设置失败：{}", e));
        }
    }
}

fn toggle_silent_mode(app_handle: &AppHandle) {
    let storage_state = app_handle.state::<storage::StorageState>();
    let mut silent = storage_state.silent_mode.lock().unwrap();
    *silent = !*silent;
    let enabled = *silent;
    drop(silent);

    // 持久化
    let _ = config::save_silent_mode(enabled);
    // 更新托盘勾选
    if let Some(app_state) = app_handle.try_state::<crate::AppState>() {
        app_state.set_silent_checked(enabled);
    }
    let msg = if enabled { "静默启动服务已开启" } else { "静默启动服务已关闭" };
    let _ = app_handle.emit("log-message", msg);
}

// ── 退出 ──

fn quit_app(app_handle: &AppHandle) {
    let _ = ble::stop_service(app_handle);
    std::process::exit(0);
}
