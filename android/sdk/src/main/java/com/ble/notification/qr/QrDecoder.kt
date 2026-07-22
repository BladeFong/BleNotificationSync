package com.ble.notification.qr

data class QrResult(val uuid: String, val mac: String? = null, val name: String? = null)

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

        val uuid = params["uuid"]?.takeIf { it.isNotBlank() } ?: return null
        val mac = params["mac"]?.takeIf { it.isNotBlank() }
        val name = params["name"]?.takeIf { it.isNotBlank() }?.let {
            try {
                java.net.URLDecoder.decode(it, "UTF-8")
            } catch (_: Exception) {
                it
            }
        }

        return QrResult(uuid = uuid, mac = mac, name = name)
    }
}

