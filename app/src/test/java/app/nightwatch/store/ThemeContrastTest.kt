package app.nightwatch.store

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ThemeContrastTest {
    private fun contrast(a: Color,b: Color): Float {
        val one = a.luminance(); val two = b.luminance()
        return (maxOf(one,two)+.05f)/(minOf(one,two)+.05f)
    }
    @Test fun bothThemesHaveReadableTextAndActions() {
        for(dark in listOf(false,true)) {
            val colors = AppThemes.colors(dark)
            assertTrue(contrast(colors.onSurface,colors.surface) >= 4.5f)
            assertTrue(contrast(colors.onSurfaceVariant,colors.surface) >= 4.5f)
            assertTrue(contrast(colors.primary,colors.background) >= 4.5f)
            assertTrue(contrast(colors.onPrimary,colors.primary) >= 4.5f)
            assertTrue(contrast(colors.tertiary,colors.surface) >= 4.5f)
        }
    }
}
