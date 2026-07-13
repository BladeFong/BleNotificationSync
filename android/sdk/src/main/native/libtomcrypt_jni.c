/*
 * JNI bridge for LibTomCrypt — AES-CCM and HKDF-SHA256.
 *
 * Compiled as shared library "libtomcrypt_jni.so".
 * Kotlin entry: NativeCrypto.kt
 */
#include <jni.h>
#include <string.h>
#include <stdlib.h>

#include "tomcrypt.h"

/* ── one-time registration ─────────────────────────────────────────── */

static int ltc_initialized = 0;

static void ensure_init(void)
{
    if (ltc_initialized) return;
    register_all_ciphers();
    register_all_hashes();
    ltc_initialized = 1;
}

static int aes_idx(void)  { ensure_init(); return find_cipher("aes"); }
static int sha256_idx(void) { ensure_init(); return find_hash("sha256"); }

/* ── AES-CCM encrypt ───────────────────────────────────────────────── */

JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_aesCcmEncrypt(
    JNIEnv *env, jclass clazz,
    jbyteArray key, jbyteArray nonce, jbyteArray plaintext)
{
    (void)clazz;

    int cipher = aes_idx();
    if (cipher == -1) return NULL;

    jsize key_len   = (*env)->GetArrayLength(env, key);
    jsize nonce_len = (*env)->GetArrayLength(env, nonce);
    jsize pt_len    = (*env)->GetArrayLength(env, plaintext);

    jbyte *key_data   = (*env)->GetByteArrayElements(env, key, NULL);
    jbyte *nonce_data = (*env)->GetByteArrayElements(env, nonce, NULL);
    jbyte *pt_data    = (*env)->GetByteArrayElements(env, plaintext, NULL);

    /* Output: ciphertext + 16-byte tag */
    unsigned long tag_len = 16;
    unsigned char *out = (unsigned char *)malloc((size_t)(pt_len + 16));
    if (!out) {
        (*env)->ReleaseByteArrayElements(env, key, key_data, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, nonce, nonce_data, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, plaintext, pt_data, JNI_ABORT);
        return NULL;
    }

    int err = ccm_memory(
        cipher,
        (const unsigned char *)key_data, (unsigned long)key_len,
        NULL, /* no pre-scheduled key */
        (const unsigned char *)nonce_data, (unsigned long)nonce_len,
        NULL, 0, /* no AAD / header */
        (unsigned char *)pt_data, (unsigned long)pt_len,
        out,              /* ciphertext output */
        out + pt_len,     /* tag output (appended after CT) */
        &tag_len,
        CCM_ENCRYPT);

    (*env)->ReleaseByteArrayElements(env, key, key_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, nonce, nonce_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, plaintext, pt_data, JNI_ABORT);

    if (err != CRYPT_OK) {
        free(out);
        return NULL;
    }

    jsize result_len = (jsize)((unsigned long)pt_len + tag_len);
    jbyteArray result = (*env)->NewByteArray(env, result_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, result_len, (jbyte *)out);
    }
    free(out);
    return result;
}

/* ── AES-CCM decrypt ───────────────────────────────────────────────── */

JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_aesCcmDecrypt(
    JNIEnv *env, jclass clazz,
    jbyteArray key, jbyteArray nonce, jbyteArray ciphertext)
{
    (void)clazz;

    int cipher = aes_idx();
    if (cipher == -1) return NULL;

    jsize key_len   = (*env)->GetArrayLength(env, key);
    jsize nonce_len = (*env)->GetArrayLength(env, nonce);
    jsize ct_len    = (*env)->GetArrayLength(env, ciphertext);

    if (ct_len < 16) return NULL; /* too short for tag */

    jsize pt_len = ct_len - 16;

    jbyte *key_data   = (*env)->GetByteArrayElements(env, key, NULL);
    jbyte *nonce_data = (*env)->GetByteArrayElements(env, nonce, NULL);
    jbyte *ct_data    = (*env)->GetByteArrayElements(env, ciphertext, NULL);

    unsigned char *pt_out = (unsigned char *)malloc((size_t)pt_len);
    if (!pt_out) {
        (*env)->ReleaseByteArrayElements(env, key, key_data, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, nonce, nonce_data, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, ciphertext, ct_data, JNI_ABORT);
        return NULL;
    }

    unsigned long tag_len = 16;
    int err = ccm_memory(
        cipher,
        (const unsigned char *)key_data, (unsigned long)key_len,
        NULL,
        (const unsigned char *)nonce_data, (unsigned long)nonce_len,
        NULL, 0,
        pt_out, (unsigned long)pt_len,           /* plaintext output */
        (unsigned char *)ct_data,                /* ciphertext (without tag) */
        (unsigned char *)ct_data + pt_len,       /* tag (last 16 bytes of input) */
        &tag_len,
        CCM_DECRYPT);

    (*env)->ReleaseByteArrayElements(env, key, key_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, nonce, nonce_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, ciphertext, ct_data, JNI_ABORT);

    if (err != CRYPT_OK) {
        free(pt_out);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, pt_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, pt_len, (jbyte *)pt_out);
    }
    free(pt_out);
    return result;
}

/* ── HKDF-SHA256 ──────────────────────────────────────────────────── */

/*
 * BLE key derivation:
 *   PRK = HMAC-SHA256(salt, IKM)
 *   OKM = HKDF-Expand(PRK, info, L)
 *
 * For this project:
 *   salt = "BleNotificationSync"
 *   IKM  = package_name  (or a random secret set during pairing)
 *   info = package_name
 *   L    = 32
 *
 * libtomcrypt's hkdf() wraps extract+expand but the "in" (IKM) param
 * is separate from "info".  We call hkdf_extract and hkdf_expand
 * directly to match the project's key derivation spec.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_hkdfSha256(
    JNIEnv *env, jclass clazz,
    jbyteArray salt, jbyteArray info, jint length)
{
    (void)clazz;

    int hash = sha256_idx();
    if (hash == -1) return NULL;
    if (length <= 0 || length > 255) return NULL; /* HKDF output limit */

    jsize salt_len = (*env)->GetArrayLength(env, salt);
    jsize info_len = (*env)->GetArrayLength(env, info);

    jbyte *salt_data = (*env)->GetByteArrayElements(env, salt, NULL);
    jbyte *info_data = (*env)->GetByteArrayElements(env, info, NULL);

    /* Step 1: Extract — PRK = HMAC(salt, IKM) */
    unsigned long hash_size = 256 / 8; /* SHA-256 = 32 bytes */
    unsigned char *prk = (unsigned char *)malloc(hash_size);
    unsigned char *out = (unsigned char *)malloc((size_t)length);
    if (!prk || !out) {
        free(prk);
        free(out);
        (*env)->ReleaseByteArrayElements(env, salt, salt_data, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, info, info_data, JNI_ABORT);
        return NULL;
    }

    /* IKM = info (package name); salt may be NULL/empty for default */
    const unsigned char *salt_ptr = (salt_len > 0)
        ? (const unsigned char *)salt_data : NULL;
    unsigned long salt_use = (salt_len > 0) ? (unsigned long)salt_len : 0;

    int err = hkdf_extract(
        hash,
        salt_ptr, salt_use,
        (const unsigned char *)info_data, (unsigned long)info_len,
        prk, &hash_size);

    if (err != CRYPT_OK) {
        free(prk);
        free(out);
        (*env)->ReleaseByteArrayElements(env, salt, salt_data, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, info, info_data, JNI_ABORT);
        return NULL;
    }

    /* Step 2: Expand — OKM = Expand(PRK, info, L) */
    err = hkdf_expand(
        hash,
        (const unsigned char *)info_data, (unsigned long)info_len,
        prk, hash_size,
        out, (unsigned long)length);

    /* Zero and free the intermediate PRK */
    zeromem(prk, hash_size);
    free(prk);

    (*env)->ReleaseByteArrayElements(env, salt, salt_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, info, info_data, JNI_ABORT);

    if (err != CRYPT_OK) {
        free(out);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, length, (jbyte *)out);
    }
    zeromem(out, (size_t)length);
    free(out);
    return result;
}
