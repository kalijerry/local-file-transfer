package com.transferhub.app.util

import java.util.Locale

fun formatSize(bytes: Long): String {
    if (bytes < 0) return "未知大小"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
}
