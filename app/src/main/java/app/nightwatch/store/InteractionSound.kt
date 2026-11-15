package app.nightwatch.store

import android.content.Context
import android.view.SoundEffectConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/** Native UI click: follows Android's sound-effects setting and volume. */
@Composable internal fun rememberInteractionSound(): () -> Unit {
    val view = LocalView.current
    return {
        if(view.context.getSharedPreferences("nightwatch", Context.MODE_PRIVATE).getBoolean("interaction_sounds", true)) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        }
    }
}
