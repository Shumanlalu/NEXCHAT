package com.nexchat.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
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
    val source = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)
    } ?: return@runCatching null
    val largest = maxOf(source.width, source.height)
    val scale = if (largest > 640) 640f / largest else 1f
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        source
    }
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
    if (scaled !== source) scaled.recycle()
    source.recycle()
    val bytes = output.toByteArray()
    if (bytes.size > 680_000) null else Base64.encodeToString(bytes, Base64.NO_WRAP)
}.getOrNull()

fun displayNameFor(name: String?, fallback: String): String =
    name?.takeIf { it.isNotBlank() } ?: fallback
