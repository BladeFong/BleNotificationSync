use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// 获取 AppData 配置目录：%APPDATA%/ble-notification-sync
fn config_dir() -> Result<PathBuf, String> {
    #[cfg(target_os = "windows")]
    {
        let appdata = std::env::var("APPDATA")
            .map_err(|_| "APPDATA not found".to_string())?;
        Ok(PathBuf::from(appdata).join("ble-notification-sync"))
    }
    #[cfg(not(target_os = "windows"))]
    {
        let home = std::env::var("HOME")
            .or_else(|_| std::env::var("USERPROFILE"))
            .unwrap_or_default();
        Ok(PathBuf::from(home).join(".config").join("ble-notification-sync"))
    }
}

fn config_path() -> Result<PathBuf, String> {
    Ok(config_dir()?.join("config.json"))
}

#[derive(Debug, Serialize, Deserialize, Default)]
pub struct AppConfig {
    #[serde(default)]
    pub silent_mode: bool,
    #[serde(default)]
    pub devices: Vec<DeviceEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceEntry {
    pub mac: String,
    pub app_name: String,
    pub package_name: String,
    pub paired_at: String,
}

/// 加载配置，文件不存在时返回默认值
pub fn load_config() -> AppConfig {
    let path = match config_path() {
        Ok(p) => p,
        Err(_) => return AppConfig::default(),
    };
    match fs::read_to_string(&path) {
        Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
        Err(_) => AppConfig::default(),
    }
}

/// 保存静默模式开关到配置（不覆盖设备列表）
pub fn save_silent_mode(enabled: bool) -> Result<(), String> {
    let mut config = load_config();
    config.silent_mode = enabled;
    save_config(&config)
}

/// 保存配置到 JSON 文件
pub fn save_config(config: &AppConfig) -> Result<(), String> {
    let dir = config_dir()?;
    fs::create_dir_all(&dir).map_err(|e| format!("无法创建配置目录: {}", e))?;
    let path = config_path()?;
    let content = serde_json::to_string_pretty(config)
        .map_err(|e| format!("序列化配置失败: {}", e))?;
    fs::write(&path, content).map_err(|e| format!("写入配置失败: {}", e))
}

/// 通过 Windows Credential Manager 存储 baseKey
/// Service: ble-notification-sync, Username: {mac}:{package}, Password: key_hex
#[cfg(target_os = "windows")]
pub fn store_base_key(mac: &str, package: &str, key: &[u8; 32]) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    let target = format!("BleNotificationSync/{}/{}", mac, package);
    let key_hex = hex::encode(key);

    let output = std::process::Command::new("cmdkey")
        .args(&[
            &format!("/generic:{}", target),
            &format!("/user:{}:{}", mac, package),
            &format!("/pass:{}", key_hex),
        ])
        .creation_flags(0x08000000)
        .output()
        .map_err(|e| format!("cmdkey 调用失败: {}", e))?;

    if output.status.success() {
        Ok(())
    } else {
        Err("cmdkey 执行失败".to_string())
    }
}

#[cfg(not(target_os = "windows"))]
pub fn store_base_key(_mac: &str, _package: &str, _key: &[u8; 32]) -> Result<(), String> {
    Err("Credential Manager only supported on Windows".to_string())
}

/// 从 Windows Credential Manager 读取 baseKey
#[cfg(target_os = "windows")]
pub fn get_base_key(mac: &str, package: &str) -> Result<[u8; 32], String> {
    use std::os::windows::process::CommandExt;
    let target = format!("BleNotificationSync/{}/{}", mac, package);

    // Natively load CredReadW using C# in PowerShell via Add-Type (independent of extra modules)
    // 使用 $args[0] 参数化传递 target，避免命令注入
    let ps_cmd = r#"$def = @'
using System;
using System.Runtime.InteropServices;
public class Cred {
    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode)]
    public static extern bool CredRead(string target, int type, int flags, out IntPtr ptr);
    [DllImport("advapi32.dll", EntryPoint = "CredFree")]
    public static extern void CredFree(IntPtr ptr);
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct PCREDENTIAL {
        public int Flags;
        public int Type;
        public string TargetName;
        public string Comment;
        public uint dwLowDateTime;
        public uint dwHighDateTime;
        public int CredentialBlobSize;
        public IntPtr CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public IntPtr Attributes;
        public string TargetAlias;
        public string UserName;
    }
    public static string Get(string target) {
        IntPtr ptr;
        if (CredRead(target, 1, 0, out ptr)) {
            PCREDENTIAL cred = (PCREDENTIAL)Marshal.PtrToStructure(ptr, typeof(PCREDENTIAL));
            string password = Marshal.PtrToStringUni(cred.CredentialBlob, cred.CredentialBlobSize / 2);
            CredFree(ptr);
            return password;
        }
        return "";
    }
}
'@; Add-Type -TypeDefinition $def; [Cred]::Get($args[0])"#;
    let output = std::process::Command::new("powershell")
        .args(&["-NoProfile", "-Command", ps_cmd, "-args", &target])
        .creation_flags(0x08000000)
        .output()
        .map_err(|e| format!("PowerShell 调用失败: {}", e))?;

    let key_hex = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if key_hex.is_empty() || key_hex.len() != 64 {
        return Err("获取 key 失败".to_string());
    }

    let bytes = hex::decode(&key_hex).map_err(|e| format!("hex 解码失败: {}", e))?;
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes);
    Ok(key)
}

#[cfg(not(target_os = "windows"))]
pub fn get_base_key(_mac: &str, _package: &str) -> Result<[u8; 32], String> {
    Err("Credential Manager only supported on Windows".to_string())
}

/// 从 Windows Credential Manager 删除 baseKey
#[cfg(target_os = "windows")]
pub fn delete_base_key(mac: &str, package: &str) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    let target = format!("BleNotificationSync/{}/{}", mac, package);

    std::process::Command::new("cmdkey")
        .args(&[&format!("/delete:{}", target)])
        .creation_flags(0x08000000)
        .output()
        .map_err(|e| format!("cmdkey 删除失败: {}", e))?;

    Ok(())
}

#[cfg(not(target_os = "windows"))]
pub fn delete_base_key(_mac: &str, _package: &str) -> Result<(), String> {
    Err("Credential Manager only supported on Windows".to_string())
}
