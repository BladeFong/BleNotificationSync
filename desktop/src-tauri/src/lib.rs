mod ble;
mod config;
mod crypto;
mod event_handler;
mod notify;
mod protocol;
mod storage;

use std::sync::{Arc, Mutex};
use tauri::{
    menu::{CheckMenuItem, Menu, MenuItem},
    tray::{TrayIcon, TrayIconBuilder, TrayIconEvent, MouseButton, MouseButtonState},
    Manager, WindowEvent,
};

/// 检测系统语言是否为中文
fn is_chinese_locale() -> bool {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        if let Ok(output) = std::process::Command::new("powershell")
            .args(&["-Command", "(Get-Culture).TwoLetterISOLanguageName"])
            .creation_flags(0x08000000)
            .output()
        {
            let lang = String::from_utf8_lossy(&output.stdout).trim().to_lowercase();
            return lang == "zh";
        }
        false
    }
    #[cfg(not(target_os = "windows"))]
    {
        std::env::var("LANG").unwrap_or_default().starts_with("zh")
    }
}

/// 托盘菜单文案
struct MenuText {
    show: &'static str,
    start_service: &'static str,
    autostart: &'static str,
    silent: &'static str,
    quit: &'static str,
}

fn menu_text() -> MenuText {
    if is_chinese_locale() {
        MenuText {
            show: "显示窗口",
            start_service: "启动服务",
            autostart: "开机自启动",
            silent: "静默启动服务",
            quit: "退出",
        }
    } else {
        MenuText {
            show: "Show Window",
            start_service: "Start Service",
            autostart: "Auto Start",
            silent: "Silent Start",
            quit: "Quit",
        }
    }
}

/// Global state for menu updates
pub struct AppState {
    pub ble_service_item: Arc<Mutex<Option<CheckMenuItem<tauri::Wry>>>>,
    pub silent_item: Arc<Mutex<Option<CheckMenuItem<tauri::Wry>>>>,
    pub _tray_icon: Mutex<Option<TrayIcon>>,
}

/// # Safety
///
/// AppState 的所有字段都通过 Mutex 保护，CheckMenuItem 和 TrayIcon
/// 只在主线程（setup 阶段）创建，其他线程只通过 Mutex 同步访问。
/// Tauri 的状态管理系统需要 Send + Sync，因此手动实现是安全的。
unsafe impl Send for AppState {}
unsafe impl Sync for AppState {}

impl AppState {
    pub fn new() -> Self {
        Self {
            ble_service_item: Arc::new(Mutex::new(None)),
            silent_item: Arc::new(Mutex::new(None)),
            _tray_icon: Mutex::new(None),
        }
    }

    pub fn set_ble_service_checked(&self, checked: bool) {
        if let Ok(guard) = self.ble_service_item.lock() {
            if let Some(item) = &*guard {
                let _ = item.set_checked(checked);
            }
        }
    }

    pub fn set_silent_checked(&self, checked: bool) {
        if let Ok(guard) = self.silent_item.lock() {
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
        .plugin(tauri_plugin_notification::init())
        .manage(ble::BleState::default())
        .manage(storage::StorageState::default())
        .manage(notify::NotifyState::default())
        .manage(AppState::new())
        .invoke_handler(tauri::generate_handler![
            ble::start_gatt_server,
            ble::stop_gatt_server,
            ble::get_status,
            ble::get_mac_address,
            ble::get_device_info,
            storage::get_paired_devices,
            storage::add_paired_device,
            storage::remove_paired_device,
            storage::get_device_key,
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
            let storage_state = app.state::<storage::StorageState>();
            let silent_enabled = *storage_state.silent_mode.lock().unwrap();
            let autostart_enabled = storage::get_autostart().unwrap_or(false);

            // i18n menu text
            let mt = menu_text();

            // Create menu items
            let show_item = MenuItem::with_id(app, "show", mt.show, true, None::<&str>)?;
            let ble_service_item = CheckMenuItem::with_id(app, "ble_service", mt.start_service, true, false, None::<&str>)?;
            let auto_start_item = CheckMenuItem::with_id(app, "autostart", mt.autostart, true, autostart_enabled, None::<&str>)?;
            let silent_item = CheckMenuItem::with_id(app, "silent", mt.silent, true, silent_enabled, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", mt.quit, true, None::<&str>)?;

            // Save references for dynamic updates
            let ble_service_ref = ble_service_item.clone();
            let silent_ref = silent_item.clone();

            let menu = Menu::with_items(app, &[
                &show_item, &ble_service_item,
                &auto_start_item, &silent_item,
                &quit_item,
            ])?;

            // Store in AppState
            let app_state = app.state::<AppState>();
            *app_state.ble_service_item.lock().unwrap() = Some(ble_service_ref);
            *app_state.silent_item.lock().unwrap() = Some(silent_ref);

            // Build tray with menu（保存 handle 防止被 drop）
            let tray = TrayIconBuilder::new()
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
            *app_state._tray_icon.lock().unwrap() = Some(tray);

            // Handle silent mode on startup
            if silent_enabled {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.hide();
                }
                event_handler::start_ble_service(app.handle());
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

    app.run(|_app_handle, _event| {});
}

