package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Colour picker — the palette grid shared by the shift form and the Job
 *  Profile editor, and the custom-colour sheet behind its last slot.
 *
 *  A colour is stored as a raw ARGB Long either way, so a custom pick needs
 *  nothing new from storage, and a preset is simply a value that happens to be
 *  in ShiftColors.Palette. Every drawing site already runs the stored value
 *  through ShiftColors.ink / accent, so any custom colour stays legible.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ══════════════════════════════════════════════════════════════
//  Palette grid
// ══════════════════════════════════════════════════════════════

/**
 * Thirteen presets plus the custom slot: two clean rows of seven. [custom] is
 * the last custom colour in play, kept so switching to a preset and back does
 * not throw the choice away.
 */
@Composable
internal fun ColorPicker(
    selected: Long,
    custom: Long?,
    onSelect: (Long) -> Unit,
    onCustom: () -> Unit
) {
    val customColor = remember(custom) { custom?.let { Color(it.toInt()) } }
    val isCustomSelected = custom != null && custom == selected
    val rows = remember { ShiftColors.Palette.chunked(7) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            val isLastRow = rowIndex == rows.lastIndex
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { c ->
                    val v = c.toArgb().toLong()
                    Swatch(
                        color = c,
                        isSelected = v == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(v) }
                    )
                }
                if (isLastRow) {
                    CustomSwatch(
                        color = customColor,
                        isSelected = isCustomSelected,
                        modifier = Modifier.weight(1f),
                        onClick = onCustom
                    )
                }
                // Keeps the last row aligned to the same 7-column rhythm.
                val used = row.size + if (isLastRow) 1 else 0
                repeat(7 - used) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .then(if (isSelected) Modifier.border(2.5.dp, color, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(if (isSelected) 4.dp else 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxSize().background(color, CircleShape))
    }
}

/**
 * The fourteenth slot. Empty it shows a hue wheel and a plus; once a custom
 * colour is in play it shows that colour with the wheel as a thin ring, so it
 * reads as part of the palette while still saying "tap to choose any colour".
 */
@Composable
private fun CustomSwatch(
    color: Color?,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val wheel = remember {
        Brush.sweepGradient(ShiftColors.Palette + ShiftColors.Palette.first())
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .then(
                if (isSelected && color != null) Modifier.border(2.5.dp, color, CircleShape)
                else Modifier
            )
            .clickable(onClickLabel = "Choose a custom color", onClick = onClick)
            .semantics { contentDescription = "Custom color" }
            .padding(if (isSelected) 4.dp else 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxSize().background(wheel, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (color != null) {
                Box(Modifier.fillMaxSize(0.78f).background(color, CircleShape))
            } else {
                Box(
                    Modifier.fillMaxSize(0.52f).background(cs.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.Add, null, tint = cs.onSurface, modifier = Modifier.fillMaxSize(0.72f))
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Custom colour sheet
// ══════════════════════════════════════════════════════════════

private fun Color.toHsv(): FloatArray =
    FloatArray(3).also { android.graphics.Color.colorToHSV(toArgb(), it) }

private fun hsv(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s, v)))

private fun Color.hex(): String = "%06X".format(toArgb() and 0xFFFFFF)

private fun parseHex(text: String): Color? {
    val clean = text.trim().removePrefix("#")
    if (clean.length != 6) return null
    return clean.toLongOrNull(16)?.let { Color((0xFF000000 or it).toInt()) }
}

/**
 * Any colour, picked on a saturation × brightness field with a hue slider
 * beneath — the layout every design tool uses — or typed as HEX. The mark at
 * the bottom shows the colour as the app will really draw it, after the
 * legibility adjustment for the current theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomColorSheet(
    initial: Color,
    /** The name and icon of the item being coloured, for the live preview. */
    previewName: String,
    previewIcon: String?,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberAppSheetState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val start = remember(initial) { initial.toHsv() }
    var hue by rememberSaveable { mutableFloatStateOf(start[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(start[1]) }
    var value by rememberSaveable { mutableFloatStateOf(start[2]) }
    val color = remember(hue, saturation, value) { hsv(hue, saturation, value) }

    // The field follows the picker, but the picker only follows the field once
    // it holds a whole, valid code — half-typed text is left alone.
    var hexText by rememberSaveable { mutableStateOf(color.hex()) }
    var editingHex by remember { mutableStateOf(false) }
    LaunchedEffect(color) { if (!editingHex) hexText = color.hex() }

    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = "Custom color",
        caption = "Drag to pick, or type a HEX code"
    ) {
        Spacer(Modifier.height(18.dp))
        SaturationValueField(
            hue = hue,
            saturation = saturation,
            value = value,
            onChange = { s, v -> editingHex = false; saturation = s; value = v }
        )
        Spacer(Modifier.height(18.dp))
        GradientSlider(
            value = hue / 360f,
            thumbColor = hsv(hue, 1f, 1f),
            trackColors = remember { List(13) { hsv(it * 30f, 1f, 1f) } },
            contentDescription = "Hue",
            onValueChange = { editingHex = false; hue = it * 360f }
        )

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShiftIcon(
                name = previewName,
                iconName = previewIcon,
                colorValue = color.toArgb().toLong(),
                size = 52.dp
            )
            Spacer(Modifier.width(14.dp))
            OutlinedTextField(
                value = hexText,
                onValueChange = { raw ->
                    val next = raw.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                    editingHex = true
                    hexText = next
                    parseHex(next)?.toHsv()?.let { hue = it[0]; saturation = it[1]; value = it[2] }
                },
                modifier = Modifier.weight(1f),
                prefix = { Text("#", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant) },
                singleLine = true,
                isError = parseHex(hexText) == null,
                label = { Text("HEX") },
                textStyle = MaterialTheme.typography.titleMedium,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.primary,
                    unfocusedBorderColor = cs.outline,
                    focusedContainerColor = cs.background,
                    unfocusedContainerColor = cs.background,
                    cursorColor = cs.primary
                )
            )
        }

        Spacer(Modifier.height(20.dp))
        Surface(
            onClick = {
                focusManager.clearFocus()
                scope.launch {
                    sheetState.hide()
                    onPick(color)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = cs.primary,
            contentColor = cs.onPrimary,
            shadowElevation = 0.dp
        ) {
            Box(Modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text("Use color", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Saturation left → right, brightness bottom → top, for the current hue. */
@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val thumb = 26.dp
    val density = LocalDensity.current
    val thumbPx = with(density) { thumb.toPx() }
    var fieldSize by remember { mutableStateOf(IntOffset.Zero) }
    val pure = remember(hue) { hsv(hue, 1f, 1f) }
    val current = remember(hue, saturation, value) { hsv(hue, saturation, value) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .onSizeChanged { fieldSize = IntOffset(it.width, it.height) }
            .semantics { contentDescription = "Saturation and brightness" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun report(x: Float, y: Float) = onChange(
                        (x / size.width).coerceIn(0f, 1f),
                        1f - (y / size.height).coerceIn(0f, 1f)
                    )
                    report(down.position.x, down.position.y)
                    down.consume()
                    drag(down.id) { change ->
                        report(change.position.x, change.position.y)
                        change.consume()
                    }
                }
            }
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.medium)
                .background(Brush.horizontalGradient(listOf(Color.White, pure)))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
        )
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (saturation * fieldSize.x - thumbPx / 2).roundToInt(),
                        ((1f - value) * fieldSize.y - thumbPx / 2).roundToInt()
                    )
                }
                .size(thumb)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape)
                .padding(3.dp)
                .background(current, CircleShape)
        )
    }
}

/**
 * A track painted with the outcome of the axis it controls, so the choice is
 * made by looking rather than by reading numbers. Hand-rolled rather than a
 * Material Slider purely so the track can *be* the gradient.
 */
@Composable
internal fun GradientSlider(
    value: Float,
    thumbColor: Color,
    trackColors: List<Color>,
    contentDescription: String,
    onValueChange: (Float) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val thumbSize = 24.dp
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(0f) }
    val thumbPx = with(density) { thumbSize.toPx() }
    val travel = (widthPx - thumbPx).coerceAtLeast(1f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(thumbSize + 12.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(Unit) {
                // One gesture loop handles both a tap and a drag, so the track
                // responds on touch-down instead of waiting for slop.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val span = (size.width - thumbPx).coerceAtLeast(1f)
                    fun fractionAt(x: Float) = ((x - thumbPx / 2f) / span).coerceIn(0f, 1f)
                    onValueChange(fractionAt(down.position.x))
                    down.consume()
                    drag(down.id) { change ->
                        onValueChange(fractionAt(change.position.x))
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(trackColors))
                .border(1.dp, cs.outline, CircleShape)
        )
        Box(
            Modifier
                .offset { IntOffset((value.coerceIn(0f, 1f) * travel).roundToInt(), 0) }
                .size(thumbSize)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape)
                .border(3.dp, thumbColor, CircleShape)
        )
    }
}
