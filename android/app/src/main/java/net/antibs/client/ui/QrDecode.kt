package net.antibs.client.ui

import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/** Decodes a QR code from a picked image (shared by the add-servers sheet and the camera scanner). */
internal fun decodeQrFromUri(ctx: android.content.Context, uri: Uri): String? = try {
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    ctx.contentResolver.openInputStream(uri)?.use { stream ->
        val bmp = BitmapFactory.decodeStream(stream, null, opts) ?: return null
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.TRY_HARDER] = true
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }
} catch (_: Exception) { null }
