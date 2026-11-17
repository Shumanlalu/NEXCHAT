package com.nexchat.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.nexchat.app.core.network.NexJson
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val monthDayFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val fullDateFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

fun formatClock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(clockFormatter)

fun formatListTime(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> formatClock(epochMillis)
        date == today.minusDays(1) -> "Yesterday"
        date.isAfter(today.minusDays(7)) -> date.format(dayFormatter)
        else -> date.format(monthDayFormatter)
    }
}

fun formatDayLabel(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(fullDateFormatter)
    }
}

fun parseReactions(raw: String): Map<String, List<String>> = runCatching {
    NexJson.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject.mapValues { (_, value) ->
        value.jsonArray.map { it.jsonPrimitive.content }
    }
}.getOrDefault(emptyMap())

fun reactionSummary(raw: String): List<Pair<String, Int>> =
    parseReactions(raw)
        .mapValues { (_, users) -> users.size }
        .filterValues { it > 0 }
        .toList()
        .sortedByDescending { it.second }

fun encodeAvatar(context: Context, uri: Uri): String? = runCatching {
    val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
        runCatching {
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL

    // Read the dimensions first so a large camera photo is downsampled during
    // decode instead of allocating the full-size bitmap.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1280)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return@runCatching null

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
    }
    val oriented = if (matrix.isIdentity) {
        decoded
    } else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    val largest = maxOf(oriented.width, oriented.height)
    val scale = if (largest > 640) 640f / largest else 1f
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== oriented) oriented.recycle() }
    } else {
        oriented
    }

    val output = ByteArrayOutputStream()
    // Re-encoding drops EXIF, GPS, and every other source metadata block.
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
    scaled.recycle()
    val bytes = output.toByteArray()
    if (bytes.size > 680_000) null else Base64.encodeToString(bytes, Base64.NO_WRAP)
}.getOrNull()

fun displayNameFor(name: String?, fallback: String): String =
    name?.takeIf { it.isNotBlank() } ?: fallback
