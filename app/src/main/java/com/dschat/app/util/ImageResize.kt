package com.dschat.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 上传前缩放超大/超长图。官方模型对图片尺寸有限制（超长图报 unsupported image），
 * 官方 App 上传前会缩放；这里把超过 4096 边的图缩到 ≤4096 再上传，避免发送报错。
 */
object ImageResize {
    private const val MAX_SIDE = 4096

    /** 返回 (上传字节, mime)。未超限返回原字节。 */
    fun maybeShrink(bytes: ByteArray, mime: String): Pair<ByteArray, String> {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return bytes to mime
            if (w <= MAX_SIDE && h <= MAX_SIDE) return bytes to mime

            var sample = 1
            while (w / (sample * 2) >= MAX_SIDE || h / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes to mime
            val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) {
                val nw = maxOf(1, (bmp.width * scale).toInt())
                val nh = maxOf(1, (bmp.height * scale).toInt())
                val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray() to "image/jpeg"
        } catch (_: Throwable) {
            bytes to mime
        }
    }
}