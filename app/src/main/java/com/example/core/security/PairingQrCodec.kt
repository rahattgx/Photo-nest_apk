package com.example.core.security

import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.model.PairingPayload
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

object PairingQrCodec {

    fun encodeToString(payload: PairingPayload): String {
        return JSONObject().apply {
            put("v", 1)
            put("sid", payload.serverId)
            put("uid", payload.ownerUid)
            put("name", payload.serverName)
            put("host", payload.hostAddress)
            put("port", payload.port)
            put("fp", payload.certFingerprint)
            put("sec", payload.pairingSecret)
            put("exp", payload.expiresAtMillis)
        }.toString()
    }

    fun decodeFromString(raw: String): PairingPayload? {
        return try {
            val trimmed = raw.trim()
            val obj = JSONObject(trimmed)
            if (obj.optInt("v", 0) != 1) return null
            val sid = obj.getString("sid")
            val uid = obj.getString("uid")
            val name = obj.optString("name", "PhotoNest Server")
            val host = obj.getString("host")
            val port = obj.getInt("port")
            val fp = obj.getString("fp")
            val sec = obj.getString("sec")
            val exp = obj.optLong("exp", Long.MAX_VALUE)
            PairingPayload(
                serverId = sid,
                ownerUid = uid,
                serverName = name,
                hostAddress = host,
                port = port,
                certFingerprint = fp,
                pairingSecret = sec,
                expiresAtMillis = exp
            )
        } catch (_: Exception) {
            null
        }
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    fun decodeQrFromYuv(
        data: ByteArray,
        width: Int,
        height: Int
    ): String? {
        return try {
            val source = PlanarYUVLuminanceSource(
                data,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(bitmap).text
        } catch (_: Exception) {
            null
        }
    }
}
