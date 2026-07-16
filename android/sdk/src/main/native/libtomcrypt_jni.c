/*
 * JNI bridge for LibTomCrypt — AES-GCM and HKDF-SHA256.
 */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include "tomcrypt.h"

static int ltc_initialized = 0;

static void ensure_init(void) {
    if (ltc_initialized) return;
    register_all_ciphers();
    register_all_hashes();
    ltc_initialized = 1;
}

static int aes_idx(void)  { ensure_init(); return find_cipher("aes"); }
static int sha256_idx(void) { ensure_init(); return find_hash("sha256"); }

JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_aesGcmEncrypt(
    JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce, jbyteArray plaintext) {
    (void)clazz;
    int cipher = aes_idx();
    if (cipher == -1) return NULL;

    jsize key_len = (*env)->GetArrayLength(env, key);
    jsize nonce_len = (*env)->GetArrayLength(env, nonce);
    jsize pt_len = (*env)->GetArrayLength(env, plaintext);

    jbyte *key_data = (*env)->GetByteArrayElements(env, key, NULL);
    jbyte *nonce_data = (*env)->GetByteArrayElements(env, nonce, NULL);
    jbyte *pt_data = (*env)->GetByteArrayElements(env, plaintext, NULL);

    unsigned long tag_len = 16;
    unsigned char *ct = (unsigned char *)malloc((size_t)pt_len);
    unsigned char *tag = (unsigned char *)malloc(16);
    if (!ct || !tag) {
        free(ct); free(tag);
        goto release;
    }

    int err = gcm_memory(cipher,
        (const unsigned char *)key_data, (unsigned long)key_len,
        (const unsigned char *)nonce_data, (unsigned long)nonce_len,
        NULL, 0,
        (unsigned char *)pt_data, (unsigned long)pt_len,
        ct, tag, &tag_len, GCM_ENCRYPT);

    if (err != CRYPT_OK) { free(ct); free(tag); ct = NULL; tag = NULL; }

release:
    (*env)->ReleaseByteArrayElements(env, key, key_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, nonce, nonce_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, plaintext, pt_data, JNI_ABORT);

    if (!ct) return NULL;

    jsize result_len = (jsize)((unsigned long)pt_len + tag_len);
    jbyteArray result = (*env)->NewByteArray(env, result_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)pt_len, (jbyte *)ct);
        (*env)->SetByteArrayRegion(env, result, (jsize)pt_len, (jsize)tag_len, (jbyte *)tag);
    }
    free(ct); free(tag);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_aesGcmDecrypt(
    JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce, jbyteArray ciphertext) {
    (void)clazz;
    int cipher = aes_idx();
    if (cipher == -1) return NULL;

    jsize key_len = (*env)->GetArrayLength(env, key);
    jsize nonce_len = (*env)->GetArrayLength(env, nonce);
    jsize ct_len = (*env)->GetArrayLength(env, ciphertext);
    if (ct_len < 16) return NULL;

    jsize pt_len = ct_len - 16;
    jbyte *key_data = (*env)->GetByteArrayElements(env, key, NULL);
    jbyte *nonce_data = (*env)->GetByteArrayElements(env, nonce, NULL);
    jbyte *ct_data = (*env)->GetByteArrayElements(env, ciphertext, NULL);

    unsigned char *pt = (unsigned char *)malloc((size_t)pt_len);
    if (!pt) { goto release2; }

    unsigned long tag_len = 16;
    int err = gcm_memory(cipher,
        (const unsigned char *)key_data, (unsigned long)key_len,
        (const unsigned char *)nonce_data, (unsigned long)nonce_len,
        NULL, 0,
        pt, (unsigned long)pt_len,
        (unsigned char *)ct_data, (unsigned char *)ct_data + pt_len, &tag_len, GCM_DECRYPT);

    if (err != CRYPT_OK) { free(pt); pt = NULL; }

release2:
    (*env)->ReleaseByteArrayElements(env, key, key_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, nonce, nonce_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, ciphertext, ct_data, JNI_ABORT);

    if (!pt) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, pt_len);
    if (result) { (*env)->SetByteArrayRegion(env, result, 0, pt_len, (jbyte *)pt); }
    free(pt);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ble_notification_crypto_NativeCrypto_hkdfSha256(
    JNIEnv *env, jclass clazz, jbyteArray salt, jbyteArray ikm, jint length) {
    (void)clazz;
    int hash = sha256_idx();
    if (hash == -1) return NULL;
    if (length <= 0 || length > 255) return NULL;

    jsize salt_len = (*env)->GetArrayLength(env, salt);
    jsize ikm_len = (*env)->GetArrayLength(env, ikm);
    jbyte *salt_data = (*env)->GetByteArrayElements(env, salt, NULL);
    jbyte *ikm_data = (*env)->GetByteArrayElements(env, ikm, NULL);

    unsigned long hash_size = 32;
    unsigned char *prk = (unsigned char *)malloc(hash_size);
    unsigned char *out = (unsigned char *)malloc((size_t)length);
    if (!prk || !out) { free(prk); free(out); prk = NULL; out = NULL; goto release3; }

    {
        const unsigned char *sptr = (salt_len > 0) ? (const unsigned char *)salt_data : NULL;
        unsigned long slen = (salt_len > 0) ? (unsigned long)salt_len : 0;
        if (hkdf_extract(hash, sptr, slen, (const unsigned char *)ikm_data, (unsigned long)ikm_len, prk, &hash_size) != CRYPT_OK) {
            free(prk); free(out); prk = NULL; out = NULL; goto release3;
        }
    }

    if (hkdf_expand(hash, (const unsigned char *)"", 0, prk, hash_size, out, (unsigned long)length) != CRYPT_OK) {
        free(out); out = NULL;
    }
    zeromem(prk, hash_size); free(prk);

release3:
    (*env)->ReleaseByteArrayElements(env, salt, salt_data, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, ikm, ikm_data, JNI_ABORT);

    if (!out) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result) { (*env)->SetByteArrayRegion(env, result, 0, length, (jbyte *)out); }
    zeromem(out, (size_t)length); free(out);
    return result;
}
