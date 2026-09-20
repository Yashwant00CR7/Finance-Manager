package com.yk.finance.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The greys the app was modelled on, and a light counterpart.
 *
 * No dynamic colour. Material You would recolour the whole surface from the wallpaper,
 * which is exactly what must not happen here: the category colours carry meaning, and a
 * wallpaper-tinted background would shift under them every time it changed.
 */
private val DarkGrey = Color(0xFF333333)
private val DarkBar = Color(0xFF3C3C3C)
private val DarkCard = Color(0xFF3A3A3A)
private val DarkDivider = Color(0xFF4A4A4A)

private val LightBackground = Color(0xFFF2F2F2)
private val LightBar = Color(0xFFFFFFFF)
private val LightCard = Color(0xFFFFFFFF)
private val LightDivider = Color(0xFFDDDDDD)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFBDBDBD),
    onPrimary = Color(0xFF1F1F1F),
    secondary = Color(0xFF9E9E9E),
    background = DarkGrey,
    onBackground = Color.White,
    surface = DarkGrey,
    onSurface = Color.White,
    surfaceVariant = DarkCard,
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainer = DarkBar,
    surfaceContainerHigh = DarkCard,
    surfaceContainerHighest = DarkCard,
    outline = DarkDivider,
    outlineVariant = DarkDivider,
    error = Color(0xFFEF5350),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF424242),
    onPrimary = Color.White,
    secondary = Color(0xFF616161),
    background = LightBackground,
    onBackground = Color(0xFF1A1A1A),
    surface = LightBackground,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = LightCard,
    onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceContainer = LightBar,
    surfaceContainerHigh = LightCard,
    surfaceContainerHighest = LightCard,
    outline = LightDivider,
    outlineVariant = LightDivider,
    error = Color(0xFFD32F2F),
)

/**
 * Money's two colours, which Material has no slot for.
 *
 * Deliberately not primary/error: expense is not an error state, and income is not a
 * brand colour. They are their own vocabulary and every screen reads them from here so
 * a debit is the same red in the list, the donut and the budget bar.
 */
data class MoneyColors(
    val expense: Color,
    val income: Color,
    val header: Color,
    val muted: Color,
)

val LocalMoneyColors = staticCompositionLocalOf {
    MoneyColors(
        expense = Color(0xFFEF5350),
        income = Color(0xFF66BB6A),
        header = DarkBar,
        muted = Color(0xFFB0B0B0),
    )
}

@Composable
fun FinanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val money = if (darkTheme) {
        MoneyColors(
            expense = Color(0xFFEF5350),
            income = Color(0xFF66BB6A),
            header = DarkBar,
            muted = Color(0xFFB0B0B0),
        )
    } else {
        MoneyColors(
            expense = Color(0xFFD32F2F),
            income = Color(0xFF2E7D32),
            header = Color(0xFFEDEDED),
            muted = Color(0xFF6B6B6B),
        )
    }

    // The status bar is hidden, not recoloured. window.statusBarColor was doing nothing
    // anyway - it is deprecated and ignored from targetSdk 35, which this app is on.
    //
    // Transient-by-swipe rather than a hard hide: a swipe from the top still floats the
    // clock and notifications in for a few seconds and then takes them away again, so
    // nothing is locked away and nothing sits there.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !darkTheme
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    CompositionLocalProvider(LocalMoneyColors provides money) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
