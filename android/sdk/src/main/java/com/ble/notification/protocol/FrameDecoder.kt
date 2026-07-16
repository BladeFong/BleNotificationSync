package com.ble.notification.protocol

import com.ble.notification.crypto.AesGcmCrypto

data class Frame(
    val type: MessageType,
    val seq: Int,
    val totalSeq: Int,
    val payload: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type &&
                seq == other.seq &&
                totalSeq == other.totalSeq &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + seq
        result = 31 * result + totalSeq
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}

object FrameDecoder {

    private val MAGIC = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    private const val HEADER_SIZE = 5
    private const val NONCE_SIZE = 12
    private const val MIN_FRAME_SIZE = HEADER_SIZE

    fun decode(data: ByteArray): Frame? {
        if (data.size < MIN_FRAME_SIZE) return null
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1]) return null

        val msgType = MessageType.fromValue(data[2]) ?: return null
        val seq = data[3].toInt() and 0xFF
        val totalSeq = data[4].toInt() and 0xFF

        val payload = if (msgType == MessageType.NOTIFY) {
            decodeNotifyPayload(data)
        } else {
            val raw = data.sliceArray(HEADER_SIZE until data.size)
            if (raw.isEmpty()) null else raw
        }

        return Frame(msgType, seq, totalSeq, payload)
    }

    private fun decodeNotifyPayload(data: ByteArray): ByteArray? {
        if (data.size < HEADER_SIZE + 1 + NONCE_SIZE) return null

        val packageLen = data[HEADER_SIZE].toInt() and 0xFF
        if (data.size < HEADER_SIZE + 1 + packageLen + NONCE_SIZE) return null

        val packageName = String(data, HEADER_SIZE + 1, packageLen, Charsets.UTF_8)
        val nonceOffset = HEADER_SIZE + 1 + packageLen
        val nonce = data.sliceArray(nonceOffset until nonceOffset + NONCE_SIZE)
        val ciphertext = data.sliceArray(nonceOffset + NONCE_SIZE until data.size)

        return AesGcmCrypto.decrypt(packageName, nonce, ciphertext)
    }
}
