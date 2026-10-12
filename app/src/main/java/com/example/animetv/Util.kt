package com.example.animetv

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

// ---------------------------------------------------------------- JSON

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { i -> if (isNull(i)) null else optString(i).takeIf { it.isNotBlank() } }

/** optString devuelve "null" para valores JSON null; esto devuelve "". */
internal fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name)

// ---------------------------------------------------------------- Formato

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

// ---------------------------------------------------------------- Imágenes

/** Muchos sitios bloquean imágenes sin Referer (anti-hotlinking). */
fun imageModel(url: String): GlideUrl =
    GlideUrl(url, LazyHeaders.Builder().addHeader("Referer", SiteConfig.BASE_URL).build())

/** Desenfoque barato: reduce la imagen y deja que la vista la amplíe con filtrado. */
class SoftBlur(private val factor: Int = 10) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val w = (toTransform.width / factor).coerceAtLeast(1)
        val h = (toTransform.height / factor).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(toTransform, w, h, true)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("com.example.animetv.SoftBlur.$factor".toByteArray())
    }

    override fun equals(other: Any?): Boolean = other is SoftBlur && other.factor == factor

    override fun hashCode(): Int = "com.example.animetv.SoftBlur".hashCode() * 31 + factor
}
