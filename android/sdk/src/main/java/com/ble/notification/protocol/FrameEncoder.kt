package com.ble.notification.protocol

import com.ble.notification.crypto.AesGcmCrypto

/**
 * Encodes BLE notification frames according to the protocol spec.
 *
 * Frame layout: Magic(2) + MsgType(1) + Seq(1) + TotalSeq(1) + Payload(var)
 */
object FrameEncoder {

    private val MAGIC = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    private const val HEADER_SIZE = 5
    private const val NONCE_SIZE = 12

    /**
     * Encode a REGISTER frame (plaintext JSON payload).
     *
     * @param appName display name of the app
     * @param packageName Android package name
     * @param random random bytes for authentication challenge (32 bytes)
     * @param androidId Android ID of the device
     * @param deviceName friendly device name (e.g. Model)
     * @return encoded frame bytes
     */
    fun encodeRegister(
        appName: String,
        packageName: String,
        random: ByteArray,
        androidId: String? = null,
        deviceName: String? = null
    ): ByteArray {
        val randomHex = random.joinToString("") { "%02x".format(it) }
        val fields = mutableListOf<Pair<String, String>>(
            "app_name" to jsonValue(appName),
            "package" to jsonValue(packageName),
            "random" to jsonValue(randomHex)
        )
        if (!androidId.isNullOrBlank()) {
            fields.add("android_id" to jsonValue(androidId))
        }
        if (!deviceName.isNullOrBlank()) {
            fields.add("device_name" to jsonValue(deviceName))
        }
        val json = buildJson(*fields.toTypedArray())
        return buildFrame(MessageType.REGISTER, 0, 1, json.toByteArray(Charsets.UTF_8))
    }


    /**
     * Encode a NOTIFY frame with AES-GCM encryption.
     *
     * Frame: Header(5) + PackageLen(1) + Package(var) + Nonce(12) + Ciphertext(var)
     *
     * @param key         32-byte AES key (derived during pairing via HKDF)
     * @param packageName cleartext Package field in the frame
     * @param title notification title
     * @param body notification body
     * @param timestamp event timestamp in millis
     * @return encoded frame bytes
     */
    fun encodeNotify(
        key: ByteArray,
        packageName: String,
        title: String,
        body: String,
        timestamp: Long
    ): ByteArray {
        val plaintext = buildJson(
            "title" to jsonValue(title),
            "body" to jsonValue(body),
            "timestamp" to timestamp.toString()
        )
        val encrypted = AesGcmCrypto.encrypt(
            key,
            plaintext.toByteArray(Charsets.UTF_8)
        )

        val pkgBytes = packageName.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(HEADER_SIZE + 1 + pkgBytes.size + NONCE_SIZE + encrypted.ciphertext.size)
        var offset = 0

        // Header
        MAGIC.copyInto(frame, offset); offset += 2
        frame[offset] = MessageType.NOTIFY.value; offset += 1
        frame[offset] = 0; offset += 1 // Seq
        frame[offset] = 1; offset += 1 // TotalSeq

        // PackageLen + Package (cleartext)
        frame[offset] = pkgBytes.size.toByte(); offset += 1
        pkgBytes.copyInto(frame, offset); offset += pkgBytes.size

        // Nonce (cleartext)
        encrypted.nonce.copyInto(frame, offset); offset += NONCE_SIZE

        // Ciphertext
        encrypted.ciphertext.copyInto(frame, offset)

        return frame
    }

    /**
     * Encode an ICON_DATA frame (raw binary, no encryption).
     *
     * @param iconBytes raw icon data (max 239 bytes per frame)
     * @param seq 0-based sequence number
     * @param totalSeq total number of icon frames
     * @return encoded frame bytes
     */
    fun encodeIconData(iconBytes: ByteArray, seq: Int, totalSeq: Int): ByteArray {
        return buildFrame(MessageType.ICON_DATA, seq, totalSeq, iconBytes)
    }

    /**
     * Encode an ICON_END frame (plaintext JSON payload).
     *
     * @param totalSize total byte size of the icon data
     * @return encoded frame bytes
     */
    fun encodeIconEnd(totalSize: Int): ByteArray {
        val json = buildJson("total_size" to totalSize.toString())
        return buildFrame(MessageType.ICON_END, 0, 1, json.toByteArray(Charsets.UTF_8))
    }

    // ── Internal ────────────────────────────────────────────────

    private fun buildFrame(
        type: MessageType,
        seq: Int,
        totalSeq: Int,
        payload: ByteArray
    ): ByteArray {
        val frame = ByteArray(HEADER_SIZE + payload.size)
        var offset = 0

        MAGIC.copyInto(frame, offset); offset += 2
        frame[offset] = type.value; offset += 1
        frame[offset] = seq.toByte(); offset += 1
        frame[offset] = totalSeq.toByte(); offset += 1
        payload.copyInto(frame, offset)

        return frame
    }

    /**
     * Escape a string value for safe JSON embedding.
     * Covers the five characters that must be escaped per RFC 8259:
     * backslash, double-quote, newline, carriage-return, tab.
     * Other Unicode control characters (U+0000–U+001F) are not valid
     * in notification title/body text, so manual escaping is sufficient.
     */
    private fun jsonValue(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    /**
     * Build a minimal JSON object string from key-value pairs.
     * Each value must already be JSON-escaped and quoted.
     * Note: uses manual string concatenation rather than a full JSON library
     * because the frame payloads are tiny (3–5 fields) and the protocol is
     * deliberately simple — introducing a library would add runtime overhead
     * without meaningful benefit.
     */
    private fun buildJson(vararg fields: Pair<String, String>): String {
        return fields.joinToString(",", "{", "}") { (key, value) ->
            "\"$key\":$value"
        }
    }
}
