package ai.opencode.feature.drawer.motion

import ai.opencode.core.ui.BackSwipeRules

/**
 * A rightward drag on the open drawer's list inside a group, registered as
 * [DrawerPanRegions.groupBack]. [onProgress] follows the finger as a predictive back's progress
 * (0…1 across [width] dp); the release goes back one level ([onBack]) by the shared back rule, or
 * settles where it was ([onSettle]), as does a drag cut short.
 */
class DrawerGroupBackPan(
    private val width: () -> Float,
    private val onProgress: (Float) -> Unit,
    private val onBack: () -> Unit,
    private val onSettle: () -> Unit,
) {
    private var translation: Float? = null

    val isActive: Boolean get() = translation != null

    fun began() {
        translation = 0f
        onProgress(0f)
    }

    /** [translation] in dp since the pan was chosen, toward the trailing side. */
    fun changed(translation: Float) {
        if (this.translation == null) return
        val travel = translation.coerceAtLeast(0f)
        this.translation = travel
        onProgress(progress(travel, width()))
    }

    /** Lifted at [velocity] dp/s. */
    fun ended(velocity: Float) {
        val travel = translation ?: return
        translation = null
        if (BackSwipeRules.goesBack(travel, width(), velocity)) onBack() else onSettle()
    }

    fun cancelled() {
        if (translation == null) return
        translation = null
        onSettle()
    }

    companion object {
        fun progress(translation: Float, width: Float): Float =
            if (width > 0f) (translation / width).coerceIn(0f, 1f) else 0f
    }
}
