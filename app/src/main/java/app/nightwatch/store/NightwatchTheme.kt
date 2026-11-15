package app.nightwatch.store

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val Raised: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val White: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Green: Color @Composable get() = MaterialTheme.colorScheme.tertiary

internal object AppThemes {
    fun colors(dark: Boolean): ColorScheme {
        val bg = Color(if(dark) 0xFF09090A else 0xFFF7F6F3)
        val surface = Color(if(dark) 0xFF171719 else 0xFFFFFFFF)
        val raised = Color(if(dark) 0xFF29292C else 0xFFE9E8E4)
        val text = Color(if(dark) 0xFFF4F3EF else 0xFF171718)
        val muted = Color(if(dark) 0xFFB4B3B6 else 0xFF5D5D62)
        val accent = Color(if(dark) 0xFFF0EEE8 else 0xFF202022)
        val green = Color(if(dark) 0xFF91D3AA else 0xFF22683E)
        val error = Color(if(dark) 0xFFFF8C82 else 0xFFB33E3D)
        val base = if(dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent, onPrimary = Color(if(dark) 0xFF111112 else 0xFFFFFFFF),
            primaryContainer = raised, onPrimaryContainer = text,
            secondary = text, onSecondary = bg, secondaryContainer = raised, onSecondaryContainer = text,
            tertiary = green, onTertiary = bg, tertiaryContainer = raised, onTertiaryContainer = text,
            background = bg, onBackground = text, surface = surface, onSurface = text,
            surfaceVariant = raised, onSurfaceVariant = muted, surfaceTint = Color.Transparent,
            inverseSurface = text, inverseOnSurface = bg, inversePrimary = accent,
            error = error, onError = Color.White, errorContainer = raised, onErrorContainer = error,
            outline = muted, outlineVariant = raised, scrim = Color.Black,
            surfaceBright = raised, surfaceDim = bg, surfaceContainerLowest = bg,
            surfaceContainerLow = surface, surfaceContainer = surface,
            surfaceContainerHigh = raised, surfaceContainerHighest = raised
        )
    }
}

@Composable internal fun NightwatchTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when(mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val colors = remember(dark) { AppThemes.colors(dark) }
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = colors.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colors.background.toArgb()
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
