mod ble;
mod crypto;
mod protocol;
mod storage;

use tauri::{
    menu::{Menu, MenuItem},
    tray::{TrayIconBuilder, TrayIconEvent, MouseButton, MouseButtonState},
    Emitter, Manager, RunEvent, WindowEvent,
};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(ble::BleState::default())
        .manage(storage::StorageState::default())
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
        ])
        .on_window_event(|window, event| {
            // When close is requested, hide the window instead of destroying it
            if let WindowEvent::CloseRequested { api, .. } = event {
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .setup(|app| {
            // Create menu items
            let show_item = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let start_item = MenuItem::with_id(app, "start", "启动服务", true, None::<&str>)?;
            let stop_item = MenuItem::with_id(app, "stop", "停止服务", true, None::<&str>)?;
            let auto_start_item = MenuItem::with_id(app, "autostart", "开机自启动", true, None::<&str>)?;
            let silent_start_item = MenuItem::with_id(app, "silent", "静默启动服务", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;

            let menu = Menu::with_items(app, &[
                &show_item, &start_item, &stop_item,
                &auto_start_item, &silent_start_item,
                &quit_item,
            ])?;

            // Build single tray with menu and click handler
            let _tray = TrayIconBuilder::new()
                .icon(app.default_window_icon().cloned().unwrap())
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app_handle: &tauri::AppHandle, event| {
                    let id = event.id().0.as_str();

                    match id {
                        "show" => {
                            if let Some(window) = app_handle.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.unminimize();
                                let _ = window.set_focus();
                            }
                        }
                        "start" => {
                            let _ = app_handle.emit("tray-action", "start");
                        }
                        "stop" => {
                            let _ = app_handle.emit("tray-action", "stop");
                        }
                        "autostart" => {
                            let _ = app_handle.emit("tray-action", "autostart");
                        }
                        "silent" => {
                            // Hide window and start server
                            if let Some(window) = app_handle.get_webview_window("main") {
                                let _ = window.hide();
                            }
                            let _ = app_handle.emit("tray-action", "silent-start");
                        }
                        "quit" => {
                            app_handle.exit(0);
                        }
                        _ => {}
                    }
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.unminimize();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(app)?;

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
