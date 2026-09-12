package com.qianbi.writer.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** 封面图处理：解码时按需降采样，导入时统一压成 JPEG，避免大图爆内存。 */
object ImageUtil {

    /** 从字节流解码成可绘制的位图（列表/详情里都先用它把大图缩下来）。 */
    fun load(bytes: ByteArray, maxSide: Int = 720): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 把用户选择的任意图片压成 JPEG（最大边 [maxSize]），失败返回 null。 */
    fun decode(bytes: ByteArray, maxSide: Int = 1080): ByteArray? = try {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = scaleDown(src, maxSide)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (scaled !== src) scaled.recycle()
        src.recycle()
        out.toByteArray()
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var maxSide = maxOf(width, height)
        while (maxSide / 2 >= target) {
            sample *= 2
            maxSide /= 2
        }
        return sample
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(src.width, src.height)
        if (side <= maxSide || side == 0) return src
        val ratio = maxSide.toFloat() / side
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
