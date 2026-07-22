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
    #[serde(alias = "mac")]
    pub device_id: String,
    #[serde(default)]
    pub device_name: String,
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

/// 创建 keyring 条目
fn create_keyring_entry(device_id: &str, package: &str) -> Result<keyring::Entry, String> {
    let service = "ble-notification-sync";
    let username = format!("{}:{}", device_id, package);
    keyring::Entry::new(service, &username)
        .map_err(|e| format!("创建 keyring 条目失败: {}", e))
}

/// 存储 baseKey 到系统安全存储
/// Windows: Credential Manager, macOS: Keychain, Linux: Secret Service
pub fn store_base_key(device_id: &str, package: &str, key: &[u8; 32]) -> Result<(), String> {
    let entry = create_keyring_entry(device_id, package)?;
    let key_hex = hex::encode(key);
    entry.set_password(&key_hex)
        .map_err(|e| format!("存储密钥失败: {}", e))
}

/// 从系统安全存储读取 baseKey
pub fn get_base_key(device_id: &str, package: &str) -> Result<[u8; 32], String> {
    let entry = create_keyring_entry(device_id, package)?;
    let key_hex = entry.get_password()
        .map_err(|e| format!("获取密钥失败: {}", e))?;
    
    let bytes = hex::decode(&key_hex)
        .map_err(|e| format!("hex 解码失败: {}", e))?;
    
    if bytes.len() != 32 {
        return Err("密钥长度错误".to_string());
    }
    
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes);
    Ok(key)
}

/// 从系统安全存储删除 baseKey
pub fn delete_base_key(device_id: &str, package: &str) -> Result<(), String> {
    let entry = create_keyring_entry(device_id, package)?;
    entry.delete_credential()
        .map_err(|e| format!("删除密钥失败: {}", e))
}
