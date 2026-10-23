package com.example.workclockr2

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal val AppZone: ZoneOffset = ZoneOffset.ofHours(8)
internal val DisplayTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

internal fun formatAppDateTime(value: String, pattern: DateTimeFormatter): String =
    runCatching {
        ZonedDateTime.parse(value).withZoneSameInstant(AppZone).format(pattern)
    }.recoverCatching {
        LocalDateTime.parse(value).format(pattern)
    }.getOrDefault(value.replace("T", " ").take(23))
