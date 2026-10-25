package app.gitbuilder

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button

/** Paleta (claro/escuro) e helpers de UI compartilhados pelas telas. */
val blue = 0xFF1A73E8.toInt()

val Context.night: Boolean get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
val Context.txt: Int get() = if (night) Color.WHITE else 0xFF202124.toInt()
val Context.muted: Int get() = if (night) 0xFF9AA0A6.toInt() else 0xFF70757A.toInt()
val Context.surface: Int get() = if (night) 0xFF2A2B2E.toInt() else 0xFFE8EAED.toInt()
val Context.green: Int get() = if (night) 0xFF81C995.toInt() else 0xFF1E8E3E.toInt()
val Context.red: Int get() = if (night) 0xFFF28B82.toInt() else 0xFFD93025.toInt()
val Context.amber: Int get() = if (night) 0xFFFDD663.toInt() else 0xFFE37400.toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.styleBtn(b: Button, fill: Int, text: Int) {
    b.background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14).toFloat() }
    b.setTextColor(text); b.isAllCaps = false; b.stateListAnimator = null
    b.minHeight = 0; b.minimumHeight = dp(52)
}
