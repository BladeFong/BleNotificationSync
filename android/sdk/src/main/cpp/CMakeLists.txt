cmake_minimum_required(VERSION 3.18)
project(tomcrypt_jni LANGUAGES C)

set(CMAKE_C_STANDARD 99)

set(LTC_DIR  "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libtomcrypt")
set(LTM_DIR  "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libtommath")

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
    LTC_RIJNDAEL
    LTC_ECB_MODE
    LTC_CTR_MODE
    LTC_SHA256
    LTC_HMAC
    LTC_GCM_MODE
    LTC_HKDF
    LTC_HASH_HELPERS
    USE_LTM
    LTM_DESC
)

file(GLOB LTM_SOURCES "${LTM_DIR}/*.c")
add_library(tommath STATIC ${LTM_SOURCES})
target_include_directories(tommath PUBLIC "${LTM_DIR}")
target_compile_options(tommath PRIVATE -w)

set(LTC_SOURCES
    "${LTC_DIR}/src/ciphers/aes/aes.c"
    "${LTC_DIR}/src/ciphers/aes/aes_desc.c"
    "${LTC_DIR}/src/ciphers/aes/aes_tab.c"
    "${LTC_DIR}/src/hashes/sha2/sha256.c"
    "${LTC_DIR}/src/hashes/sha2/sha256_desc.c"
    "${LTC_DIR}/src/mac/hmac/hmac_init.c"
    "${LTC_DIR}/src/mac/hmac/hmac_process.c"
    "${LTC_DIR}/src/mac/hmac/hmac_done.c"
    "${LTC_DIR}/src/mac/hmac/hmac_memory.c"
    "${LTC_DIR}/src/mac/hmac/hmac_file.c"
    "${LTC_DIR}/src/modes/ecb/ecb_start.c"
    "${LTC_DIR}/src/modes/ecb/ecb_encrypt.c"
    "${LTC_DIR}/src/modes/ecb/ecb_decrypt.c"
    "${LTC_DIR}/src/modes/ecb/ecb_done.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_init.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_reset.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_add_iv.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_add_aad.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_process.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_done.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_memory.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_gf_mult.c"
    "${LTC_DIR}/src/encauth/gcm/gcm_mult_h.c"
    "${LTC_DIR}/src/hashes/helper/hash_memory.c"
    "${LTC_DIR}/src/misc/hkdf/hkdf.c"
    "${LTC_DIR}/src/misc/zeromem.c"
    "${LTC_DIR}/src/misc/mem_neq.c"
    "${LTC_DIR}/src/misc/compare_testvector.c"
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
target_include_directories(tomcrypt PUBLIC "${LTC_DIR}/src/headers" "${LTM_DIR}")
target_compile_options(tomcrypt PRIVATE -w)

add_library(tomcrypt_jni SHARED "${CMAKE_CURRENT_SOURCE_DIR}/../native/libtomcrypt_jni.c")
target_include_directories(tomcrypt_jni PRIVATE "${LTC_DIR}/src/headers" "${LTM_DIR}")
target_link_libraries(tomcrypt_jni tomcrypt tommath)
