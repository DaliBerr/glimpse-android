package com.albumclean.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class Photo(
    val key: String,
    val uri: String,
    val name: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val takenAt: Long,
    val addedAt: Long,
    val album: String,
    val mimeType: String,
) {
    val dimensions: String get() = if (width > 0 && height > 0) "$width × $height" else "尺寸未记录"
    val dateLabel: String get() = if (takenAt > 0) "拍摄时间" else "添加时间"
    val dateText: String get() {
        val time = takenAt.takeIf { it > 0 } ?: addedAt
        return if (time > 0) SimpleDateFormat("yyyy.MM.dd  HH:mm", Locale.CHINA).format(Date(time)) else "时间未记录"
    }
}

enum class Choice { KEEP, DELETE, DELETED }
enum class PhotoAvailability { AVAILABLE, REMOVED, UNAVAILABLE }
data class DeletionPlan(val ready: List<Photo>, val removed: List<String>, val unavailable: List<String>)
data class Decision(val photo: Photo, val choice: Choice, val order: Long)
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun parseExifTime(date: String?, offset: String?): Long {
    if (date.isNullOrBlank()) return 0
    return runCatching {
        val hasOffset = !offset.isNullOrBlank()
        val format = SimpleDateFormat(if (hasOffset) "yyyy:MM:dd HH:mm:ssXXX" else "yyyy:MM:dd HH:mm:ss", Locale.US).apply { isLenient = false }
        format.parse(date + if (hasOffset) offset else "")?.time?.takeIf { it > 0 } ?: 0
    }.getOrDefault(0)
}

/** Horizontal wins ties; both directions use the same physical distance threshold. */
fun swipeChoice(x: Float, y: Float, threshold: Float): Choice? {
    if (maxOf(abs(x), abs(y)) < threshold) return null
    return if (abs(x) >= abs(y)) {
        if (x > 0) Choice.KEEP else Choice.DELETE
    } else {
        if (y < 0) Choice.KEEP else Choice.DELETE
    }
}

/** Preserve the next card across foreground refreshes; shuffle only unseen additions. */
fun buildDeck(previous: List<String>, photos: List<Photo>, decisions: List<Decision>): List<String> {
    val processed = decisions.mapTo(HashSet()) { it.photo.key }
    val eligible = photos.map { it.key }.filterNot { it in processed }.toSet()
    val retained = previous.filter { it in eligible }.distinct()
    val known = retained.toHashSet()
    return retained + eligible.filterNot { it in known }.shuffled()
}
