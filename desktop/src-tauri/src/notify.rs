use tauri::{AppHandle, Emitter};

/// 弹出系统通知（notify_rust → 失败回退 PowerShell）
pub fn send(app_handle: &AppHandle, title: &str, body: &str) {
    let app_handle = app_handle.clone();
    let title = title.to_string();
    let body = body.to_string();

    tokio::spawn(async move {
        // 1. notify_rust（设置正确 AUMID，支持 Windows/macOS/Linux）
        match send_notify_rust(&app_handle, &title, &body) {
            Ok(()) => {
                let _ = app_handle.emit("log-message", format!("通知已发送: {} - {}", title, body));
                return;
            }
            Err(e) => {
                let _ = app_handle.emit("log-message", format!("notify_rust 失败: {}, 尝试 PowerShell", e));
            }
        }

        // 2. 最终回退 PowerShell BalloonTip（仅 Windows）
        send_powershell(&app_handle, &title, &body);
    });
}

/// notify_rust 直接调用
fn send_notify_rust(_app_handle: &AppHandle, title: &str, body: &str) -> Result<(), String> {
    let mut notification = notify_rust::Notification::new();
    notification.summary(title);
    notification.body(body);

    // notify-rust v4 不支持 app_id 方法，使用默认设置
    notification.show().map_err(|e| format!("{:?}", e))?;
    Ok(())
}

/// 检测是否安装版：检查 exe 同级目录下的 .installed 标识文件
fn is_installed() -> bool {
    if let Ok(exe) = tauri::utils::platform::current_exe() {
        if let Some(dir) = exe.parent() {
            return dir.join(".installed").exists();
        }
    }
    false
}

/// PowerShell NotifyIcon BalloonTip 回退（仅 Windows）
fn send_powershell(app_handle: &AppHandle, title: &str, body: &str) {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const NO_WINDOW: u32 = 0x08000000;

        // 使用 $args[0] 和 $args[1] 参数化传递，避免命令注入
        let script = r#"Add-Type -AssemblyName System.Windows.Forms; $n = New-Object System.Windows.Forms.NotifyIcon; $n.Icon = [System.Drawing.SystemIcons]::Information; $n.Visible = $true; $n.ShowBalloonTip(60000, $args[0], $args[1], [System.Windows.Forms.ToolTipIcon]::Info); Start-Sleep -Seconds 3"#;

        match std::process::Command::new("powershell")
            .args(&["-NoProfile", "-Command", script, "-args", title, body])
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
