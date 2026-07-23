use tauri::{AppHandle, Emitter};

/// 弹出系统通知
pub fn send(app_handle: &AppHandle, title: &str, body: &str) {
    let app_handle = app_handle.clone();
    let title = title.to_string();
    let body = body.to_string();

    tokio::spawn(async move {
        // 安装版用原生通知，开发版用 PowerShell 通知
        if is_installed(&app_handle) {
            match send_notify_rust(&app_handle, &title, &body) {
                Ok(()) => {
                    let _ = app_handle.emit("log-message", format!("通知已发送: {} - {}", title, body));
                    return;
                }
                Err(e) => {
                    let _ = app_handle.emit("log-message", format!("原生通知失败: {}", e));
                }
            }
        }
        
        // PowerShell 通知兜底
        send_powershell(&app_handle, &title, &body);
    });
}

/// notify_rust 直接调用（仅安装版使用）
fn send_notify_rust(app_handle: &AppHandle, title: &str, body: &str) -> Result<(), String> {
    let mut notification = notify_rust::Notification::new();
    notification.summary(title);
    notification.body(body);

    // 设置 app_id 使用原生通知
    let app_id = app_handle.config().identifier.clone();
    notification.app_id(&app_id);

    // 设置应用图标
    if let Ok(exe) = tauri::utils::platform::current_exe() {
        if let Some(dir) = exe.parent() {
            let icon_path = dir.join("icon.ico");
            if icon_path.exists() {
                notification.icon(icon_path.to_str().unwrap_or(""));
            }
        }
    }

    notification.show().map_err(|e| format!("{:?}", e))?;
    Ok(())
}

/// 检测是否安装版：查询注册表并验证运行路径一致
fn is_installed(app_handle: &AppHandle) -> bool {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const NO_WINDOW: u32 = 0x08000000;

        // 1. 获取当前运行路径
        let exe_path = match tauri::utils::platform::current_exe() {
            Ok(p) => p.to_string_lossy().to_string(),
            Err(e) => {
                let _ = app_handle.emit("log-message", format!("[通知检测] 获取运行路径失败: {}", e));
                return false;
            }
        };

        // 2. 查询 HKCU 注册表中的安装路径
        let output = match std::process::Command::new("reg")
            .args(&[
                "query",
                r"HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\BLE Notification Sync",
                "/v",
                "InstallLocation",
            ])
            .creation_flags(NO_WINDOW)
            .output()
        {
            Ok(o) => o,
            Err(e) => {
                let _ = app_handle.emit("log-message", format!("[通知检测] 注册表查询失败: {}", e));
                return false;
            }
        };

        if !output.status.success() {
            let _ = app_handle.emit("log-message", format!("[通知检测] 注册表查询无结果，使用 PowerShell 通知"));
            return false;
        }

        // 3. 解析注册表输出
        let stdout = String::from_utf8_lossy(&output.stdout);
        let install_path = stdout.lines()
            .find(|l| l.contains("InstallLocation"))
            .and_then(|l| l.split("REG_SZ").nth(1))
            .map(|s| s.trim().trim_matches('"').trim())
            .unwrap_or("");

        if install_path.is_empty() {
            let _ = app_handle.emit("log-message", format!("[通知检测] 注册表中 InstallLocation 为空，使用 PowerShell 通知"));
            return false;
        }

        // 4. 比较路径（运行路径包含安装路径即可）
        let exe_lower = exe_path.to_lowercase().replace("\\\\?\\", "");
        let install_lower = install_path.to_lowercase();
        let matched = exe_lower.contains(&install_lower);

        let _ = app_handle.emit("log-message", format!("[通知检测] 运行路径: {}", exe_lower));
        let _ = app_handle.emit("log-message", format!("[通知检测] 安装路径: {}", install_lower));
        let _ = app_handle.emit("log-message", format!("[通知检测] 路径匹配: {}", matched));

        matched
    }

    #[cfg(not(target_os = "windows"))]
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
