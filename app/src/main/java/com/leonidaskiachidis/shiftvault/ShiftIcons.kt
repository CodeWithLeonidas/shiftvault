package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Shift & job icons — the mark that stands for a shift or a Job Profile
 *  wherever one is listed.
 *
 *  A mark is a tinted circle in the item's own colour holding either the icon
 *  the user picked or, by default, the first letter of its name. Everything
 *  that shows a shift or a profile draws it through [ShiftIcon], so the rule
 *  is the same on every screen and the letter follows a rename by itself.
 *
 *  The choice is stored as a stable key ("job_chef"), never as a resource id:
 *  ids are regenerated on every build. Keys map to drawables through the
 *  static table below — a lookup by name at runtime would also let R8 strip
 *  the drawables as unused.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════
//  Catalogue
// ══════════════════════════════════════════════════════════════

object ShiftIconCatalog {

    class Section(val title: String, val keys: List<String>)

    private val table: Map<String, Int> = linkedMapOf(
        // Shifts
        "shift_morning" to R.drawable.ic_shift_morning,
        "shift_day" to R.drawable.ic_shift_day,
        "shift_afternoon" to R.drawable.ic_shift_afternoon,
        "shift_evening" to R.drawable.ic_shift_evening,
        "shift_night" to R.drawable.ic_shift_night,
        "shift_night_owl" to R.drawable.ic_shift_night_owl,
        "shift_split" to R.drawable.ic_shift_split,
        "shift_rotating" to R.drawable.ic_shift_rotating,
        "shift_on_call" to R.drawable.ic_shift_on_call,
        "overtime" to R.drawable.ic_overtime,
        "break" to R.drawable.ic_break,
        // Time off
        "vacation" to R.drawable.ic_vacation,
        "holiday" to R.drawable.ic_holiday,
        "sick_leave" to R.drawable.ic_sick_leave,
        "time_off" to R.drawable.ic_time_off,
        // Jobs
        "job_office" to R.drawable.ic_job_office,
        "job_retail" to R.drawable.ic_job_retail,
        "job_restaurant" to R.drawable.ic_job_restaurant,
        "job_chef" to R.drawable.ic_job_chef,
        "job_barista" to R.drawable.ic_job_barista,
        "job_bar" to R.drawable.ic_job_bar,
        "job_bakery" to R.drawable.ic_job_bakery,
        "job_hotel" to R.drawable.ic_job_hotel,
        "job_cleaning" to R.drawable.ic_job_cleaning,
        "job_healthcare" to R.drawable.ic_job_healthcare,
        "job_hospital" to R.drawable.ic_job_hospital,
        "job_doctor" to R.drawable.ic_job_doctor,
        "job_pharmacy" to R.drawable.ic_job_pharmacy,
        "job_lab" to R.drawable.ic_job_lab,
        "job_ambulance" to R.drawable.ic_job_ambulance,
        "job_homecare" to R.drawable.ic_job_homecare,
        "job_childcare" to R.drawable.ic_job_childcare,
        "job_education" to R.drawable.ic_job_education,
        "job_police" to R.drawable.ic_job_police,
        "job_fire" to R.drawable.ic_job_fire,
        "job_security" to R.drawable.ic_job_security,
        "job_lifeguard" to R.drawable.ic_job_lifeguard,
        "job_delivery" to R.drawable.ic_job_delivery,
        "job_postal" to R.drawable.ic_job_postal,
        "job_driver" to R.drawable.ic_job_driver,
        "job_bus" to R.drawable.ic_job_bus,
        "job_flight" to R.drawable.ic_job_flight,
        "job_maritime" to R.drawable.ic_job_maritime,
        "job_warehouse" to R.drawable.ic_job_warehouse,
        "job_factory" to R.drawable.ic_job_factory,
        "job_construction" to R.drawable.ic_job_construction,
        "job_electrician" to R.drawable.ic_job_electrician,
        "job_mechanic" to R.drawable.ic_job_mechanic,
        "job_farm" to R.drawable.ic_job_farm,
        "job_gardening" to R.drawable.ic_job_gardening,
        "job_it" to R.drawable.ic_job_it,
        "job_support" to R.drawable.ic_job_support,
        "job_events" to R.drawable.ic_job_events,
        "job_music" to R.drawable.ic_job_music,
        "job_fitness" to R.drawable.ic_job_fitness,
        "job_salon" to R.drawable.ic_job_salon,
        "job_pets" to R.drawable.ic_job_pets,
        // General
        "clock" to R.drawable.ic_clock,
        "alarm" to R.drawable.ic_alarm,
        "bell" to R.drawable.ic_bell,
        "calendar" to R.drawable.ic_calendar,
        "calendar_check" to R.drawable.ic_calendar_check,
        "calendar_upcoming" to R.drawable.ic_calendar_upcoming,
        "location" to R.drawable.ic_location,
        "notes" to R.drawable.ic_notes,
        "wallet" to R.drawable.ic_wallet,
        "team" to R.drawable.ic_team,
        "person" to R.drawable.ic_person
    )

    private val shifts = table.keys.take(11)
    private val timeOff = listOf("vacation", "holiday", "sick_leave", "time_off")
    private val jobs = table.keys.filter { it.startsWith("job_") }
    private val general = table.keys.toList().takeLast(11)

    /** Picker order for a shift: what kind of shift first. */
    val forShifts: List<Section> = listOf(
        Section("Shifts", shifts),
        Section("Time off", timeOff),
        Section("Jobs", jobs),
        Section("General", general)
    )

    /** Picker order for a Job Profile: what kind of job first. */
    val forJobs: List<Section> = listOf(
        Section("Jobs", jobs),
        Section("Shifts", shifts),
        Section("Time off", timeOff),
        Section("General", general)
    )

    /** The drawable for [key], or null for no icon / a key this build doesn't know. */
    @DrawableRes
    fun resOf(key: String?): Int? = key?.let(table::get)

    /** "job_night_owl" → "Night owl" — for the picker's labels and TalkBack. */
    fun labelOf(key: String): String =
        key.removePrefix("job_").removePrefix("shift_").replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
            .let { if (it == "It") "IT" else it }
}

/** The default mark: the first letter or digit of [name], or null if it has none. */
fun initialOf(name: String): String? =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase()

// ══════════════════════════════════════════════════════════════
//  The mark
// ══════════════════════════════════════════════════════════════

/**
 * A shift's or profile's mark: [iconName]'s icon, or [name]'s first letter,
 * in a circle tinted with [colorValue]. The stored colour is run through
 * ShiftColors first, so any custom colour stays legible on either theme.
 */
@Composable
fun ShiftIcon(
    name: String,
    iconName: String?,
    colorValue: Long,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    /** Muted, for a row that is disabled or out of focus. */
    dimmed: Boolean = false
) {
    val base = rememberShiftColor(colorValue)
    val accent = rememberShiftAccent(base)
    val ink = rememberShiftInk(base)
    val isDark = AppTheme.accents.isDark
    val res = ShiftIconCatalog.resOf(iconName)
    val letter = initialOf(name)

    Box(
        modifier
            .size(size)
            .alpha(if (dimmed) 0.45f else 1f)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (isDark) 0.24f else 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            res != null -> ToneIcon(
                id = res,
                contentDescription = null,
                tones = IconTones(outline = ink, fill = accent.copy(alpha = 0.35f), accent = accent),
                size = size * 0.6f
            )
            letter != null -> Text(
                letter,
                color = ink,
                fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() },
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            // A brand-new item without a name yet: a quiet placeholder.
            else -> ToneIcon(
                id = R.drawable.ic_calendar,
                contentDescription = null,
                tones = IconTones.of(ink.copy(alpha = 0.55f), isDark),
                size = size * 0.55f
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Form row + picker sheet
// ══════════════════════════════════════════════════════════════

/**
 * The form control: a live preview of the mark, what it is, and a way to
 * change it. Sits right under the name field, so the letter updating as the
 * name is typed is seen happening.
 */
@Composable
fun IconField(
    name: String,
    iconName: String?,
    colorValue: Long,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Choose an icon", onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = iconName to initialOf(name),
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.8f)) togetherWith
                    (fadeOut(tween(100)) + scaleOut(tween(150), targetScale = 0.8f))
            },
            label = "iconPreview"
        ) { (icon, _) ->
            ShiftIcon(name = name, iconName = icon, colorValue = colorValue, size = 44.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Icon", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                iconName?.let(ShiftIconCatalog::labelOf) ?: "First letter of the name",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text("Change", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        Spacer(Modifier.width(4.dp))
        Icon(AppIcons.ChevronRight, null, tint = cs.primary, modifier = Modifier.size(18.dp))
    }
}

/**
 * Every icon, in sections, each drawn in the item's own colour so the choice
 * is made by seeing the result. The first cell is the default letter. One tap
 * picks and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPickerSheet(
    name: String,
    colorValue: Long,
    selected: String?,
    sections: List<ShiftIconCatalog.Section>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberAppSheetState()
    val scope = rememberCoroutineScope()
    val pick: (String?) -> Unit = { key ->
        // Slide down first, then change the form — see MainActivity's sheets.
        scope.launch {
            sheetState.hide()
            onPick(key)
        }
    }

    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = "Choose an icon",
        caption = "Or keep the first letter of the name"
    ) {
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SheetLabel("Default")
            Spacer(Modifier.height(10.dp))
            IconGrid(listOf<String?>(null), name, colorValue, selected, pick)
            sections.forEach { section ->
                Spacer(Modifier.height(18.dp))
                SheetLabel(section.title)
                Spacer(Modifier.height(10.dp))
                IconGrid(section.keys, name, colorValue, selected, pick)
            }
        }
    }
}

private const val GRID_COLUMNS = 6

@Composable
private fun IconGrid(
    keys: List<String?>,
    name: String,
    colorValue: Long,
    selected: String?,
    onPick: (String?) -> Unit
) {
    val ring = rememberShiftAccent(colorValue)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val isSelected = key == selected
                    val label = key?.let(ShiftIconCatalog::labelOf) ?: "First letter"
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .then(if (isSelected) Modifier.border(2.dp, ring, CircleShape) else Modifier)
                            .clickable { onPick(key) }
                            .clearAndSetSemantics {
                                contentDescription = label
                                role = Role.RadioButton
                                this.selected = isSelected
                            }
                            .padding(if (isSelected) 4.dp else 0.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Every cell previews the real mark; the letter cell
                        // ignores the key and shows the name's initial.
                        ShiftIcon(
                            name = name,
                            iconName = key,
                            colorValue = colorValue,
                            size = 48.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
