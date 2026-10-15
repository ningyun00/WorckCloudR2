package com.example.workclockr2

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Loads the current employee's profile background image from Cloudflare R2.
 *
 * Object key format: profiles/backgrounds/{employeeId}.jpg
 * Returns null when the object does not exist or cannot be decoded.
 */
suspend fun readProfileBackgroundFromR2(
    config: R2Config,
    employeeId: String
): Bitmap? {
    if (employeeId.isBlank()) return null

    val store = R2Store(config)
    val key = "profiles/backgrounds/" + employeeId + ".jpg"
    val bytes = store.readBinaryAsync(key) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
