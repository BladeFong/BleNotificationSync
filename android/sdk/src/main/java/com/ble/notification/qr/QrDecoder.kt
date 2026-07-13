package com.ble.notification.qr

data class QrResult(val mac: String, val uuid: String)

object QrDecoder {

    private const val SCHEME = "ble"
    private const val HOST = "pair"

    fun parseQrCode(url: String): QrResult? {
        if (url.isBlank()) return null

        val uri = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return null
        }

        if (uri.scheme != SCHEME || uri.host != HOST) return null

        val query = uri.query ?: return null
        val params = query.split("&")
            .associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }

        val mac = params["mac"]?.takeIf { it.isNotBlank() } ?: return null
        val uuid = params["uuid"]?.takeIf { it.isNotBlank() } ?: return null

        return QrResult(mac, uuid)
    }
}
