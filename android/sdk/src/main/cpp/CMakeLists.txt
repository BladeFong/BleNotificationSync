cmake_minimum_required(VERSION 3.18)
project(tomcrypt_jni LANGUAGES C)

set(CMAKE_C_STANDARD 99)

# ── Paths ─────────────────────────────────────────────────────────────
set(LTC_DIR  "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libtomcrypt")
set(LTM_DIR  "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libtommath")

# ── Compile definitions (minimal feature set) ────────────────────────
# Disable everything, then enable only what we need:
#   AES (RIJNDAEL), ECB mode, SHA-256, HMAC, CCM, HKDF
add_compile_definitions(
    LTC_NO_ASM
    LTC_NO_CIPHERS
    LTC_NO_MODES
    LTC_NO_HASHES
    LTC_NO_MACS
    LTC_NO_PRNGS
    LTC_NO_PK
    LTC_NO_PKCS
    LTC_NO_MISC
    # --- enable what we need ---
    LTC_RIJNDAEL        # AES
    LTC_ECB_MODE        # ECB (required by CCM internals)
    LTC_CBC_MODE        # sometimes pulled in transitively
    LTC_SHA256          # SHA-256 hash
    LTC_HMAC            # HMAC (required by HKDF)
    LTC_OMAC            # OMAC (sometimes required)
    LTC_CCM_MODE        # AES-CCM authenticated encryption
    LTC_HKDF            # HKDF-SHA256 key derivation
    USE_LTM
    LTM_DESC
)

# ── libtommath (static) ──────────────────────────────────────────────
file(GLOB LTM_SOURCES "${LTM_DIR}/*.c")

add_library(tommath STATIC ${LTM_SOURCES})
target_include_directories(tommath PUBLIC "${LTM_DIR}")
target_compile_options(tommath PRIVATE -w)  # suppress warnings in third-party code

# ── libtomcrypt (static, minimal) ────────────────────────────────────
set(LTC_SOURCES
    # AES cipher
    "${LTC_DIR}/src/ciphers/aes/aes.c"
    "${LTC_DIR}/src/ciphers/aes/aes_desc.c"
    "${LTC_DIR}/src/ciphers/aes/aes_tab.c"

    # SHA-256 hash
    "${LTC_DIR}/src/hashes/sha2/sha256.c"
    "${LTC_DIR}/src/hashes/sha2/sha256_desc.c"

    # HMAC
    "${LTC_DIR}/src/mac/hmac/hmac_init.c"
    "${LTC_DIR}/src/mac/hmac/hmac_process.c"
    "${LTC_DIR}/src/mac/hmac/hmac_done.c"
    "${LTC_DIR}/src/mac/hmac/hmac_memory.c"
    "${LTC_DIR}/src/mac/hmac/hmac_file.c"

    # OMAC (OMAC1/CMAC — sometimes required by internal dependencies)
    "${LTC_DIR}/src/mac/omac/omac_init.c"
    "${LTC_DIR}/src/mac/omac/omac_process.c"
    "${LTC_DIR}/src/mac/omac/omac_done.c"
    "${LTC_DIR}/src/mac/omac/omac_memory.c"
    "${LTC_DIR}/src/mac/omac/omac_file.c"
    "${LTC_DIR}/src/mac/omac/omac_test.c"

    # CCM authenticated encryption
    "${LTC_DIR}/src/encauth/ccm/ccm_init.c"
    "${LTC_DIR}/src/encauth/ccm/ccm_reset.c"
    "${LTC_DIR}/src/encauth/ccm/ccm_add_nonce.c"
    "${LTC_DIR}/src/encauth/ccm/ccm_add_aad.c"
    "${LTC_DIR}/src/encauth/ccm/ccm_process.c"
    "${LTC_DIR}/src/encauth/ccm/ccm_done.c"
    "${LTC_DIR}/src/encauth/ccm/ccm_memory.c"

    # HKDF
    "${LTC_DIR}/src/misc/hkdf/hkdf.c"

    # crypt core (registration, find, descriptor management)
    "${LTC_DIR}/src/misc/crypt/crypt.c"
    "${LTC_DIR}/src/misc/crypt/crypt_register_cipher.c"
    "${LTC_DIR}/src/misc/crypt/crypt_register_hash.c"
    "${LTC_DIR}/src/misc/crypt/crypt_register_all_ciphers.c"
    "${LTC_DIR}/src/misc/crypt/crypt_register_all_hashes.c"
    "${LTC_DIR}/src/misc/crypt/crypt_find_cipher.c"
    "${LTC_DIR}/src/misc/crypt/crypt_find_hash.c"
    "${LTC_DIR}/src/misc/crypt/crypt_cipher_descriptor.c"
    "${LTC_DIR}/src/misc/crypt/crypt_cipher_is_valid.c"
    "${LTC_DIR}/src/misc/crypt/crypt_hash_descriptor.c"
    "${LTC_DIR}/src/misc/crypt/crypt_hash_is_valid.c"
    "${LTC_DIR}/src/misc/crypt/crypt_argchk.c"
    "${LTC_DIR}/src/misc/crypt/crypt_inits.c"
    "${LTC_DIR}/src/misc/crypt/crypt_sizes.c"
    "${LTC_DIR}/src/misc/crypt/crypt_fsa.c"
    "${LTC_DIR}/src/misc/crypt/crypt_ltc_mp_descriptor.c"
)

add_library(tomcrypt STATIC ${LTC_SOURCES})
target_include_directories(tomcrypt PUBLIC
    "${LTC_DIR}/src/headers"
    "${LTM_DIR}"
)
target_compile_options(tomcrypt PRIVATE -w)

# ── JNI shared library ──────────────────────────────────────────────
add_library(tomcrypt_jni SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/../native/libtomcrypt_jni.c"
)

target_include_directories(tomcrypt_jni PRIVATE
    "${LTC_DIR}/src/headers"
    "${LTM_DIR}"
)

# Android NDK provides jni.h via the platform sysroot; no extra include needed.
target_link_libraries(tomcrypt_jni tomcrypt tommath)
