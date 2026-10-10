package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Tone icons — the app's three-tone illustrations, recoloured for the theme.
 *
 *  Every illustrated drawable is drawn in the same three fixed colours: a dark
 *  outline, a pale body fill and a small accent. Tinted the ordinary way they
 *  collapse into a solid blob, and left alone they ignore the sixteen themes
 *  and dark mode. So each fixed colour is treated as a *role* and repainted at
 *  draw time:
 *
 *      outline #3B5BDB → the tint
 *      fill    #BAC8FF → the tint, washed out
 *      accent  #FF8A3D → the tint
 *
 *  The painter picks the tint up from the colour filter Icon() already hands
 *  every painter, so `Icon(AppIcons.X, null, tint = c)` keeps working at every
 *  call site. Where the three roles need colours of their own (the nav bar
 *  fills its icon only when selected) [ToneIcon] sets them explicitly.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendModeColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme

/** The three colours a tone icon is painted in. */
data class IconTones(val outline: Color, val fill: Color, val accent: Color) {
    companion object {
        /** The one-colour reading: body washed out, outline and accent solid. */
        fun of(tint: Color, isDark: Boolean): IconTones = IconTones(
            outline = tint,
            fill = tint.copy(alpha = tint.alpha * if (isDark) 0.30f else 0.22f),
            accent = tint
        )
    }
}

private enum class ToneRole { OUTLINE, FILL, ACCENT }

private fun roleOf(color: Color): ToneRole = when (color.toArgb()) {
    0xFFBAC8FF.toInt() -> ToneRole.FILL
    0xFFFF8A3D.toInt() -> ToneRole.ACCENT
    else -> ToneRole.OUTLINE
}

private class TonedPath(val path: Path, val role: ToneRole, val alpha: Float)

/**
 * Draws a tone drawable with its three colours swapped for [IconTones]. The
 * paths are built once, when the painter is created; drawing only fills them.
 */
class TonePainter internal constructor(
    vector: ImageVector,
    private val isDark: Boolean
) : Painter() {

    private val viewportWidth = vector.viewportWidth
    private val viewportHeight = vector.viewportHeight
    private val paths: List<TonedPath> = buildList { collect(vector.root, this) }

    /** Set by the colour filter Icon() applies — the tint of the call site. */
    private var tint: Color = Color.Black

    /** When set, wins over the tint: each role gets its own colour. */
    var tones: IconTones? by mutableStateOf(null)

    // No natural size: Icon() then falls back to 24dp, and every call site
    // sizes its icon explicitly anyway.
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        tint = (colorFilter as? BlendModeColorFilter)?.color ?: Color.Black
        return true
    }

    override fun DrawScope.onDraw() {
        val t = tones ?: IconTones.of(tint, isDark)
        scale(size.width / viewportWidth, size.height / viewportHeight, pivot = Offset.Zero) {
            for (p in paths) {
                val color = when (p.role) {
                    ToneRole.OUTLINE -> t.outline
                    ToneRole.FILL -> t.fill
                    ToneRole.ACCENT -> t.accent
                }
                if (color.alpha > 0f) drawPath(p.path, color, alpha = p.alpha)
            }
        }
    }

    private companion object {
        fun collect(group: VectorGroup, into: MutableList<TonedPath>) {
            for (node in group) when (node) {
                is VectorGroup -> collect(node, into)
                is VectorPath -> {
                    val fill = (node.fill as? SolidColor)?.value ?: continue
                    val path = node.pathData.toPath().apply { fillType = node.pathFillType }
                    into += TonedPath(path, roleOf(fill), node.fillAlpha)
                }
            }
        }
    }
}

/** A [TonePainter] for [id], rebuilt only when the drawable or light/dark changes. */
@Composable
fun rememberTonePainter(@DrawableRes id: Int): TonePainter {
    val vector = ImageVector.vectorResource(id)
    val isDark = AppTheme.accents.isDark
    return remember(vector, isDark) { TonePainter(vector, isDark) }
}

/**
 * A tone icon with every role coloured explicitly — for the few places that
 * animate one role on its own. Everywhere else, plain `Icon(AppIcons.X, …)`.
 */
@Composable
fun ToneIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    tones: IconTones,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    val painter = rememberTonePainter(id)
    painter.tones = tones
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription; role = Role.Image }
    } else Modifier
    Box(
        modifier
            .size(size)
            .then(semantics)
            .paint(painter, contentScale = ContentScale.Fit)
    )
}
