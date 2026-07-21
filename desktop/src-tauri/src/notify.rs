use tauri::{AppHandle, Emitter};

/// 弹出系统通知（Tauri 跨平台组件 → 失败回退 notify_rust → 失败回退 PowerShell）
pub fn send(app_handle: &AppHandle, title: &str, body: &str) {
    let app_handle = app_handle.clone();
    let title = title.to_string();
    let body = body.to_string();

    tokio::spawn(async move {
        // 1. 尝试 Tauri 跨平台通知组件（支持 Windows + macOS + Linux）
        match send_tauri(&app_handle, &title, &body) {
            Ok(()) => {
                let _ = app_handle.emit("log-message", format!("通知已发送: {} - {}", title, body));
                return;
            }
            Err(e) => {
                let _ = app_handle.emit("log-message", format!("Tauri 通知失败: {}, 尝试 notify_rust", e));
            }
        }

        // 2. 回退到 notify_rust（直接 WinRT Toast / macOS / Linux）
        match send_notify_rust(&title, &body) {
            Ok(()) => {
                let _ = app_handle.emit("log-message", format!("通知已发送 (notify_rust): {} - {}", title, body));
                return;
            }
            Err(e) => {
                let _ = app_handle.emit("log-message", format!("notify_rust 失败: {}, 尝试 PowerShell", e));
            }
        }

        // 3. 最终回退 PowerShell BalloonTip（仅 Windows）
        send_powershell(&app_handle, &title, &body);
    });
}

/// Tauri 跨平台通知组件
fn send_tauri(app_handle: &AppHandle, title: &str, body: &str) -> Result<(), String> {
    use tauri_plugin_notification::NotificationExt;
    app_handle
        .notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map_err(|e| format!("{:?}", e))
}

/// notify_rust 直接调用（绕过 Tauri 插件的错误静默吞掉问题）
fn send_notify_rust(title: &str, body: &str) -> Result<(), String> {
    let mut notification = notify_rust::Notification::new();
    notification.summary(title);
    notification.body(body);
    notification.show().map_err(|e| format!("{:?}", e))?;
    Ok(())
}

/// PowerShell NotifyIcon BalloonTip 回退（仅 Windows）
fn send_powershell(app_handle: &AppHandle, title: &str, body: &str) {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const NO_WINDOW: u32 = 0x08000000;

        let title_escaped = title.replace('\'', "''");
        let body_escaped = body.replace('\'', "''");

        let script = format!(
            "Add-Type -AssemblyName System.Windows.Forms; \
             $n = New-Object System.Windows.Forms.NotifyIcon; \
             $n.Icon = [System.Drawing.SystemIcons]::Information; \
             $n.Visible = $true; \
             $n.ShowBalloonTip(60000, '{}', '{}', [System.Windows.Forms.ToolTipIcon]::Info); \
             Start-Sleep -Seconds 3",
            title_escaped, body_escaped
        );

        match std::process::Command::new("powershell")
            .args(&["-NoProfile", "-Command", &script])
            .creation_flags(NO_WINDOW)
            .output()
        {
            Ok(output) => {
                if output.status.success() {
                    let _ = app_handle.emit("log-message", "PowerShell 通知已发送".to_string());
                } else {
                    let stderr = String::from_utf8_lossy(&output.stderr);
                    let _ = app_handle.emit("log-message", format!("PowerShell 通知失败: {}", stderr));
                }
            }
            Err(e) => {
                let _ = app_handle.emit("log-message", format!("PowerShell 启动失败: {}", e));
            }
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        let _ = app_handle.emit("log-message", "所有通知方式均失败".to_string());
    }
}
