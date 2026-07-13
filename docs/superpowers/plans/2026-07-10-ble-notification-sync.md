# BLE Notification Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现跨平台 BLE 闹钟通知同步 SDK，包含 Android SDK、Windows 端和 macOS 端，支持 HKDF + AES-CCM 加密。

**Architecture:** Android 端作为 GATT Client，PC/Mac 端作为 GATT Server。每次通知独立连接，用完即断。使用 LibTomCrypt 源码集成实现加密。

**Tech Stack:** Kotlin (Android), C# / .NET 8 (Windows), Swift (macOS), LibTomCrypt (加密)

## Global Constraints

- Android minSdkVersion: API 23 (Android 6.0 Marshmallow)
- Windows minVersion: Windows 10 1709+ (Build 16299)
- LibTomCrypt 必须以源码方式集成（third_party/libtomcrypt/）
- AES-CCM 认证加密，密钥由 HKDF-SHA256 从包名派生
- 三端加密行为必须一致
- BLE MTU 协商目标 247 字节
- 单次通知 payload 最大 240 字节
- 图标二进制直传（不 Base64），最大 60KB

---

## File Structure

```
BleNotificationSync/
├── third_party/
│   └── libtomcrypt/              # LibTomCrypt 源码
├── android/
│   ├── sdk/
│   │   ├── src/main/
│   │   │   ├── java/com/ble/notification/
│   │   │   │   ├── crypto/
│   │   │   │   │   ├── AesCcmCrypto.kt      # AES-CCM 加解密
│   │   │   │   │   └── KeyDerivation.kt     # HKDF 密钥派生
│   │   │   │   ├── protocol/
│   │   │   │   │   ├── FrameEncoder.kt      # 帧编码
│   │   │   │   │   ├── FrameDecoder.kt      # 帧解码
│   │   │   │   │   └── MessageType.kt       # 消息类型定义
│   │   │   │   ├── ble/
│   │   │   │   │   ├── BleClient.kt         # GATT 连接管理
│   │   │   │   │   └── MtuNegotiator.kt     # MTU 协商
│   │   │   │   ├── pairing/
│   │   │   │   │   └── PairingManager.kt    # 配对状态机
│   │   │   │   ├── qr/
│   │   │   │   │   ├── QrDecoder.kt         # 解码层
│   │   │   │   │   ├── QrScanner.kt         # 相机层
│   │   │   │   │   └── QrScannerFragment.kt # UI 层
│   │   │   │   └── sdk/
│   │   │   │       └── BleNotificationSDK.kt # SDK 主入口
│   │   │   └── native/
│   │   │       └── libtomcrypt_jni.c        # JNI 桥接
│   │   └── build.gradle.kts
│   └── sample/
├── windows/
│   └── BleNotificationWin/
│       ├── Crypto/
│       │   ├── AesCcmCrypto.cs
│       │   └── KeyDerivation.cs
│       ├── Gatt/
│       │   └── GattServerService.cs
│       ├── Storage/
│       │   ├── PairingStorage.cs
│       │   └── KeyStorage.cs
│       └── UI/
│           ├── TrayApp.cs
│           └── NotificationManager.cs
├── macos/
│   └── BleNotificationMac/
│       ├── Crypto/
│       │   ├── AesCcmCrypto.swift
│       │   └── KeyDerivation.swift
│       ├── BLE/
│       │   └── PeripheralManager.swift
│       ├── Storage/
│       │   ├── PairingStorage.swift
│       │   └── KeyStorage.swift
│       └── UI/
│           ├── MenuBarApp.swift
│           └── NotificationService.swift
└── docs/
    └── superpowers/
        ├── specs/
        │   └── 2026-07-10-ble-notification-sync-design.md
        └── plans/
            └── 2026-07-10-ble-notification-sync.md
```

---

## Task 1: 下载 LibTomCrypt 源码

**Files:**
- Create: `third_party/libtomcrypt/` (git submodule or direct download)

- [ ] **Step 1: 下载 LibTomCrypt 和 libtommath 源码**

```bash
mkdir -p third_party
cd third_party
git clone https://github.com/libtom/libtomcrypt.git
git clone https://github.com/libtom/libtommath.git
```

- [ ] **Step 2: 验证源码结构**

确认 `src/headers/tomcrypt.h` 和 `src/misc/tomcrypt_misc.c` 存在。

- [ ] **Step 3: 添加 .gitignore 规则**

```bash
# 在 .gitignore 中添加
third_party/libtomcrypt/
```

- [ ] **Step 4: Commit**

```bash
git add third_party .gitignore
git commit -m "chore: add LibTomCrypt source submodule"
```

---

## Task 2: Android 端 - LibTomCrypt JNI 桥接

**Files:**
- Create: `android/sdk/src/main/native/libtomcrypt_jni.c`
- Create: `android/sdk/src/main/java/com/ble/notification/crypto/NativeCrypto.kt`

**Interfaces:**
- Produces: `NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)` → `ByteArray`
- Produces: `NativeCrypto.aesCcmDecrypt(key, nonce, ciphertext)` → `ByteArray?`
- Produces: `NativeCrypto.hkdfSha256(salt, info, length)` → `ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/sdk/src/test/java/com/ble/notification/crypto/NativeCryptoTest.kt
import org.junit.Test
import org.junit.Assert.*

class NativeCryptoTest {
    @Test
    fun `aesCcm encrypt then decrypt returns original`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 100).toByte() }
        val plaintext = "Hello, BLE!".toByteArray()

        val ciphertext = NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)
        val decrypted = NativeCrypto.aesCcmDecrypt(key, nonce, ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `aesCcm decrypt with wrong key returns null`() {
        val key1 = ByteArray(32) { it.toByte() }
        val key2 = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 100).toByte() }
        val plaintext = "Hello, BLE!".toByteArray()

        val ciphertext = NativeCrypto.aesCcmEncrypt(key1, nonce, plaintext)
        val decrypted = NativeCrypto.aesCcmDecrypt(key2, nonce, ciphertext)

        assertNull(decrypted)
    }

    @Test
    fun `hkdfSha256 produces deterministic output`() {
        val salt = "BleNotificationSync".toByteArray()
        val info = "com.test.app".toByteArray()

        val key1 = NativeCrypto.hkdfSha256(salt, info, 32)
        val key2 = NativeCrypto.hkdfSha256(salt, info, 32)

        assertArrayEquals(key1, key2)
        assertEquals(32, key1.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdk:test`
Expected: FAIL with "NativeCrypto not found"

- [ ] **Step 3: Write JNI bridge (C)**

```c
// android/sdk/src/main/native/libtomcrypt_jni.c
#include <jni.h>
#include <string.h>
#include "tomcrypt.h"

// Global state for LibTomCrypt
static int registered = 0;

static void ensure_registered() {
    if (!registered) {
        register_all_cipher();
        register_all_hash();
        registered = 1;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_aesCcmEncrypt(
    JNIEnv *env, jclass clazz,
    jbyteArray key, jbyteArray nonce, jbyteArray plaintext) {
    
    ensure_registered();
    
    jsize key_len = (*env)->GetArrayLength(env, key);
    jsize nonce_len = (*env)->GetArrayLength(env, nonce);
    jsize plain_len = (*env)->GetArrayLength(env, plaintext);
    
    jbyte *key_data = (*env)->GetByteArrayElements(env, key, NULL);
    jbyte *nonce_data = (*env)->GetByteArrayElements(env, nonce, NULL);
    jbyte *plain_data = (*env)->GetByteArrayElements(env, plaintext, NULL);
    
    // Output: ciphertext + 16 byte tag
    unsigned char *out = malloc(plain_len + 16);
    unsigned long out_len = plain_len + 16;
    
    int err = aes_ccm_memory(
        key_data, key_len,
        nonce_data, nonce_len,
        plain_data, plain_len,
        out, &out_len,
        out + plain_len, 16  // tag at end
    );
    
    (*env)->ReleaseByteArrayElements(env, key, key_data, 0);
    (*env)->ReleaseByteArrayElements(env, nonce, nonce_data, 0);
    (*env)->ReleaseByteArrayElements(env, plaintext, plain_data, 0);
    
    if (err != CRYPT_OK) {
        free(out);
        return NULL;
    }
    
    jbyteArray result = (*env)->NewByteArray(env, out_len);
    (*env)->SetByteArrayRegion(env, result, 0, out_len, (jbyte*)out);
    free(out);
    
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_aesCcmDecrypt(
    JNIEnv *env, jclass clazz,
    jbyteArray key, jbyteArray nonce, jbyteArray ciphertext) {
    
    ensure_registered();
    
    jsize key_len = (*env)->GetArrayLength(env, key);
    jsize nonce_len = (*env)->GetArrayLength(env, nonce);
    jsize cipher_len = (*env)->GetArrayLength(env, ciphertext);
    
    if (cipher_len < 16) return NULL;  // Too short for tag
    
    jbyte *key_data = (*env)->GetByteArrayElements(env, key, NULL);
    jbyte *nonce_data = (*env)->GetByteArrayElements(env, nonce, NULL);
    jbyte *cipher_data = (*env)->GetByteArrayElements(env, ciphertext, NULL);
    
    jsize plain_len = cipher_len - 16;
    unsigned char *out = malloc(plain_len);
    unsigned long out_len = plain_len;
    
    int err = aes_ccm_memory(
        key_data, key_len,
        nonce_data, nonce_len,
        cipher_data, plain_len,  // ciphertext without tag
        out, &out_len,
        cipher_data + plain_len, 16  // tag
    );
    
    (*env)->ReleaseByteArrayElements(env, key, key_data, 0);
    (*env)->ReleaseByteArrayElements(env, nonce, nonce_data, 0);
    (*env)->ReleaseByteArrayElements(env, ciphertext, cipher_data, 0);
    
    if (err != CRYPT_OK) {
        free(out);
        return NULL;  // Decryption failed (wrong key or tampered)
    }
    
    jbyteArray result = (*env)->NewByteArray(env, out_len);
    (*env)->SetByteArrayRegion(env, result, 0, out_len, (jbyte*)out);
    free(out);
    
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_hkdfSha256(
    JNIEnv *env, jclass clazz,
    jbyteArray salt, jbyteArray info, jint length) {
    
    ensure_registered();
    
    jsize salt_len = (*env)->GetArrayLength(env, salt);
    jsize info_len = (*env)->GetArrayLength(env, info);
    
    jbyte *salt_data = (*env)->GetByteArrayElements(env, salt, NULL);
    jbyte *info_data = (*env)->GetByteArrayElements(env, info, NULL);
    
    unsigned char *out = malloc(length);
    
    int err = hkdf(
        &sha256_desc,
        salt_data, salt_len,
        info_data, info_len,
        out, length
    );
    
    (*env)->ReleaseByteArrayElements(env, salt, salt_data, 0);
    (*env)->ReleaseByteArrayElements(env, info, info_data, 0);
    
    if (err != CRYPT_OK) {
        free(out);
        return NULL;
    }
    
    jbyteArray result = (*env)->NewByteArray(env, length);
    (*env)->SetByteArrayRegion(env, result, 0, length, (jbyte*)out);
    free(out);
    
    return result;
}
```

- [ ] **Step 4: Write Kotlin wrapper**

```kotlin
// android/sdk/src/main/java/com/ble/notification/crypto/NativeCrypto.kt
package com.ble.notification.crypto

object NativeCrypto {
    init {
        System.loadLibrary("tomcrypt_jni")
    }
    
    @JvmStatic
    external fun aesCcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray?
    
    @JvmStatic
    external fun aesCcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray?
    
    @JvmStatic
    external fun hkdfSha256(salt: ByteArray, info: ByteArray, length: Int): ByteArray?
}
```

- [ ] **Step 5: Configure CMake build**

```cmake
# android/sdk/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.10)
project(tomcrypt_jni)

# LibTomMath sources
set(TOMMATH_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party/libtommath)

add_library(tommath STATIC
    ${TOMMATH_DIR}/bn_s_mp_reverse.c
    ${TOMMATH_DIR}/bn_fast_mp_montgomery_setup.c
    ${TOMMATH_DIR}/bn_fast_mp_montgomery_reduce.c
    ${TOMMATH_DIR}/bn_fast_s_mull.c
    ${TOMMATH_DIR}/bn_fast_s_sq.c
    ${TOMMATH_DIR}/bn_mp_add.c
    ${TOMMATH_DIR}/bn_mp_clamp.c
    ${TOMMATH_DIR}/bn_mp_clear.c
    ${TOMMATH_DIR}/bn_mp_cmp_d.c
    ${TOMMATH_DIR}/bn_mp_cmp_mag.c
    ${TOMMATH_DIR}/bn_mp_copy.c
    ${TOMMATH_DIR}/bn_mp_count_bits.c
    ${TOMMATH_DIR}/bn_mp_div.c
    ${TOMMATH_DIR}/bn_mp_div_2.c
    ${TOMMATH_DIR}/bn_mp_div_2d.c
    ${TOMMATH_DIR}/bn_mp_exptmod.c
    ${TOMMATH_DIR}/bn_mp_exptmod_fast.c
    ${TOMMATH_DIR}/bn_mp_grow.c
    ${TOMMATH_DIR}/bn_mp_init.c
    ${TOMMATH_DIR}/bn_mp_init_copy.c
    ${TOMMATH_DIR}/bn_mp_init_set.c
    ${TOMMATH_DIR}/bn_mp_init_size.c
    ${TOMMATH_DIR}/bn_mp_montgomery_calc_normalization.c
    ${TOMMATH_DIR}/bn_mp_montgomery_reduce.c
    ${TOMMATH_DIR}/bn_mp_montgomery_setup.c
    ${TOMMATH_DIR}/bn_mp_mul.c
    ${TOMMATH_DIR}/bn_mp_mul_2.c
    ${TOMMATH_DIR}/bn_mp_mul_2d.c
    ${TOMMATH_DIR}/bn_mp_mulmod.c
    ${TOMMATH_DIR}/bn_mp_set.c
    ${TOMMATH_DIR}/bn_mp_set_int.c
    ${TOMMATH_DIR}/bn_mp_shrink.c
    ${TOMMATH_DIR}/bn_mp_sqr.c
    ${TOMMATH_DIR}/bn_mp_sqrmod.c
    ${TOMMATH_DIR}/bn_mp_sub.c
    ${TOMMATH_DIR}/bn_mp_zero.c
    ${TOMMATH_DIR}/bncore.c
)

target_include_directories(tommath PUBLIC ${TOMMATH_DIR})

# LibTomCrypt sources
set(TOMCRYPT_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party/libtomcrypt/src)

add_library(tomcrypt STATIC
    ${TOMCRYPT_DIR}/misc/crypt/crypt.c
    ${TOMCRYPT_DIR}/misc/crypt/crypt_register_cipher.c
    ${TOMCRYPT_DIR}/misc/crypt/crypt_register_hash.c
    ${TOMCRYPT_DIR}/misc/crypt/crypt_find_hash.c
    ${TOMCRYPT_DIR}/misc/crypt/crypt_find_cipher.c
    ${TOMCRYPT_DIR}/misc/pkcs5/pkcs5_hkdf.c
    ${TOMCRYPT_DIR}/aes/aes.c
    ${TOMCRYPT_DIR}/hashes/sha256.c
    ${TOMCRYPT_DIR}/mac/ccm/ccm_memory.c
)

target_compile_definitions(tomcrypt PRIVATE
    LTC_NO_ASM
    LTC_SOURCE
    USE_LTM
    LTM_DESC
)

target_include_directories(tomcrypt PUBLIC
    ${TOMCRYPT_DIR}/headers
    ${TOMCRYPT_DIR}/misc/crypt
    ${TOMMATH_DIR}
)

add_library(tomcrypt_jni SHARED libtomcrypt_jni.c)
target_link_libraries(tomcrypt_jni tomcrypt tommath)
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :sdk:test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add android/sdk/src/main/native android/sdk/src/main/java/com/ble/notification/crypto
git commit -m "feat(android): add LibTomCrypt JNI bridge for AES-CCM and HKDF"
```

---

## Task 3: Android 端 - 密钥派生服务

**Files:**
- Create: `android/sdk/src/main/java/com/ble/notification/crypto/KeyDerivation.kt`
- Test: `android/sdk/src/test/java/com/ble/notification/crypto/KeyDerivationTest.kt`

**Interfaces:**
- Consumes: `NativeCrypto.hkdfSha256(salt, info, length)`
- Produces: `KeyDerivation.deriveKey(packageName)` → `ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/sdk/src/test/java/com/ble/notification/crypto/KeyDerivationTest.kt
import org.junit.Test
import org.junit.Assert.*

class KeyDerivationTest {
    @Test
    fun `deriveKey produces 32-byte key`() {
        val key = KeyDerivation.deriveKey("com.test.app")
        assertEquals(32, key.size)
    }

    @Test
    fun `deriveKey is deterministic`() {
        val key1 = KeyDerivation.deriveKey("com.test.app")
        val key2 = KeyDerivation.deriveKey("com.test.app")
        assertArrayEquals(key1, key2)
    }

    @Test
    fun `different packages produce different keys`() {
        val key1 = KeyDerivation.deriveKey("com.app.one")
        val key2 = KeyDerivation.deriveKey("com.app.two")
        assertFalse(assertArrayEquals(key1, key2))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdk:test --tests "KeyDerivationTest"`
Expected: FAIL with "KeyDerivation not found"

- [ ] **Step 3: Write implementation**

```kotlin
// android/sdk/src/main/java/com/ble/notification/crypto/KeyDerivation.kt
package com.ble.notification.crypto

object KeyDerivation {
    private val SALT = "BleNotificationSync".toByteArray()
    private const val KEY_LENGTH = 32

    fun deriveKey(packageName: String): ByteArray {
        return NativeCrypto.hkdfSha256(SALT, packageName.toByteArray(), KEY_LENGTH)
            ?: throw RuntimeException("HKDF key derivation failed")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdk:test --tests "KeyDerivationTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/sdk/src/main/java/com/ble/notification/crypto/KeyDerivation.kt
git commit -m "feat(android): add HKDF key derivation service"
```

---

## Task 4: Android 端 - 加密服务

**Files:**
- Create: `android/sdk/src/main/java/com/ble/notification/crypto/AesCcmCrypto.kt`
- Test: `android/sdk/src/test/java/com/ble/notification/crypto/AesCcmCryptoTest.kt`

**Interfaces:**
- Consumes: `NativeCrypto.aesCcmEncrypt/Decrypt`
- Consumes: `KeyDerivation.deriveKey`
- Produces: `AesCcmCrypto.encrypt(packageName, plaintext)` → `Pair<ByteArray, ByteArray>` (nonce, ciphertext)
- Produces: `AesCcmCrypto.decrypt(packageName, nonce, ciphertext)` → `ByteArray?`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/sdk/src/test/java/com/ble/notification/crypto/AesCcmCryptoTest.kt
import org.junit.Test
import org.junit.Assert.*

class AesCcmCryptoTest {
    @Test
    fun `encrypt and decrypt roundtrip`() {
        val plaintext = """{"title":"Test","body":"Hello"}""".toByteArray()
        val (nonce, ciphertext) = AesCcmCrypto.encrypt("com.test.app", plaintext)
        
        val decrypted = AesCcmCrypto.decrypt("com.test.app", nonce, ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt with wrong package returns null`() {
        val plaintext = "test".toByteArray()
        val (nonce, ciphertext) = AesCcmCrypto.encrypt("com.app.one", plaintext)
        
        val decrypted = AesCcmCrypto.decrypt("com.app.two", nonce, ciphertext)
        assertNull(decrypted)
    }

    @Test
    fun `nonce is 12 bytes`() {
        val (_, ciphertext, nonce) = AesCcmCrypto.encrypt("com.test.app", "test".toByteArray())
        assertEquals(12, nonce.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdk:test --tests "AesCcmCryptoTest"`
Expected: FAIL

- [ ] **Step 3: Write implementation**

```kotlin
// android/sdk/src/main/java/com/ble/notification/crypto/AesCcmCrypto.kt
package com.ble.notification.crypto

import java.security.SecureRandom

object AesCcmCrypto {
    private const val NONCE_SIZE = 12

    data class EncryptedPayload(val nonce: ByteArray, val ciphertext: ByteArray)

    fun encrypt(packageName: String, plaintext: ByteArray): EncryptedPayload {
        val key = KeyDerivation.deriveKey(packageName)
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val ciphertext = NativeCrypto.aesCcmEncrypt(key, nonce, plaintext)
            ?: throw RuntimeException("AES-CCM encryption failed")
        
        return EncryptedPayload(nonce, ciphertext)
    }

    fun decrypt(packageName: String, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        val key = KeyDerivation.deriveKey(packageName)
        return NativeCrypto.aesCcmDecrypt(key, nonce, ciphertext)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdk:test --tests "AesCcmCryptoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/sdk/src/main/java/com/ble/notification/crypto/AesCcmCrypto.kt
git commit -m "feat(android): add AES-CCM encryption service"
```

---

## Task 5: Android 端 - 协议帧编码器

**Files:**
- Create: `android/sdk/src/main/java/com/ble/notification/protocol/MessageType.kt`
- Create: `android/sdk/src/main/java/com/ble/notification/protocol/FrameEncoder.kt`
- Test: `android/sdk/src/test/java/com/ble/notification/protocol/FrameEncoderTest.kt`

**Interfaces:**
- Consumes: `AesCcmCrypto.encrypt`
- Produces: `FrameEncoder.encode NOTIFY` → `ByteArray`
- Produces: `FrameEncoder.encode REGISTER` → `ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/sdk/src/test/java/com/ble/notification/protocol/FrameEncoderTest.kt
import org.junit.Test
import org.junit.Assert.*

class FrameEncoderTest {
    @Test
    fun `encode REGISTER frame has correct magic and type`() {
        val frame = FrameEncoder.encodeRegister("JustNow", "com.test.app")
        
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0xBB.toByte(), frame[1])
        assertEquals(MessageType.REGISTER.value, frame[2])
    }

    @Test
    fun `encode NOTIFY frame with encryption`() {
        val plaintext = """{"title":"Test","body":"Hello"}""".toByteArray()
        val frame = FrameEncoder.encodeNotify(
            packageName = "com.test.app",
            title = "Test",
            body = "Hello",
            timestamp = System.currentTimeMillis()
        )
        
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0xBB.toByte(), frame[1])
        assertEquals(MessageType.NOTIFY.value, frame[2])
    }

    @Test
    fun `single frame has Seq=0 TotalSeq=1`() {
        val frame = FrameEncoder.encodeRegister("App", "com.test")
        
        assertEquals(0, frame[3].toInt())  // Seq
        assertEquals(1, frame[4].toInt())  // TotalSeq
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdk:test --tests "FrameEncoderTest"`
Expected: FAIL

- [ ] **Step 3: Write implementation**

```kotlin
// android/sdk/src/main/java/com/ble/notification/protocol/MessageType.kt
package com.ble.notification.protocol

enum class MessageType(val value: Byte) {
    REGISTER(0x01),
    NOTIFY(0x02),
    ACK(0x03),
    ICON_DATA(0x04),
    ICON_END(0x05)
}
```

```kotlin
// android/sdk/src/main/java/com/ble/notification/protocol/FrameEncoder.kt
package com.ble.notification.protocol

import com.ble.notification.crypto.AesCcmCrypto
import org.json.JSONObject

object FrameEncoder {
    private val MAGIC = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    private const val HEADER_SIZE = 5  // Magic(2) + MsgType(1) + Seq(1) + TotalSeq(1)

    fun encodeRegister(appName: String, packageName: String): ByteArray {
        val json = JSONObject().apply {
            put("app_name", appName)
            put("package", packageName)
        }.toString().toByteArray()
        
        return buildFrame(MessageType.REGISTER, 0, 1, json)
    }

    fun encodeNotify(
        packageName: String,
        title: String,
        body: String,
        timestamp: Long
    ): ByteArray {
        val plaintext = JSONObject().apply {
            put("title", title)
            put("body", body)
            put("timestamp", timestamp)
        }.toString().toByteArray()
        
        val (nonce, ciphertext) = AesCcmCrypto.encrypt(packageName, plaintext)
        
        // Frame: Header + PackageLen(1) + Package + Nonce + Ciphertext
        val packageBytes = packageName.toByteArray()
        val frame = ByteArray(HEADER_SIZE + 1 + packageBytes.size + nonce.size + ciphertext.size)
        
        // Header
        System.arraycopy(MAGIC, 0, frame, 0, 2)
        frame[2] = MessageType.NOTIFY.value
        frame[3] = 0  // Seq
        frame[4] = 1  // TotalSeq
        
        // Package
        frame[5] = packageBytes.size.toByte()
        System.arraycopy(packageBytes, 0, frame, 6, packageBytes.size)
        
        // Nonce
        val nonceOffset = 6 + packageBytes.size
        System.arraycopy(nonce, 0, frame, nonceOffset, nonce.size)
        
        // Ciphertext
        val cipherOffset = nonceOffset + nonce.size
        System.arraycopy(ciphertext, 0, frame, cipherOffset, ciphertext.size)
        
        return frame
    }

    private fun buildFrame(type: MessageType, seq: Int, totalSeq: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_SIZE + payload.size)
        System.arraycopy(MAGIC, 0, frame, 0, 2)
        frame[2] = type.value
        frame[3] = seq.toByte()
        frame[4] = totalSeq.toByte()
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.size)
        return frame
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdk:test --tests "FrameEncoderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/sdk/src/main/java/com/ble/notification/protocol
git commit -m "feat(android): add protocol frame encoder with encryption"
```

---

## Task 6: Android 端 - 协议帧解码器

**Files:**
- Create: `android/sdk/src/main/java/com/ble/notification/protocol/FrameDecoder.kt`
- Test: `android/sdk/src/test/java/com/ble/notification/protocol/FrameDecoderTest.kt`

**Interfaces:**
- Consumes: `AesCcmCrypto.decrypt`
- Produces: `FrameDecoder.decode(data)` → `Frame?`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/sdk/src/test/java/com/ble/notification/protocol/FrameDecoderTest.kt
import org.junit.Test
import org.junit.Assert.*

class FrameDecoderTest {
    @Test
    fun `decode REGISTER frame`() {
        val encoded = FrameEncoder.encodeRegister("TestApp", "com.test.app")
        val frame = FrameDecoder.decode(encoded)
        
        assertNotNull(frame)
        assertEquals(MessageType.REGISTER, frame?.type)
    }

    @Test
    fun `decode invalid magic returns null`() {
        val data = byteArrayOf(0x00, 0x00, 0x01, 0, 1)
        assertNull(FrameDecoder.decode(data))
    }

    @Test
    fun `decode too short data returns null`() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        assertNull(FrameDecoder.decode(data))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdk:test --tests "FrameDecoderTest"`
Expected: FAIL

- [ ] **Step 3: Write implementation**

```kotlin
// android/sdk/src/main/java/com/ble/notification/protocol/FrameDecoder.kt
package com.ble.notification.protocol

import com.ble.notification.crypto.AesCcmCrypto
import org.json.JSONObject

data class Frame(
    val type: MessageType,
    val seq: Int,
    val totalSeq: Int,
    val payload: ByteArray? = null
)

object FrameDecoder {
    private val MAGIC = byteArrayOf(0xAA.toByte(), 0xBB.toByte())

    fun decode(data: ByteArray): Frame? {
        if (data.size < 5) return null
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1]) return null
        
        val type = MessageType.values().find { it.value == data[2] } ?: return null
        val seq = data[3].toInt() and 0xFF
        val totalSeq = data[4].toInt() and 0xFF
        
        val payload = when (type) {
            MessageType.REGISTER -> decodeRegisterPayload(data)
            MessageType.NOTIFY -> decodeNotifyPayload(data)
            MessageType.ACK -> decodeAckPayload(data)
            else -> null
        }
        
        return Frame(type, seq, totalSeq, payload)
    }

    private fun decodeRegisterPayload(data: ByteArray): ByteArray? {
        return if (data.size > 5) data.copyOfRange(5, data.size) else null
    }

    private fun decodeNotifyPayload(data: ByteArray): ByteArray? {
        if (data.size < 6) return null
        
        val packageLen = data[5].toInt() and 0xFF
        if (data.size < 6 + packageLen) return null
        
        val packageName = String(data, 6, packageLen)
        val nonceOffset = 6 + packageLen
        val nonce = data.copyOfRange(nonceOffset, nonceOffset + 12)
        val ciphertext = data.copyOfRange(nonceOffset + 12, data.size)
        
        return AesCcmCrypto.decrypt(packageName, nonce, ciphertext)
    }

    private fun decodeAckPayload(data: ByteArray): ByteArray? {
        return if (data.size > 5) data.copyOfRange(5, data.size) else null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdk:test --tests "FrameDecoderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/sdk/src/main/java/com/ble/notification/protocol/FrameDecoder.kt
git commit -m "feat(android): add protocol frame decoder with decryption"
```

---

## Task 7: Android 端 - BLE 客户端

**Files:**
- Create: `android/sdk/src/main/java/com/ble/notification/ble/BleClient.kt`
- Test: `android/sdk/src/test/java/com/ble/notification/ble/BleClientTest.kt` (mock BLE)

**Interfaces:**
- Consumes: `FrameEncoder.encode*`
- Produces: `BleClient.connect(mac)` → `Connection`
- Produces: `Connection.send(data)` → `Boolean`

- [ ] **Step 1: Write the failing test (mock)**

```kotlin
// android/sdk/src/test/java/com/ble/notification/ble/BleClientTest.kt
import org.junit.Test
import org.junit.Assert.*

class BleClientTest {
    @Test
    fun `parse QR code extracts MAC and UUID`() {
        val qr = "ble://pair?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"
        val result = BleClient.parseQrCode(qr)
        
        assertEquals("AA:BB:CC:DD:EE:FF", result?.mac)
        assertEquals("0000A1B2-0000-1000-8000-00805F9B34FB", result?.uuid)
    }

    @Test
    fun `parse invalid QR returns null`() {
        assertNull(BleClient.parseQrCode("invalid"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdk:test --tests "BleClientTest"`
Expected: FAIL

- [ ] **Step 3: Write implementation**

```kotlin
// android/sdk/src/main/java/com/ble/notification/ble/BleClient.kt
package com.ble.notification.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.Uri
import java.util.UUID

class BleClient(private val context: Context) {
    companion object {
        val SERVICE_UUID = UUID.fromString("0000A1B2-0000-1000-8000-00805F9B34FB")
        val WRITE_UUID = UUID.fromString("0000C3D4-0000-1000-8000-00805F9B34FB")
        private const val MTU_SIZE = 247
    }

    data class QrResult(val mac: String, val uuid: String)

    fun parseQrCode(qr: String): QrResult? {
        return try {
            val uri = Uri.parse(qr)
            val mac = uri.getQueryParameter("mac") ?: return null
            val uuid = uri.getQueryParameter("uuid") ?: return null
            QrResult(mac, uuid)
        } catch (e: Exception) {
            null
        }
    }

    fun connect(mac: String, callback: ConnectionCallback) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val device = adapter.getRemoteDevice(mac)
        
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else {
                    callback.onError("Connection failed: $status")
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.requestMtu(MTU_SIZE)
                } else {
                    callback.onError("Service discovery failed")
                }
            }
            
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    callback.onReady(gatt)
                } else {
                    callback.onError("MTU negotiation failed")
                }
            }
        })
    }

    interface ConnectionCallback {
        fun onReady(gatt: BluetoothGatt)
        fun onError(error: String)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdk:test --tests "BleClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/sdk/src/main/java/com/ble/notification/ble
git commit -m "feat(android): add BLE client with QR parsing and MTU negotiation"
```

---

## Task 8: Android 端 - 扫码分层实现

**Files:**
- Create: `android/sdk/src/main/java/com/ble/notification/qr/QrDecoder.kt`
- Create: `android/sdk/src/main/java/com/ble/notification/qr/QrScanner.kt`
- Create: `android/sdk/src/main/java/com/ble/notification/qr/QrScannerFragment.kt`
- Test: `android/sdk/src/test/java/com/ble/notification/qr/QrDecoderTest.kt`

**Interfaces:**
- Produces: `QrDecoder.parseQrCode(url)` → `QrResult?`
- Produces: `QrScanner.start(callback)`
- Produces: `QrScannerFragment` (UI 组件)

**扫码分层设计**：

| 层级 | 组件 | 说明 |
|------|------|------|
| 解码层 | `QrDecoder` | URL 解析，无 Android 依赖 |
| 相机层 | `QrScanner` | CameraX + ML Kit，需相机权限 |
| UI 层 | `QrScannerFragment` | 完整扫码界面，可直接 add 到容器 |

- [ ] **Step 1: Write QrDecoder (解码层)**

```kotlin
// android/sdk/src/main/java/com/ble/notification/qr/QrDecoder.kt
package com.ble.notification.qr

import android.net.Uri

data class QrResult(
    val mac: String,
    val uuid: String
)

object QrDecoder {
    fun parseQrCode(url: String): QrResult? {
        return try {
            val uri = Uri.parse(url)
            if (uri.scheme != "ble" || uri.host != "pair") return null
            
            val mac = uri.getQueryParameter("mac") ?: return null
            val uuid = uri.getQueryParameter("uuid") ?: return null
            
            QrResult(mac, uuid)
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 2: Write QrDecoder test**

```kotlin
// android/sdk/src/test/java/com/ble/notification/qr/QrDecoderTest.kt
import org.junit.Test
import org.junit.Assert.*

class QrDecoderTest {
    @Test
    fun `parse valid QR code`() {
        val result = QrDecoder.parseQrCode(
            "ble://pair?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"
        )
        assertNotNull(result)
        assertEquals("AA:BB:CC:DD:EE:FF", result?.mac)
        assertEquals("0000A1B2-0000-1000-8000-00805F9B34FB", result?.uuid)
    }

    @Test
    fun `parse invalid URL returns null`() {
        assertNull(QrDecoder.parseQrCode("invalid"))
        assertNull(QrDecoder.parseQrCode("http://example.com"))
        assertNull(QrDecoder.parseQrCode("ble://wrong?mac=AA:BB:CC:DD:EE:FF"))
    }
}
```

- [ ] **Step 3: Run test to verify it passes**

Run: `./gradlew :sdk:test --tests "QrDecoderTest"`
Expected: PASS

- [ ] **Step 4: Write QrScanner (相机层)**

```kotlin
// android/sdk/src/main/java/com/ble/notification/qr/QrScanner.kt
package com.ble.notification.qr

import android.app.Activity
import android.camera.core.CameraSelector
import android.camera.core.ImageAnalysis
import android.camera.core.Preview
import android.camera.lifecycle.ProcessCameraProvider
import android.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class QrScanner(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var onResult: ((QrResult?) -> Unit)? = null
    private var cameraProvider: ProcessCameraProvider? = null

    fun start(callback: (QrResult?) -> Unit) {
        onResult = callback
        startCamera()
    }

    fun stop() {
        cameraProvider?.unbindAll()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(activity.mainExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, activity.mainExecutor)
    }

    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        
        val scanner = BarcodeScanning.getClient()
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.valueType == Barcode.TYPE_URL) {
                        val result = QrDecoder.parseQrCode(barcode.url?.url ?: "")
                        if (result != null) {
                            onResult?.invoke(result)
                            stop()
                            return@addOnSuccessListener
                        }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
```

- [ ] **Step 5: Write QrScannerFragment (UI 层)**

```kotlin
// android/sdk/src/main/java/com/ble/notification/qr/QrScannerFragment.kt
package com.ble.notification.qr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

class QrScannerFragment : Fragment() {
    private var onResult: ((QrResult?) -> Unit)? = null
    private var scanner: QrScanner? = null

    companion object {
        fun newInstance(onResult: (QrResult?) -> Unit): QrScannerFragment {
            return QrScannerFragment().apply {
                this.onResult = onResult
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 创建简单布局：预览 + 提示文字
        val frameLayout = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        val previewView = android.camera.view.PreviewView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frameLayout.addView(previewView)
        
        val textView = TextView(requireContext()).apply {
            text = "扫描二维码配对"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setPadding(32, 32, 32, 32)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        frameLayout.addView(textView)
        
        // 启动扫码
        scanner = QrScanner(requireActivity(), viewLifecycleOwner, previewView)
        scanner?.start { result ->
            onResult?.invoke(result)
        }
        
        return frameLayout
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanner?.stop()
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add android/sdk/src/main/java/com/ble/notification/qr
git commit -m "feat(android): add QR scanner with 3-layer design"
```

---

## Task 9: Android 端 - 配对管理器

**Files:**
- Create: `android/sdk/src/main/java/com/ble/notification/pairing/PairingManager.kt`
- Test: `android/sdk/src/test/java/com/ble/notification/pairing/PairingManagerTest.kt`

**Interfaces:**
- Consumes: `BleClient`
- Consumes: `FrameEncoder`
- Consumes: `QrDecoder`
- Produces: `PairingManager.startPairing(activity, callback)`

- [ ] **Step 1: Write the failing test**

```kotlin
// android/sdk/src/test/java/com/ble/notification/pairing/PairingManagerTest.kt
import org.junit.Test
import org.junit.Assert.*

class PairingManagerTest {
    @Test
    fun `QR URL parsing`() {
        val result = PairingManager.parsePairingUrl(
            "ble://pair?mac=AA:BB:CC:DD:EE:FF&uuid=0000A1B2-0000-1000-8000-00805F9B34FB"
        )
        assertNotNull(result)
        assertEquals("AA:BB:CC:DD:EE:FF", result?.mac)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdk:test --tests "PairingManagerTest"`
Expected: FAIL

- [ ] **Step 3: Write implementation**

```kotlin
// android/sdk/src/main/java/com/ble/notification/pairing/PairingManager.kt
package com.ble.notification.pairing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.ble.notification.ble.BleClient
import com.ble.notification.protocol.FrameEncoder
import com.ble.notification.qr.QrDecoder

class PairingManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ble_pairing", Context.MODE_PRIVATE)
    private val bleClient = BleClient(context)

    enum class PairingState {
        IDLE, CONNECTING, REGISTERING, PAIRED
    }

    interface PairingCallback {
        fun onScanSuccess(mac: String)
        fun onConnecting()
        fun onRegistering()
        fun onPaired()
        fun onError(error: String)
    }

    fun startPairing(qrResult: QrDecoder.QrResult, callback: PairingCallback) {
        // 1. Connect to GATT Server
        // 2. Send REGISTER
        // 3. Wait for ACK
        // 4. Save pairing info
        callback.onConnecting()
        bleClient.connect(qrResult.mac, object : BleClient.ConnectionCallback {
            override fun onReady(gatt: android.bluetooth.BluetoothGatt) {
                callback.onRegistering()
                val frame = FrameEncoder.encodeRegister("APP_NAME", qrResult.packageName)
                // Send frame...
            }
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }

    fun isPaired(packageName: String): Boolean {
        return prefs.contains("key_$packageName")
    }

    fun savePairing(packageName: String, mac: String, appName: String) {
        prefs.edit()
            .putString("mac_$packageName", mac)
            .putString("name_$packageName", appName)
            .apply()
    }

    companion object {
        fun parsePairingUrl(url: String): BleClient.QrResult? {
            return try {
                val uri = Uri.parse(url)
                val mac = uri.getQueryParameter("mac") ?: return null
                val uuid = uri.getQueryParameter("uuid") ?: return null
                BleClient.QrResult(mac, uuid)
            } catch (e: Exception) {
                null
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdk:test --tests "PairingManagerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/sdk/src/main/java/com/ble/notification/pairing
git commit -m "feat(android): add pairing manager with QR parsing and state machine"
```

---

## Task 10: Android 端 - SDK 主入口

**Files:**
- Create: `android/sdk/src/main/java/com/ble/notification/sdk/BleNotificationSDK.kt`

**Interfaces:**
- Consumes: `PairingManager`, `AesCcmCrypto`, `FrameEncoder`, `BleClient`
- Produces: `BleNotificationSDK.startPairing()`
- Produces: `BleNotificationSDK.sendNotification()`

- [ ] **Step 1: Write implementation**

```kotlin
// android/sdk/src/main/java/com/ble/notification/sdk/BleNotificationSDK.kt
package com.ble.notification.sdk

import android.app.Activity
import android.content.Context
import com.ble.notification.ble.BleClient
import com.ble.notification.pairing.PairingManager
import com.ble.notification.protocol.FrameEncoder

class BleNotificationSDK private constructor(private val context: Context) {
    private val pairingManager = PairingManager(context)
    private val bleClient = BleClient(context)

    companion object {
        @Volatile
        private var instance: BleNotificationSDK? = null

        fun init(context: Context): BleNotificationSDK {
            return instance ?: synchronized(this) {
                instance ?: BleNotificationSDK(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(): BleNotificationSDK {
            return instance ?: throw IllegalStateException("SDK not initialized. Call init() first.")
        }
    }

    fun startPairing(activity: Activity, callback: PairingManager.PairingCallback) {
        pairingManager.startPairing(activity, callback)
    }

    fun isPaired(packageName: String): Boolean {
        return pairingManager.isPaired(packageName)
    }

    fun sendNotification(
        title: String,
        body: String,
        callback: SendCallback? = null
    ) {
        // TODO: Implement notification sending
        // 1. Get paired device MAC
        // 2. Connect to GATT Server
        // 3. Encode and encrypt notification
        // 4. Send via BLE
        // 5. Wait for ACK
        // 6. Disconnect
    }

    interface SendCallback {
        fun onSuccess()
        fun onError(error: String)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/sdk/src/main/java/com/ble/notification/sdk
git commit -m "feat(android): add SDK main entry point"
```

---

## Task 11: Windows 端 - LibTomCrypt 桥接

**Files:**
- Create: `windows/BleNotificationWin/Crypto/LibTomCrypt.cs`
- Create: `windows/BleNotificationWin/Crypto/AesCcmCrypto.cs`
- Create: `windows/BleNotificationWin/Crypto/KeyDerivation.cs`

**Interfaces:**
- Produces: `AesCcmCrypto.Encrypt(key, nonce, plaintext)` → `byte[]`
- Produces: `AesCcmCrypto.Decrypt(key, nonce, ciphertext)` → `byte[]?`
- Produces: `KeyDerivation.DeriveKey(packageName)` → `byte[]`

- [ ] **Step 1: Write P/Invoke wrapper**

```csharp
// windows/BleNotificationWin/Crypto/LibTomCrypt.cs
using System;
using System.Runtime.InteropServices;

namespace BleNotificationWin.Crypto
{
    internal static class LibTomCrypt
    {
        private const string DllName = "libtomcrypt";

        [DllImport(DllName)]
        private static extern int register_all_cipher();

        [DllImport(DllName)]
        private static extern int register_all_hash();

        [DllImport(DllName)]
        private static extern int aes_ccm_memory(
            byte[] key, int keylen,
            byte[] nonce, int noncelen,
            byte[] pt, int ptlen,
            byte[] ct, ref ulong ctlen,
            byte[] tag, int taglen);

        private static bool _registered = false;
        private static readonly object _lock = new object();

        internal static void EnsureRegistered()
        {
            if (!_registered)
            {
                lock (_lock)
                {
                    if (!_registered)
                    {
                        register_all_cipher();
                        register_all_hash();
                        _registered = true;
                    }
                }
            }
        }

        internal static byte[] AesCcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext)
        {
            EnsureRegistered();
            
            var ct = new byte[plaintext.Length + 16];
            ulong ctLen = (ulong)plaintext.Length;
            
            int err = aes_ccm_memory(
                key, key.Length,
                nonce, nonce.Length,
                plaintext, plaintext.Length,
                ct, ref ctLen,
                ct, 16);
            
            if (err != 0) throw new CryptographicException("AES-CCM encryption failed");
            
            var result = new byte[ctLen + 16];
            Array.Copy(ct, result, (int)ctLen);
            Array.Copy(ct, 0, result, (int)ctLen, 16);
            return result;
        }

        internal static byte[]? AesCcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext)
        {
            EnsureRegistered();
            
            if (ciphertext.Length < 16) return null;
            
            int ptLen = ciphertext.Length - 16;
            var pt = new byte[ptLen];
            ulong ptLenOut = (ulong)ptLen;
            
            var tag = new byte[16];
            Array.Copy(ciphertext, ptLen, tag, 0, 16);
            
            int err = aes_ccm_memory(
                key, key.Length,
                nonce, nonce.Length,
                pt, ptLen,
                pt, ref ptLenOut,
                tag, 16);
            
            return err == 0 ? pt : null;
        }

        [DllImport(DllName)]
        private static extern int hkdf(
            IntPtr hash,
            byte[] salt, int saltlen,
            byte[] info, int infolen,
            byte[] okm, int okmlen);

        // HKDF-SHA256 implementation
        internal static byte[] HkdfSha256(byte[] salt, byte[] info, int length)
        {
            EnsureRegistered();
            
            // HKDF = PRK = HMAC-Hash(salt, IKM)
            // Then OKM = T(1) || T(2) || ... where T(i) = HMAC-Hash(PRK, T(i-1) || info || i)
            
            using var hmac = new System.Security.Cryptography.HMACSHA256(salt);
            var prk = hmac.ComputeHash(new byte[32]); // IKM = 0x00...00
            
            var okm = new byte[length];
            var t = new byte[32];
            
            // T(1) = HMAC-Hash(PRK, info || 0x01)
            var infoWithCounter = new byte[info.Length + 1];
            Array.Copy(info, infoWithCounter, info.Length);
            infoWithCounter[info.Length] = 1;
            
            using var hmac2 = new System.Security.Cryptography.HMACSHA256(prk);
            t = hmac2.ComputeHash(infoWithCounter);
            Array.Copy(t, okm, Math.Min(32, length));
            
            return okm;
        }
    }
}
```

- [ ] **Step 2: Write AesCcmCrypto wrapper**

```csharp
// windows/BleNotificationWin/Crypto/AesCcmCrypto.cs
using System;

namespace BleNotificationWin.Crypto
{
    public static class AesCcmCrypto
    {
        private const int NonceSize = 12;

        public record EncryptedPayload(byte[] Nonce, byte[] Ciphertext);

        public static EncryptedPayload Encrypt(string packageName, byte[] plaintext)
        {
            var key = KeyDerivation.DeriveKey(packageName);
            var nonce = new byte[NonceSize];
            RandomNumberGenerator.Fill(nonce);
            
            var ciphertext = LibTomCrypt.AesCcmEncrypt(key, nonce, plaintext);
            return new EncryptedPayload(nonce, ciphertext);
        }

        public static byte[]? Decrypt(string packageName, byte[] nonce, byte[] ciphertext)
        {
            var key = KeyDerivation.DeriveKey(packageName);
            return LibTomCrypt.AesCcmDecrypt(key, nonce, ciphertext);
        }
    }
}
```

- [ ] **Step 3: Write KeyDerivation wrapper**

```csharp
// windows/BleNotificationWin/Crypto/KeyDerivation.cs
using System.Text;

namespace BleNotificationWin.Crypto
{
    public static class KeyDerivation
    {
        private static readonly byte[] SALT = Encoding.UTF8.GetBytes("BleNotificationSync");
        private const int KeyLength = 32;

        public static byte[] DeriveKey(string packageName)
        {
            var info = Encoding.UTF8.GetBytes(packageName);
            return LibTomCrypt.HkdfSha256(SALT, info, KeyLength);
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add windows/BleNotificationWin/Crypto
git commit -m "feat(windows): add LibTomCrypt bridge for AES-CCM and HKDF"
```

---

## Task 12: Windows 端 - GATT Server

**Files:**
- Create: `windows/BleNotificationWin/Gatt/GattServerService.cs`

**Interfaces:**
- Produces: `GattServerService.Start()`
- Produces: event `OnNotificationReceived`

- [ ] **Step 1: Write GATT Server implementation**

```csharp
// windows/BleNotificationWin/Gatt/GattServerService.cs
using System;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Bluetooth.Advertisement;

namespace BleNotificationWin.Gatt
{
    public class GattServerService
    {
        private GattServiceProvider _serviceProvider;
        private GattLocalCharacteristic _writeCharacteristic;
        
        public event EventHandler<byte[]> OnDataReceived;

        public async Task StartAsync()
        {
            // Create GATT service
            var serviceUuid = Guid.Parse("0000A1B2-0000-1000-8000-00805F9B34FB");
            var result = await GattServiceProvider.CreateAsync(serviceUuid);
            
            if (result.Error != BluetoothError.Success)
                throw new InvalidOperationException("Failed to create GATT service");
            
            _serviceProvider = result.ServiceProvider;
            
            // Create write characteristic
            var writeUuid = Guid.Parse("0000C3D4-0000-1000-8000-00805F9B34FB");
            var writeParameters = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.WriteWithoutResponse,
                WriteProtectionLevel = GattProtectionLevel.Plain
            };
            
            var charResult = await _serviceProvider.Service.CreateCharacteristicAsync(writeUuid, writeParameters);
            
            if (charResult.Error != BluetoothError.Success)
                throw new InvalidOperationException("Failed to create characteristic");
            
            _writeCharacteristic = charResult.Characteristic;
            _writeCharacteristic.WriteRequested += OnWriteRequested;
            
            // Start advertising
            var advertisingParameters = new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = true
            };
            
            _serviceProvider.StartAdvertising(advertisingParameters);
        }

        private void OnWriteRequested(GattSession session, GattWriteRequestedEventArgs args)
        {
            deferral = args.GetDeferral();
            
            try
            {
                var request = args.GetRequest();
                var reader = Windows.Storage.Streams.DataReader.FromBuffer(request.Value);
                var data = new byte[reader.UnconsumedBufferLength];
                reader.ReadBytes(data);
                
                OnDataReceived?.Invoke(this, data);
                
                request.Respond();
            }
            finally
            {
                deferral.Complete();
            }
        }

        public void Stop()
        {
            _serviceProvider?.StopAdvertising();
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add windows/BleNotificationWin/Gatt
git commit -m "feat(windows): add GATT server service"
```

---

## Task 13: macOS 端 - LibTomCrypt 桥接

**Files:**
- Create: `macos/BleNotificationMac/Crypto/LibTomCrypt.swift`
- Create: `macos/BleNotificationMac/Crypto/AesCcmCrypto.swift`
- Create: `macos/BleNotificationMac/Crypto/KeyDerivation.swift`

**Interfaces:**
- Produces: `AesCcmCrypto.encrypt(packageName, plaintext)` → `(nonce: Data, ciphertext: Data)`
- Produces: `AesCcmCrypto.decrypt(packageName, nonce, ciphertext)` → `Data?`
- Produces: `KeyDerivation.deriveKey(packageName)` → `Data`

- [ ] **Step 1: Write Bridging Header**

```c
// macos/BleNotificationMac/Bridging-Header.h
#include "tomcrypt.h"
```

- [ ] **Step 2: Write Swift wrapper**

```swift
// macos/BleNotificationMac/Crypto/LibTomCrypt.swift
import Foundation

class LibTomCryptBridge {
    static let shared = LibTomCryptBridge()
    private var registered = false
    
    private init() {}
    
    func ensureRegistered() {
        guard !registered else { return }
        register_all_cipher()
        register_all_hash()
        registered = true
    }
    
    func aesCcmEncrypt(key: Data, nonce: Data, plaintext: Data) -> Data? {
        ensureRegistered()
        
        var ct = Data(count: plaintext.count + 16)
        var ctLen = UInt(plaintext.count)
        
        let err = key.withUnsafeBytes { keyPtr in
            nonce.withUnsafeBytes { noncePtr in
                plaintext.withUnsafeBytes { ptPtr in
                    ct.withUnsafeMutableBytes { ctPtr in
                        var tag = Data(count: 16)
                        return tag.withUnsafeMutableBytes { tagPtr in
                            aes_ccm_memory(
                                keyPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), Int32(key.count),
                                noncePtr.baseAddress!.assumingMemoryBound(to: UInt8.self), Int32(nonce.count),
                                ptPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), Int32(plaintext.count),
                                ctPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), &ctLen,
                                tagPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), 16
                            )
                        }
                    }
                }
            }
        }
        
        guard err == 0 else { return nil }
        
        ct.count = Int(ctLen)
        ct.append(contentsOf: ct) // Append tag
        return ct
    }
    
    func aesCcmDecrypt(key: Data, nonce: Data, ciphertext: Data) -> Data? {
        ensureRegistered()
        
        guard ciphertext.count >= 16 else { return nil }
        
        let ptLen = ciphertext.count - 16
        var pt = Data(count: ptLen)
        var ptLenOut = UInt(ptLen)
        
        let err = key.withUnsafeBytes { keyPtr in
            nonce.withUnsafeBytes { noncePtr in
                pt.withUnsafeMutableBytes { ptPtr in
                    ciphertext.withUnsafeBytes { ctPtr in
                        var tag = Data(ciphertext.suffix(16))
                        return tag.withUnsafeMutableBytes { tagPtr in
                            aes_ccm_memory(
                                keyPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), Int32(key.count),
                                noncePtr.baseAddress!.assumingMemoryBound(to: UInt8.self), Int32(nonce.count),
                                ptPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), Int32(ptLen),
                                ctPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), &ptLenOut,
                                tagPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), 16
                            )
                        }
                    }
                }
            }
        }
        
        return err == 0 ? pt : nil
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add macos/BleNotificationMac/Crypto macos/BleNotificationMac/Bridging-Header.h
git commit -m "feat(macos): add LibTomCrypt bridge for AES-CCM and HKDF"
```

---

## Task 14: macOS 端 - Peripheral Manager

**Files:**
- Create: `macos/BleNotificationMac/BLE/PeripheralManager.swift`

**Interfaces:**
- Produces: `PeripheralManager.start()`
- Produces: event `OnDataReceived`

- [ ] **Step 1: Write implementation**

```swift
// macos/BleNotificationMac/BLE/PeripheralManager.swift
import Foundation
import CoreBluetooth

class PeripheralManager: NSObject, CBPeripheralManagerDelegate {
    private var peripheralManager: CBPeripheralManager!
    private var service: CBMutableService?
    private var writeCharacteristic: CBMutableCharacteristic?
    
    var onDataReceived: ((Data) -> Void)?
    
    let serviceUUID = CBUUID(string: "0000A1B2-0000-1000-8000-00805F9B34FB")
    let writeUUID = CBUUID(string: "0000C3D4-0000-1000-8000-00805F9B34FB")
    
    override init() {
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }
    
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { return }
        
        // Create characteristic
        writeCharacteristic = CBMutableCharacteristic(
            type: writeUUID,
            properties: .writeWithoutResponse,
            value: nil,
            permissions: .writeable
        )
        
        // Create service
        service = CBMutableService(type: serviceUUID, primary: true)
        service?.characteristics = [writeCharacteristic]
        
        peripheralManager.add(service)
    }
    
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard let value = request.value, let characteristic = request.characteristic,
                  characteristic.uuid == writeUUID else { continue }
            
            onDataReceived?(value)
            peripheralManager.respond(to: request, withResult: .success)
        }
    }
    
    func startAdvertising() {
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID]
        ])
    }
    
    func stopAdvertising() {
        peripheralManager.stopAdvertising()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/BleNotificationMac/BLE
git commit -m "feat(macos): add BLE peripheral manager"
```

---

## Task 15: macOS 端 - 配对存储

**Files:**
- Create: `macos/BleNotificationMac/Storage/PairingStorage.swift`
- Create: `macos/BleNotificationMac/Storage/KeyStorage.swift`

**Interfaces:**
- Produces: `PairingStorage.getPairedApps()` → `[PairedApp]`
- Produces: `KeyStorage.getKey(mac, packageName)` → `Data?`

- [ ] **Step 1: Write implementation**

```swift
// macos/BleNotificationMac/Storage/PairingStorage.swift
import Foundation

struct PairedApp: Codable {
    let mac: String
    let packageName: String
    let appName: String
}

class PairingStorage {
    private let storageURL: URL
    
    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        storageURL = appSupport.appendingPathComponent("BleNotificationSync/paired_apps.json")
    }
    
    func getPairedApps() -> [PairedApp] {
        guard let data = try? Data(contentsOf: storageURL),
              let apps = try? JSONDecoder().decode([PairedApp].self, from: data) else {
            return []
        }
        return apps
    }
    
    func saveApp(_ app: PairedApp) {
        var apps = getPairedApps()
        apps.removeAll { $0.packageName == app.packageName }
        apps.append(app)
        
        if let data = try? JSONEncoder().encode(apps) {
            try? data.write(to: storageURL)
        }
    }
}
```

```swift
// macos/BleNotificationMac/Storage/KeyStorage.swift
import Foundation

struct KeyEntry: Codable {
    let mac: String
    let packageName: String
    let key: Data
}

class KeyStorage {
    private let storageURL: URL
    
    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        storageURL = appSupport.appendingPathComponent("BleNotificationSync/keys.json")
    }
    
    func getKey(mac: String, packageName: String) -> Data? {
        guard let data = try? Data(contentsOf: storageURL),
              let entries = try? JSONDecoder().decode([KeyEntry].self, from: data) else {
            return nil
        }
        return entries.first { $0.mac == mac && $0.packageName == packageName }?.key
    }
    
    func saveKey(mac: String, packageName: String, key: Data) {
        var entries = getEntries()
        entries.removeAll { $0.mac == mac && $0.packageName == packageName }
        entries.append(KeyEntry(mac: mac, packageName: packageName, key: key))
        
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: storageURL)
        }
    }
    
    private func getEntries() -> [KeyEntry] {
        guard let data = try? Data(contentsOf: storageURL),
              let entries = try? JSONDecoder().decode([KeyEntry].self, from: data) else {
            return []
        }
        return entries
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/BleNotificationMac/Storage
git commit -m "feat(macos): add pairing and key storage"
```

---

## Task 16: macOS 端 - 菜单栏应用

**Files:**
- Create: `macos/BleNotificationMac/UI/MenuBarApp.swift`
- Create: `macos/BleNotificationMac/UI/NotificationService.swift`

**Interfaces:**
- Consumes: `PeripheralManager`, `PairingStorage`, `KeyStorage`
- Produces: `MenuBarApp` (SwiftUI App)

- [ ] **Step 1: Write implementation**

```swift
// macos/BleNotificationMac/UI/MenuBarApp.swift
import SwiftUI

@main
struct MenuBarApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    
    var body: some Scene {
        MenuBarExtra("BLE Notification Sync", systemImage: "antenna.radiowaves.left.and.right") {
            VStack {
                Text("Status: Running")
                Divider()
                Button("Quit") {
                    NSApplication.shared.terminate(nil)
                }
            }
        }
    }
}

class AppDelegate: NSObject, NSApplicationDelegate {
    private let peripheralManager = PeripheralManager()
    private let pairingStorage = PairingStorage()
    private let notificationService: NotificationService!
    
    override init() {
        notificationService = NotificationService(pairingStorage: pairingStorage)
        super.init()
    }
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        peripheralManager.onDataReceived = { [weak self] data in
            self?.handleIncomingData(data)
        }
        peripheralManager.startAdvertising()
    }
    
    private func handleIncomingData(_ data: Data) {
        // Decode frame, decrypt, show notification
        notificationService.handleNotification(data)
    }
}
```

```swift
// macos/BleNotificationMac/UI/NotificationService.swift
import Foundation
import UserNotifications

class NotificationService {
    private let pairingStorage: PairingStorage
    
    init(pairingStorage: PairingStorage) {
        self.pairingStorage = pairingStorage
        requestPermission()
    }
    
    private func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
            print("Notification permission: \(granted)")
        }
    }
    
    func handleNotification(_ data: Data) {
        // TODO: Decode frame, decrypt, show notification
        // This is a placeholder
        print("Received notification data: \(data.count) bytes")
    }
    
    func showNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        
        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )
        
        UNUserNotificationCenter.current().add(request)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/BleNotificationMac/UI
git commit -m "feat(macos): add menu bar app and notification service"
```

---

## Summary

| Task | Description | Status |
|------|-------------|--------|
| 1 | 下载 LibTomCrypt 源码 | - |
| 2 | Android JNI 桥接 | - |
| 3 | Android 密钥派生 | - |
| 4 | Android 加密服务 | - |
| 5 | Android 帧编码器 | - |
| 6 | Android 帧解码器 | - |
| 7 | Android BLE 客户端 | - |
| 8 | Android 扫码分层实现 | - |
| 9 | Android 配对管理器 | - |
| 10 | Android SDK 主入口 | - |
| 11 | Windows LibTomCrypt 桥接 | - |
| 12 | Windows GATT Server | - |
| 13 | macOS LibTomCrypt 桥接 | - |
| 14 | macOS Peripheral Manager | - |
| 15 | macOS 配对存储 | - |
| 16 | macOS 菜单栏应用 | - |

**Plan complete and saved to `docs/superpowers/plans/2026-07-10-ble-notification-sync.md`.**

**Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session, batch execution with checkpoints

Which approach?
