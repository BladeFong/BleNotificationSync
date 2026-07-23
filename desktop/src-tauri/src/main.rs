// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    std::panic::set_hook(Box::new(|info| {
        eprintln!("[FATAL PANIC] {:?}", info);
        if let Ok(appdata) = std::env::var("APPDATA") {
            let dir = std::path::PathBuf::from(appdata).join("ble-notification-sync");
            let _ = std::fs::create_dir_all(&dir);
            let log_file = dir.join("crash.log");
            let _ = std::fs::write(log_file, format!("[FATAL PANIC] {:?}", info));
        }
    }));
    desktop_lib::run()
}
