mod ble;
mod crypto;
mod event_handler;
mod protocol;
mod storage;

use std::sync::{Arc, Mutex};
use tauri::{
    menu::{CheckMenuItem, Menu, MenuItem},
    tray::{TrayIconBuilder, TrayIconEvent, MouseButton, MouseButtonState},
    Manager, RunEvent, WindowEvent,
};

/// Global state for menu updates
pub struct AppState {
    pub ble_service_item: Arc<Mutex<Option<CheckMenuItem<tauri::Wry>>>>,
}

impl AppState {
    pub fn new() -> Self {
        Self {
            ble_service_item: Arc::new(Mutex::new(None)),
        }
    }

    pub fn set_ble_service_checked(&self, checked: bool) {
        if let Ok(guard) = self.ble_service_item.lock() {
            if let Some(item) = &*guard {
                let _ = item.set_checked(checked);
            }
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_opener::init())
        .manage(ble::BleState::default())
        .manage(storage::StorageState::default())
        .manage(AppState::new())
        .invoke_handler(tauri::generate_handler![
            ble::start_gatt_server,
            ble::stop_gatt_server,
            ble::get_status,
            ble::get_mac_address,
            storage::get_paired_devices,
            storage::add_paired_device,
            storage::remove_paired_device,
            storage::set_autostart,
            storage::get_autostart,
            storage::set_silent_mode,
            storage::get_silent_mode,
        ])
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .setup(|app| {
            // Check settings
            let silent_enabled = storage::get_silent_mode().unwrap_or(false);
            let autostart_enabled = storage::get_autostart().unwrap_or(false);

            // Create menu items
            let show_item = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let ble_service_item = CheckMenuItem::with_id(app, "ble_service", "启动服务", true, false, None::<&str>)?;
            let auto_start_item = CheckMenuItem::with_id(app, "autostart", "开机自启动", true, autostart_enabled, None::<&str>)?;
            let silent_item = CheckMenuItem::with_id(app, "silent", "静默启动服务", true, silent_enabled, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;

            // Save reference for dynamic updates
            let ble_service_ref = ble_service_item.clone();

            let menu = Menu::with_items(app, &[
                &show_item, &ble_service_item,
                &auto_start_item, &silent_item,
                &quit_item,
            ])?;

            // Store in AppState
            let app_state = app.state::<AppState>();
            *app_state.ble_service_item.lock().unwrap() = Some(ble_service_ref);

            // Build tray with menu
            let _tray = TrayIconBuilder::new()
                .icon(app.default_window_icon().cloned().unwrap())
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app_handle, event| {
                    event_handler::handle_menu_event(app_handle, event.id().0.as_str());
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        event_handler::show_window(tray.app_handle());
                    }
                })
                .build(app)?;

            // Handle silent mode on startup
            if silent_enabled {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.hide();
                }
                event_handler::start_ble_service(app.handle());
                let state = app.state::<AppState>();
                state.set_ble_service_checked(true);
            } else {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application");

    app.run(|_app_handle, event| {
        if let RunEvent::ExitRequested { api, .. } = event {
            api.prevent_exit();
        }
    });
}
