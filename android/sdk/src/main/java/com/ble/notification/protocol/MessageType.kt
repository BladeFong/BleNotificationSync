package com.ble.notification.protocol

enum class MessageType(val value: Byte) {
    REGISTER(0x01),
    NOTIFY(0x02),
    ACK(0x03),
    ICON_DATA(0x04),
    ICON_END(0x05);

    companion object {
        fun fromValue(value: Byte): MessageType? =
            entries.find { it.value == value }
    }
}
