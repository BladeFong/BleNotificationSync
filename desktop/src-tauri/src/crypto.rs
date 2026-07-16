use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use aes_gcm::aead::Aead;
use hkdf::Hkdf;
use sha2::Sha256;
use rand::RngCore;

/// 从包名和配对随机数派生 baseKey（HKDF-SHA256）
/// salt = "BleNotificationSync"
/// IKM  = package_name + random（包名字节拼接 32 字节随机数）
/// info = ""（对齐 Android 端）
pub fn derive_key(package_name: &str, random: &[u8; 32]) -> [u8; 32] {
    let mut ikm = package_name.as_bytes().to_vec();
    ikm.extend_from_slice(random);
    let hk = Hkdf::<Sha256>::new(Some(b"BleNotificationSync"), &ikm);
    let mut key = [0u8; 32];
    hk.expand(b"", &mut key).expect("HKDF expand failed");
    key
}

/// Encrypt plaintext with AES-256-GCM
/// Returns (nonce, ciphertext) where ciphertext includes the auth tag
pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Result<(Vec<u8>, Vec<u8>), String> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| format!("Failed to create cipher: {}", e))?;
    
    let mut nonce_bytes = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    
    let ciphertext = cipher.encrypt(nonce, plaintext)
        .map_err(|e| format!("Encryption failed: {}", e))?;
    
    Ok((nonce_bytes.to_vec(), ciphertext))
}

/// Decrypt ciphertext with AES-256-GCM
pub fn decrypt(key: &[u8; 32], nonce: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, String> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| format!("Failed to create cipher: {}", e))?;
    
    let nonce = Nonce::from_slice(nonce);
    
    let plaintext = cipher.decrypt(nonce, ciphertext)
        .map_err(|e| format!("Decryption failed (wrong key or tampered data): {}", e))?;
    
    Ok(plaintext)
}

#[cfg(test)]
mod tests {
    use super::*;

    const RANDOM_A: [u8; 32] = [0xAA; 32];
    const RANDOM_B: [u8; 32] = [0xBB; 32];

    #[test]
    fn test_key_derivation_deterministic() {
        let key1 = derive_key("com.example.app", &RANDOM_A);
        let key2 = derive_key("com.example.app", &RANDOM_A);
        assert_eq!(key1, key2);
    }

    #[test]
    fn test_different_packages_different_keys() {
        let key1 = derive_key("com.app.one", &RANDOM_A);
        let key2 = derive_key("com.app.two", &RANDOM_A);
        assert_ne!(key1, key2);
    }

    #[test]
    fn test_different_random_different_keys() {
        let key1 = derive_key("com.example.app", &RANDOM_A);
        let key2 = derive_key("com.example.app", &RANDOM_B);
        assert_ne!(key1, key2);
    }

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = derive_key("com.example.app", &RANDOM_A);
        let plaintext = b"Hello, BLE Notification Sync!";

        let (nonce, ciphertext) = encrypt(&key, plaintext).unwrap();
        let decrypted = decrypt(&key, &nonce, &ciphertext).unwrap();

        assert_eq!(plaintext.to_vec(), decrypted);
    }

    #[test]
    fn test_wrong_key_fails() {
        let key1 = derive_key("com.app.one", &RANDOM_A);
        let key2 = derive_key("com.app.two", &RANDOM_A);
        let plaintext = b"Secret message";

        let (nonce, ciphertext) = encrypt(&key1, plaintext).unwrap();
        let result = decrypt(&key2, &nonce, &ciphertext);

        assert!(result.is_err());
    }
}
