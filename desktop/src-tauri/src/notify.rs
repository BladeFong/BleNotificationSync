use std::sync::atomic::{AtomicU64, Ordering};
use tauri::{AppHandle, Emitter, Manager};

/// 通知清理状态：epoch 防抖，3 分钟内收到新通知则顺延
pub struct NotifyState {
    pub cleanup_epoch: AtomicU64,
}

impl Default for NotifyState {
    fn default() -> Self {
        Self { cleanup_epoch: AtomicU64::new(0) }
    }
}

/// 弹出系统通知（平台分流入口）
pub fn send(app_handle: &AppHandle, title: &str, body: &str, package: Option<&str>) {
    let app_handle = app_handle.clone();
    let title = title.to_string();
    let body = body.to_string();
    let package = package.map(|s| s.to_string());

    tokio::spawn(async move {
        send_inner(&app_handle, &title, &body, package.as_deref()).await;
    });
}

#[cfg(target_os = "windows")]
async fn send_inner(app_handle: &AppHandle, title: &str, body: &str, package: Option<&str>) {
    if is_installed(app_handle) {
        match send_notify_winrt(app_handle, title, body, package) {
            Ok(()) => {
                let _ = app_handle.emit("log-message", format!("通知已发送: {} - {}", title, body));
                return;
            }
            Err(e) => {
                let _ = app_handle.emit("log-message", format!("原生通知失败: {}", e));
            }
        }
    }
    // PowerShell 通知兜底（开发版或原生通知失败时）
    send_powershell(app_handle, title, body);
}

#[cfg(not(target_os = "windows"))]
async fn send_inner(app_handle: &AppHandle, title: &str, body: &str, package: Option<&str>) {
    match send_notify_nix(title, body, package) {
        Ok(()) => {
            let _ = app_handle.emit("log-message", format!("通知已发送: {} - {}", title, body));
        }
        Err(e) => {
            let _ = app_handle.emit("log-message", format!("通知发送失败: {}", e));
        }
    }
}

// ── Windows: WinRT Toast ──

#[cfg(target_os = "windows")]
fn send_notify_winrt(
    app_handle: &AppHandle,
    title: &str,
    body: &str,
    package: Option<&str>,
) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    use winrt_notification::{Toast, IconCrop, Duration};

    const NO_WINDOW: u32 = 0x08000000;

    let app_id = app_handle.config().identifier.clone();
    let mut toast = Toast::new(&app_id)
        .title(title)
        .text1(body)
        .duration(Duration::Short);

    let mut icon_set = false;
    if let Some(pkg) = package {
        if let Some(app_icon_path) = crate::config::get_app_icon_path(pkg) {
            if app_icon_path.exists() {
                let _ = app_handle.emit("log-message", format!(
                    "[通知图标] WinRT Toast 使用图标: {}",
                    app_icon_path.to_string_lossy()
                ));
                toast = toast.icon(&app_icon_path, IconCrop::Square, "App Icon");
                icon_set = true;
            }
        }
    }

    if !icon_set {
        if let Ok(exe) = tauri::utils::platform::current_exe() {
            if let Some(dir) = exe.parent() {
                let icon_path = dir.join("icon.ico");
                if icon_path.exists() {
                    toast = toast.icon(&icon_path, IconCrop::Square, "App Icon");
                }
            }
        }
    }

    toast.show().map_err(|e| format!("WinRT Toast 展现失败: {:?}", e))?;

    // Toast 展示后 3 分钟清理操作中心残留；期间有新通知则 epoch 递增，旧任务自动放弃
    let my_epoch = app_handle
        .state::<NotifyState>()
        .cleanup_epoch
        .fetch_add(1, Ordering::SeqCst)
        + 1;
    let app_handle_owned = app_handle.clone();
    let app_id_cleanup = app_id.clone();

    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(180)).await;
        if app_handle_owned
            .state::<NotifyState>()
            .cleanup_epoch
            .load(Ordering::SeqCst)
            != my_epoch
        {
            return; // 后续通知已更新 epoch，由新任务负责清理
        }
        let ps_script = format!(
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null; [Windows.UI.Notifications.ToastNotificationManager]::History.Clear('{}')",
            app_id_cleanup
        );
        let _ = std::process::Command::new("powershell")
            .args(&["-NoProfile", "-Command", &ps_script])
            .creation_flags(NO_WINDOW)
            .output();
    });

    Ok(())
}

// ── 非 Windows: notify-rust ──

#[cfg(not(target_os = "windows"))]
fn send_notify_nix(title: &str, body: &str, package: Option<&str>) -> Result<(), String> {
    let mut notification = notify_rust::Notification::new();
    notification.summary(title);
    notification.body(body);

    if let Some(pkg) = package {
        if let Some(app_icon_path) = crate::config::get_app_icon_path(pkg) {
            notification.icon(&app_icon_path.to_string_lossy());
        }
    }

    notification
        .show()
        .map(|_| ())
        .map_err(|e| format!("notify-rust 发送失败: {:?}", e))
}

// ── Windows: 安装版检测 ──

#[cfg(target_os = "windows")]
fn is_installed(app_handle: &AppHandle) -> bool {
    use std::os::windows::process::CommandExt;
    const NO_WINDOW: u32 = 0x08000000;

    let exe_path = match tauri::utils::platform::current_exe() {
        Ok(p) => p.to_string_lossy().to_string(),
        Err(e) => {
            let _ = app_handle.emit("log-message", format!("[通知检测] 获取运行路径失败: {}", e));
            return false;
        }
    };

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
        let _ = app_handle.emit("log-message", "[通知检测] 注册表查询无结果，使用 PowerShell 通知");
        return false;
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let install_path = stdout
        .lines()
        .find(|l| l.contains("InstallLocation"))
        .and_then(|l| l.split("REG_SZ").nth(1))
        .map(|s| s.trim().trim_matches('"').trim())
        .unwrap_or("");

    if install_path.is_empty() {
        let _ = app_handle.emit("log-message", "[通知检测] 注册表中 InstallLocation 为空，使用 PowerShell 通知");
        return false;
    }

    let exe_lower = exe_path.to_lowercase().replace("\\\\?\\", "");
    let install_lower = install_path.to_lowercase();
    let matched = exe_lower.contains(&install_lower);

    let _ = app_handle.emit("log-message", format!("[通知检测] 运行路径: {}", exe_lower));
    let _ = app_handle.emit("log-message", format!("[通知检测] 安装路径: {}", install_lower));
    let _ = app_handle.emit("log-message", format!("[通知检测] 路径匹配: {}", matched));

    matched
}

// ── Windows: PowerShell BalloonTip 兜底 ──

#[cfg(target_os = "windows")]
fn send_powershell(app_handle: &AppHandle, title: &str, body: &str) {
    use std::os::windows::process::CommandExt;
    const NO_WINDOW: u32 = 0x08000000;

    let safe_title = title.replace('\'', "''");
    let safe_body = body.replace('\'', "''");

    let script = format!(
        "Add-Type -AssemblyName System.Windows.Forms; \
         $n = New-Object System.Windows.Forms.NotifyIcon; \
         $n.Icon = [System.Drawing.SystemIcons]::Information; \
         $n.Visible = $true; \
         $n.ShowBalloonTip(5000, '{}', '{}', [System.Windows.Forms.ToolTipIcon]::Info); \
         Start-Sleep -Seconds 2",
        safe_title, safe_body
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
