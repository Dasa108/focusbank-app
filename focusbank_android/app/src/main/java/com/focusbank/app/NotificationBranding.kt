package com.focusbank.app

import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Every FocusBank notification shows the same identity: the single-colour status-bar mark
 * (Android only allows alpha for small icons), the brand green as accent, and the full-colour
 * launcher logo as the large icon so the new logo is visible in the notification shade.
 */
private const val BRAND_GREEN = 0xFF41651B.toInt()

@Volatile private var cachedLogo: Bitmap? = null

private fun logo(context: Context): Bitmap? = cachedLogo ?: runCatching {
    ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(192, 192)
}.getOrNull()?.also { cachedLogo = it }

fun NotificationCompat.Builder.withFocusBankBranding(context: Context): NotificationCompat.Builder =
    setSmallIcon(R.drawable.ic_stat_focusbank)
        .setColor(BRAND_GREEN)
        .setLargeIcon(logo(context))
