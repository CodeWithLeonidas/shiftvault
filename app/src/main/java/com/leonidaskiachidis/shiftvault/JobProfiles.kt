package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Job Profiles — one user, several jobs.
 *
 *  A profile owns its shifts outright: every Shift carries a jobId, and Home,
 *  Shifts and Analytics all read through the *same* selection, held once in
 *  [ProfileState] and persisted by ShiftManager. Switching a profile anywhere
 *  therefore switches it everywhere — there is no per-screen copy of the choice
 *  that could drift out of step.
 *
 *  The data stays in one flat store keyed by jobId rather than one store per
 *  profile. Filtering to the active job is then a grouping question, which is
 *  what later work — hours per job, earnings per job, comparing two jobs —
 *  needs anyway.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

// ══════════════════════════════════════════════════════════════
//  Model helpers
// ══════════════════════════════════════════════════════════════

/** Outcome of a profile-to-profile copy, so the UI can report it honestly. */
data class CopyResult(val copied: Int, val skipped: Int)

/**
 * Stamps a legacy record onto the default profile. Applied by ShiftManager as
 * data is decoded, so nothing downstream has to handle a null jobId.
 */
fun Shift.withProfile(): Shift =
    if (jobId == null) copy(jobId = JobProfile.DEFAULT_ID) else this

/**
 * What makes two saved shifts "the same one" for the purposes of the copy
 * flow. Deliberately the visible identity — name, hours, colour — so copying
 * twice can't quietly stack duplicates the user would have to weed out.
 */
fun Shift.identity(): String = "$name|$startTime|$endTime|$colorValue|$dayType"

/**
 * Reading and writing the slice of a shift map that belongs to one profile.
 *
 * Screens show one profile, but they must persist the *whole* map: writing back
 * only what is on screen would delete every other job's shifts.
 */
object ProfileScope {

    /** Only the days, and the shifts within them, belonging to [profileId]. */
    fun shifts(
        all: Map<LocalDate, List<Shift>>,
        profileId: String
    ): Map<LocalDate, List<Shift>> =
        all.mapValues { (_, list) -> list.filter { it.jobId == profileId } }
            .filterValues { it.isNotEmpty() }

    /** One day's shifts for [profileId], in the order they were added. */
    fun daySlice(
        all: Map<LocalDate, List<Shift>>,
        date: LocalDate,
        profileId: String
    ): List<Shift> = all[date].orEmpty().filter { it.jobId == profileId }

    /**
     * Returns [all] with this profile's shifts on [date] replaced by [slice],
     * every other profile's left exactly as they were.
     */
    fun withDaySlice(
        all: Map<LocalDate, List<Shift>>,
        date: LocalDate,
        profileId: String,
        slice: List<Shift>
    ): Map<LocalDate, List<Shift>> {
        val others = all[date].orEmpty().filterNot { it.jobId == profileId }
        val merged = slice + others
        return if (merged.isEmpty()) all - date else all + (date to merged)
    }
}

/** Per-profile counts shown in the switcher, so each row says what it holds. */
data class ProfileSummary(val savedShifts: Int, val loggedDays: Int)

fun buildProfileSummaries(
    profiles: List<JobProfile>,
    templates: List<Shift>,
    shifts: Map<LocalDate, List<Shift>>
): Map<String, ProfileSummary> {
    val saved = HashMap<String, Int>()
    for (t in templates) saved[t.jobId ?: JobProfile.DEFAULT_ID] =
        (saved[t.jobId ?: JobProfile.DEFAULT_ID] ?: 0) + 1

    val days = HashMap<String, Int>()
    for ((_, list) in shifts) {
        val seen = HashSet<String>()
        for (s in list) {
            val id = s.jobId ?: JobProfile.DEFAULT_ID
            if (seen.add(id)) days[id] = (days[id] ?: 0) + 1
        }
    }

    return profiles.associate {
        it.id to ProfileSummary(saved[it.id] ?: 0, days[it.id] ?: 0)
    }
}

// ══════════════════════════════════════════════════════════════
//  Shared selection — the single source of truth
// ══════════════════════════════════════════════════════════════

@Stable
class ProfileState internal constructor(
    val profiles: List<JobProfile>,
    /** Never null: ShiftManager guarantees at least one profile exists. */
    val active: JobProfile,
    private val manager: ShiftManager,
    private val scope: CoroutineScope
) {
    val activeId: String get() = active.id

    /** Profiles other than the active one — the candidates for a copy. */
    val others: List<JobProfile> get() = profiles.filterNot { it.id == active.id }

    fun select(id: String) {
        if (id == active.id) return
        scope.launch { manager.saveActiveProfileId(id) }
    }

    fun save(profile: JobProfile, makeActive: Boolean = false) {
        scope.launch {
            manager.saveProfile(profile)
            if (makeActive) manager.saveActiveProfileId(profile.id)
        }
    }

    fun delete(id: String) {
        scope.launch { manager.deleteProfile(id) }
    }

    /** Files copies of [shifts] under the active profile. Source untouched. */
    suspend fun copySavedShifts(shifts: List<Shift>): CopyResult =
        manager.copySavedShiftsToProfile(active.id, shifts)
}

/**
 * Hoist this once, at the top of the app, and hand the result to every screen.
 * Collecting it per screen would give each one its own selection.
 */
@Composable
fun rememberProfileState(shiftManager: ShiftManager): ProfileState {
    val scope = rememberCoroutineScope()
    val profiles by shiftManager.getProfiles
        .collectAsState(initial = listOf(JobProfile.Default))
    val activeId by shiftManager.getActiveProfileId
        .collectAsState(initial = JobProfile.DEFAULT_ID)

    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    return remember(profiles, active) { ProfileState(profiles, active, shiftManager, scope) }
}

// ══════════════════════════════════════════════════════════════
//  Selector chip — the same control on Home, Shifts and Analytics
// ══════════════════════════════════════════════════════════════

/**
 * Shows which job you are looking at and opens the switcher. Identical on all
 * three screens on purpose: the profile is one app-wide setting, so it should
 * not look like three different controls.
 */
@Composable
fun ProfileChip(
    profile: JobProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = cs.surfaceVariant,
        contentColor = cs.onSurface,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShiftIcon(profile.name, profile.iconName, profile.colorValue, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                profile.name,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A cap rather than a weight: on Analytics this chip sits in a
                // horizontally-scrolling row, where a weighted child has no
                // remaining space to be given a share of.
                modifier = Modifier.widthIn(max = 190.dp)
            )
            Spacer(Modifier.width(7.dp))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = "Switch job profile",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(15.dp).rotate(90f)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Switcher sheet
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherSheet(
    profiles: List<JobProfile>,
    activeId: String,
    summaries: Map<String, ProfileSummary>,
    sheetState: AppSheetState,
    onSelect: (String) -> Unit,
    onEdit: (JobProfile) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = "Job Profile",
        caption = "Each job keeps its own shifts and its own stats"
    ) {
        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    summary = summaries[profile.id],
                    selected = profile.id == activeId,
                    onClick = { onSelect(profile.id) },
                    onEdit = { onEdit(profile) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        AddProfileRow(onClick = onCreate)
    }
}

@Composable
private fun ProfileRow(
    profile: JobProfile,
    summary: ProfileSummary?,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accent = rememberShiftAccent(profile.colorValue)

    // The selected row is filled with the job's own colour — at a glance the
    // sheet answers "which job am I on" before you have read a single word.
    val container by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.16f) else cs.background,
        animationSpec = tween(160),
        label = "profileRowContainer"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else cs.outline,
                shape = MaterialTheme.shapes.large
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShiftIcon(profile.name, profile.iconName, profile.colorValue, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                profileSummaryText(summary),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }

        if (selected) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Check, "Selected",
                    tint = cs.surface, modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable(onClick = onEdit),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Edit, "Edit ${profile.name}",
                tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp)
            )
        }
    }
}

private fun profileSummaryText(summary: ProfileSummary?): String {
    if (summary == null || (summary.savedShifts == 0 && summary.loggedDays == 0)) {
        return "No shifts yet"
    }
    val saved = "${summary.savedShifts} saved shift${if (summary.savedShifts == 1) "" else "s"}"
    if (summary.loggedDays == 0) return saved
    return "$saved · ${summary.loggedDays} day${if (summary.loggedDays == 1) "" else "s"} logged"
}

@Composable
private fun AddProfileRow(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(cs.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Add, null, tint = cs.primary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "New job profile",
                style = MaterialTheme.typography.titleMedium,
                color = cs.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Track a second job separately",
                style = MaterialTheme.typography.bodySmall,
                color = cs.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(AppIcons.ChevronRight, null, tint = cs.primary, modifier = Modifier.size(18.dp))
    }
}

// ══════════════════════════════════════════════════════════════
//  Editor sheet
//
//  A sheet rather than a page: a profile is a name, a colour and an optional
//  rate. The full-screen form is what the shift editor earns with its twenty
//  controls — sending three fields there would make adding a second job feel
//  heavier than it is.
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorSheet(
    /** Null creates a new profile; non-null edits that one. */
    editing: JobProfile?,
    currencySymbol: String,
    sheetState: AppSheetState,
    /** False for the last remaining profile — the app is always on some job. */
    canDelete: Boolean,
    onSave: (JobProfile) -> Unit,
    onDelete: (JobProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents

    var name by rememberSaveable(editing) { mutableStateOf(editing?.name ?: "") }
    var colorValue by rememberSaveable(editing) {
        mutableStateOf(editing?.colorValue ?: suggestedProfileColor())
    }
    var iconName by rememberSaveable(editing) { mutableStateOf(editing?.iconName) }
    // Kept once used, so trying a preset and coming back doesn't lose it.
    var customColor by rememberSaveable(editing) {
        mutableStateOf(editing?.colorValue?.takeIf { !ShiftColors.isPreset(Color(it.toInt())) })
    }
    var showCustomColor by rememberSaveable { mutableStateOf(false) }
    var showIconPicker by rememberSaveable { mutableStateOf(false) }
    var rateText by rememberSaveable(editing) {
        mutableStateOf(
            editing?.defaultHourlyRate?.takeIf { it > 0.0 }?.let(::formatDecimalInput) ?: ""
        )
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Goals and the take-home estimate. Blank means "not set" (stored as 0).
    var earningsGoalText by rememberSaveable(editing) {
        mutableStateOf(editing?.earningsGoal?.takeIf { it > 0.0 }?.let(::formatDecimalInput) ?: "")
    }
    var hoursGoalText by rememberSaveable(editing) {
        mutableStateOf(editing?.hoursGoal?.takeIf { it > 0.0 }?.let(::formatDecimalInput) ?: "")
    }
    var deductionText by rememberSaveable(editing) {
        mutableStateOf(editing?.deductionPercent?.takeIf { it > 0.0 }?.let(::formatDecimalInput) ?: "")
    }

    // How the job pays. Blank number fields mean "not set" (stored as 0).
    val initialPay = editing?.pay ?: PaySetup()
    var payMode by rememberSaveable(editing) { mutableStateOf(initialPay.mode) }
    var salaryHistory by rememberSaveable(editing, stateSaver = jsonSaver<List<SalaryRate>>()) {
        mutableStateOf(initialPay.salaryHistory)
    }
    var firstSalaryText by rememberSaveable(editing) { mutableStateOf("") }
    var referenceRateText by rememberSaveable(editing) {
        mutableStateOf(initialPay.referenceHourlyRate.takeIf { it > 0.0 }?.let(::formatDecimalInput) ?: "")
    }
    var contractHoursText by rememberSaveable(editing) {
        mutableStateOf(initialPay.contractHoursPerWeek.takeIf { it > 0.0 }?.let(::formatDecimalInput) ?: "")
    }
    var overtimeSource by rememberSaveable(editing) { mutableStateOf(initialPay.overtimeSource) }
    var dailyLimitText by rememberSaveable(editing) {
        mutableStateOf(initialPay.dailyLimitHours.takeIf { it > 0.0 }?.let(::formatDecimalInput) ?: "")
    }
    var overtimeMultiplier by rememberSaveable(editing) { mutableDoubleStateOf(initialPay.overtimeMultiplier) }
    var overtimeCompensation by rememberSaveable(editing) { mutableStateOf(initialPay.overtimeCompensation) }
    val isFixed = payMode == PayMode.FIXED_MONTHLY
    // Overtime rules, goals and deductions are set once and rarely touched:
    // folded away so the editor opens on what every job needs.
    var advancedOpen by rememberSaveable(editing) { mutableStateOf(false) }
    // A salary typed for a profile that had none yet becomes its starting salary.
    val effectiveHistory = salaryHistory.ifEmpty {
        parseCents(firstSalaryText)?.takeIf { it > 0L }?.let { listOf(SalaryRate(null, it)) } ?: emptyList()
    }

    // Pay period, field by field so each survives rotation on its own.
    val initialPeriod = editing?.payPeriod ?: PayPeriod()
    var payCycle by rememberSaveable(editing) { mutableStateOf(initialPeriod.cycle) }
    var payStartDay by rememberSaveable(editing) { mutableIntStateOf(initialPeriod.startDay) }
    var payWeekStart by rememberSaveable(editing) { mutableIntStateOf(initialPeriod.weekStart) }
    var payAnchor by rememberSaveable(editing) { mutableStateOf(initialPeriod.anchor) }
    var showAnchorPicker by rememberSaveable { mutableStateOf(false) }
    val payPeriod = PayPeriod(payCycle, payStartDay, payWeekStart, payAnchor)

    // A salaried job needs its salary; everything else is optional.
    val canSave = name.isNotBlank() && (!isFixed || effectiveHistory.isNotEmpty())

    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = if (editing == null) "New job profile" else "Edit profile",
        caption = if (editing == null)
            "It starts empty — add shifts, or copy them from another job"
        else
            "Renaming keeps every shift filed under this job"
    ) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(20.dp))

            FieldLabel("Name")
            Spacer(Modifier.height(10.dp))
            FormTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. Restaurant",
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            IconField(
                name = name,
                iconName = iconName,
                colorValue = colorValue,
                onClick = { showIconPicker = true }
            )

            Spacer(Modifier.height(20.dp))
            FieldLabel("Color")
            Spacer(Modifier.height(12.dp))
            ColorPicker(
                selected = colorValue,
                custom = customColor,
                onSelect = { colorValue = it },
                onCustom = { showCustomColor = true }
            )

            Spacer(Modifier.height(20.dp))
            FieldLabel("How you're paid")
            Spacer(Modifier.height(10.dp))
            SegmentedControl(
                options = PayMode.entries.map { it to it.label },
                selected = payMode,
                onSelect = { mode ->
                    payMode = mode
                    // A monthly salary is paid per month.
                    if (mode == PayMode.FIXED_MONTHLY) payCycle = PayCycle.MONTHLY
                }
            )

            if (isFixed) {
                Spacer(Modifier.height(20.dp))
                SalarySection(
                    history = salaryHistory,
                    payPeriod = PayPeriod(PayCycle.MONTHLY, payStartDay, payWeekStart, payAnchor),
                    currencySymbol = currencySymbol,
                    firstSalaryText = firstSalaryText,
                    onFirstSalaryText = { firstSalaryText = it },
                    onSetSalary = { from, cents ->
                        salaryHistory = PaySetup(salaryHistory = effectiveHistory).withSalary(from, cents).salaryHistory
                        firstSalaryText = ""
                    },
                    onRemove = { rate -> salaryHistory = salaryHistory - rate }
                )
            } else {
                Spacer(Modifier.height(20.dp))
                FieldLabel("Default hourly rate", optional = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Prefills the rate on new shifts created for this job. Each shift can have its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                FormTextField(
                    value = rateText,
                    onValueChange = { rateText = it },
                    placeholder = "0.00",
                    singleLine = true,
                    keyboardType = KeyboardType.Decimal,
                    prefix = currencySymbol
                )
            }

            Spacer(Modifier.height(20.dp))
            PayPeriodEditor(
                period = payPeriod,
                monthlyOnly = isFixed,
                onCycle = { cycle ->
                    // A fresh bi-weekly cycle starts this week, which is the
                    // likeliest truth and easy to move.
                    if (cycle == PayCycle.BIWEEKLY && payCycle != PayCycle.BIWEEKLY &&
                        editing?.payPeriod?.cycle != PayCycle.BIWEEKLY
                    ) {
                        payAnchor = weekContaining(LocalDate.now(), "Monday").start.toString()
                    }
                    payCycle = cycle
                },
                onStartDay = { payStartDay = it },
                onWeekStart = { payWeekStart = it },
                onPickAnchor = { showAnchorPicker = true }
            )

            Spacer(Modifier.height(20.dp))
            val advancedSummary = buildList {
                add("Overtime: ${overtimeSource.label.lowercase()}, ${overtimeCompensation.label.lowercase()}")
                parseDecimal(earningsGoalText)?.takeIf { it > 0.0 }?.let { add("goal $currencySymbol${formatDecimalInput(it)}") }
                parseDecimal(hoursGoalText)?.takeIf { it > 0.0 }?.let { add("goal ${formatDecimalInput(it)}h") }
                parseDecimal(deductionText)?.takeIf { it > 0.0 }?.let { add("${formatDecimalInput(it)}% deductions") }
            }.joinToString(" \u00b7 ")
            CollapsibleSection(
                icon = AppIcons.Statistics,
                title = "Advanced",
                summary = advancedSummary,
                summaryActive = false,
                expanded = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen }
            ) {
                if (isFixed) {
                    SalaryReferenceFields(
                        history = effectiveHistory,
                        currencySymbol = currencySymbol,
                        referenceRateText = referenceRateText,
                        onReferenceRate = { referenceRateText = it },
                        contractHoursText = contractHoursText,
                        onContractHours = { contractHoursText = it }
                    )
                    Spacer(Modifier.height(20.dp))
                }
                OvertimeSection(
                    isFixed = isFixed,
                    source = overtimeSource,
                    onSource = { overtimeSource = it },
                    contractHoursText = contractHoursText,
                    onContractHours = { contractHoursText = it },
                    dailyLimitText = dailyLimitText,
                    onDailyLimit = { dailyLimitText = it },
                    multiplier = overtimeMultiplier,
                    onMultiplier = { overtimeMultiplier = it },
                    compensation = overtimeCompensation,
                    onCompensation = { overtimeCompensation = it },
                    hasReferenceRate = (parseDecimal(referenceRateText) ?: 0.0) > 0.0
                )

                Spacer(Modifier.height(20.dp))
                FieldLabel("Goals per pay period", optional = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Home shows how far along each pay period is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        FormTextField(
                            value = earningsGoalText,
                            onValueChange = { earningsGoalText = it },
                            placeholder = "Earnings",
                            singleLine = true,
                            keyboardType = KeyboardType.Decimal,
                            prefix = currencySymbol
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        FormTextField(
                            value = hoursGoalText,
                            onValueChange = { hoursGoalText = it },
                            placeholder = "Hours",
                            singleLine = true,
                            keyboardType = KeyboardType.Decimal,
                            suffix = "h"
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                FieldLabel("Deductions", optional = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tax and insurance, roughly. Only used to estimate your take-home pay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                FormTextField(
                    value = deductionText,
                    onValueChange = { deductionText = it },
                    placeholder = "0",
                    singleLine = true,
                    keyboardType = KeyboardType.Decimal,
                    suffix = "%"
                )
            }

            Spacer(Modifier.height(22.dp))
            Surface(
                onClick = {
                    if (!canSave) return@Surface
                    onSave(
                        (editing ?: JobProfile(
                            id = JobProfile.newId(),
                            name = "",
                            colorValue = colorValue,
                            createdAt = System.currentTimeMillis()
                        )).copy(
                            name = name.trim(),
                            colorValue = colorValue,
                            iconName = iconName,
                            defaultHourlyRate = parseDecimal(rateText) ?: 0.0,
                            payPeriod = payPeriod,
                            earningsGoal = parseDecimal(earningsGoalText) ?: 0.0,
                            hoursGoal = parseDecimal(hoursGoalText) ?: 0.0,
                            deductionPercent = (parseDecimal(deductionText) ?: 0.0).coerceAtMost(100.0),
                            pay = PaySetup(
                                mode = payMode,
                                salaryHistory = effectiveHistory,
                                referenceHourlyRate = parseDecimal(referenceRateText) ?: 0.0,
                                contractHoursPerWeek = (parseDecimal(contractHoursText) ?: 0.0).coerceAtMost(168.0),
                                overtimeSource = overtimeSource,
                                dailyLimitHours = (parseDecimal(dailyLimitText) ?: 0.0).coerceAtMost(24.0),
                                overtimeMultiplier = overtimeMultiplier,
                                overtimeCompensation = overtimeCompensation
                            )
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                color = if (canSave) cs.primary else cs.surfaceVariant,
                contentColor = if (canSave) cs.onPrimary else cs.onSurfaceVariant,
                shadowElevation = 0.dp
            ) {
                Box(Modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (editing == null) "Create profile" else "Save changes",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (editing != null && canDelete) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable { confirmDelete = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Delete profile",
                        style = MaterialTheme.typography.labelLarge,
                        color = accents.negative
                    )
                }
            }
        }
    }

    if (showCustomColor) {
        CustomColorSheet(
            initial = Color((customColor ?: colorValue).toInt()),
            previewName = name,
            previewIcon = iconName,
            onDismiss = { showCustomColor = false },
            onPick = {
                val value = it.toArgb().toLong()
                colorValue = value
                customColor = value
                showCustomColor = false
            }
        )
    }

    if (showIconPicker) {
        IconPickerSheet(
            name = name,
            colorValue = colorValue,
            selected = iconName,
            sections = ShiftIconCatalog.forJobs,
            onPick = { iconName = it; showIconPicker = false },
            onDismiss = { showIconPicker = false }
        )
    }

    if (showAnchorPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching { LocalDate.parse(payAnchor) }
                .getOrDefault(LocalDate.now()).toEpochDay() * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { showAnchorPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        payAnchor = LocalDate.ofEpochDay(it / 86_400_000L).toString()
                    }
                    showAnchorPicker = false
                }) { Text("OK", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { showAnchorPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }

    if (confirmDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            title = {
                Text(
                    "Delete \"${editing.name}\"?",
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface
                )
            },
            text = {
                Text(
                    "Every shift filed under this job — saved shifts and calendar " +
                            "days alike — is deleted with it. Your other job profiles " +
                            "are not affected. This can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; onDelete(editing) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accents.negative)
                ) { Text("Delete", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Pay cycle controls, with the period you are in right now spelled out below
 * so the setting can be checked at a glance instead of worked out.
 */
@Composable
private fun PayPeriodEditor(
    period: PayPeriod,
    /** A monthly salary is paid per month: no weekly cycles to pick from. */
    monthlyOnly: Boolean = false,
    onCycle: (PayCycle) -> Unit,
    onStartDay: (Int) -> Unit,
    onWeekStart: (Int) -> Unit,
    onPickAnchor: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    FieldLabel("Pay period")
    Spacer(Modifier.height(6.dp))
    Text(
        if (monthlyOnly) "The month one payslip covers. It can start on any day."
        else "The stretch one payslip covers. Home totals, the Wallet and exports use it.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    if (!monthlyOnly) {
        SegmentedControl(
            options = listOf(
                PayCycle.MONTHLY to "Monthly",
                PayCycle.WEEKLY to "Weekly",
                PayCycle.BIWEEKLY to "Every 2 wks"
            ),
            selected = period.cycle,
            onSelect = onCycle
        )
        Spacer(Modifier.height(12.dp))
    }

    when (period.cycle) {
        PayCycle.MONTHLY -> Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(cs.background)
                .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Starts on the", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(
                    if (period.startDay <= 1) "1st (calendar month)" else PayPeriod.ordinal(period.startDay),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface
                )
            }
            CircleIconButton(
                icon = AppIcons.ChevronLeft, contentDescription = "Earlier day",
                onClick = { onStartDay(if (period.startDay <= 1) 31 else period.startDay - 1) },
                diameter = 40.dp, iconSize = 18.dp, container = cs.surfaceVariant, elevation = 0.dp
            )
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                icon = AppIcons.ChevronRight, contentDescription = "Later day",
                onClick = { onStartDay(if (period.startDay >= 31) 1 else period.startDay + 1) },
                diameter = 40.dp, iconSize = 18.dp, container = cs.surfaceVariant, elevation = 0.dp
            )
        }

        PayCycle.WEEKLY -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (1..7).forEach { dow ->
                val selected = dow == period.weekStart
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (selected) cs.primary else cs.surfaceVariant)
                        .clickable { onWeekStart(dow) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        java.time.DayOfWeek.of(dow)
                            .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                            .take(3),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        PayCycle.BIWEEKLY -> Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(cs.background)
                .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
                .clickable(onClick = onPickAnchor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("A pay period started on", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(
                    runCatching {
                        LocalDate.parse(period.anchor)
                            .format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
                    }.getOrDefault(period.anchor),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface
                )
            }
            Icon(AppIcons.Calendar, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "Current period: " + period.periodContaining(LocalDate.now()).label(),
        style = MaterialTheme.typography.bodySmall,
        color = cs.primary
    )
}

/**
 * The salary and what goes with it. The salary is kept as a history: a change
 * applies from a chosen pay period on, and earlier periods keep what they
 * were paid. The starting salary can be corrected for all months instead.
 */
@Composable
private fun SalarySection(
    history: List<SalaryRate>,
    payPeriod: PayPeriod,
    currencySymbol: String,
    firstSalaryText: String,
    onFirstSalaryText: (String) -> Unit,
    onSetSalary: (from: LocalDate?, cents: Long) -> Unit,
    onRemove: (SalaryRate) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val money = { cents: Long -> formatCents(cents, currencySymbol) }
    val salaries = PaySetup(salaryHistory = history).salaries

    FieldLabel("Monthly salary")
    Spacer(Modifier.height(6.dp))
    Text(
        "The same every month, whatever the number of working days. Hours are still counted from your shifts.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))

    if (salaries.isEmpty()) {
        FormTextField(
            value = firstSalaryText,
            onValueChange = onFirstSalaryText,
            placeholder = "0.00",
            singleLine = true,
            keyboardType = KeyboardType.Decimal,
            prefix = currencySymbol,
            suffix = "/month"
        )
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(cs.background)
                .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            salaries.asReversed().forEachIndexed { i, rate ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${money(rate.monthlyCents)} a month",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (i == 0) cs.onSurface else cs.onSurfaceVariant
                        )
                        Text(
                            rate.from?.let { "From " + payPeriod.periodContaining(LocalDate.parse(it)).label() }
                                ?: "From the start",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    if (rate.from != null) {
                        CircleIconButton(
                            icon = AppIcons.Close, contentDescription = "Remove this salary change",
                            onClick = { onRemove(rate) },
                            diameter = 32.dp, iconSize = 14.dp, container = cs.surfaceVariant, elevation = 0.dp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SalaryChange(payPeriod, currencySymbol, onSetSalary)
    }
}

/**
 * Contract hours and the reference rate: what a salaried job needs only to
 * put a value on overtime. Kept in Advanced, out of the everyday path.
 */
@Composable
private fun SalaryReferenceFields(
    history: List<SalaryRate>,
    currencySymbol: String,
    referenceRateText: String,
    onReferenceRate: (String) -> Unit,
    contractHoursText: String,
    onContractHours: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val money = { cents: Long -> formatCents(cents, currencySymbol) }
    FieldLabel("Contracted hours per week", optional = true)
    Spacer(Modifier.height(10.dp))
    FormTextField(
        value = contractHoursText,
        onValueChange = onContractHours,
        placeholder = "e.g. 40",
        singleLine = true,
        keyboardType = KeyboardType.Decimal,
        suffix = "h/week"
    )

    Spacer(Modifier.height(20.dp))
    FieldLabel("Reference hourly rate", optional = true)
    Spacer(Modifier.height(6.dp))
    Text(
        "Only used to put a value on overtime. Your salary is never divided by the hours you worked.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))
    FormTextField(
        value = referenceRateText,
        onValueChange = onReferenceRate,
        placeholder = "0.00",
        singleLine = true,
        keyboardType = KeyboardType.Decimal,
        prefix = currencySymbol,
        suffix = "/hr"
    )
    val salaryNow = PaySetup(salaryHistory = history).currentSalaryCents
    val implied = salaryNow?.let {
        PaySetup(
            salaryHistory = listOf(SalaryRate(null, it)),
            contractHoursPerWeek = parseDecimal(contractHoursText) ?: 0.0
        ).contractHourlyRate()
    }
    if (implied != null) {
        val impliedText = "%.2f".format(java.util.Locale.ROOT, implied)
        if (parseDecimal(referenceRateText)?.let { "%.2f".format(java.util.Locale.ROOT, it) } != impliedText) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "From your contract: ${money(Math.round(implied * 100))}/hr (a year\u2019s salary over a year\u2019s contracted hours)",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextTab(text = "Use", selected = false, onClick = { onReferenceRate(impliedText) })
            }
        }
    }
}

/** "Change salary": a new amount, from a pay period on — or a correction of the starting salary. */
@Composable
private fun SalaryChange(
    payPeriod: PayPeriod,
    currencySymbol: String,
    onSetSalary: (from: LocalDate?, cents: Long) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open) {
        TextTab(text = "Change salary", selected = false, onClick = { open = true })
        return
    }
    var amount by rememberSaveable { mutableStateOf("") }
    var correction by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable { mutableStateOf(payPeriod.periodContaining(LocalDate.now()).start.toString()) }
    val period = payPeriod.periodContaining(LocalDate.parse(start))

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(14.dp)
    ) {
        FormTextField(
            value = amount,
            onValueChange = { amount = it },
            placeholder = "New monthly salary",
            singleLine = true,
            keyboardType = KeyboardType.Decimal,
            prefix = currencySymbol,
            suffix = "/month"
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill(text = "From a pay period", selected = !correction, onClick = { correction = false })
            FilterPill(text = "Correct the starting salary", selected = correction, onClick = { correction = true })
        }
        Spacer(Modifier.height(10.dp))
        if (!correction) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Starting with", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text(period.label(), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                }
                CircleIconButton(
                    icon = AppIcons.ChevronLeft, contentDescription = "Earlier pay period",
                    onClick = { start = payPeriod.previous(period).start.toString() },
                    diameter = 36.dp, iconSize = 16.dp, container = cs.surface, elevation = 0.dp
                )
                Spacer(Modifier.width(8.dp))
                CircleIconButton(
                    icon = AppIcons.ChevronRight, contentDescription = "Later pay period",
                    onClick = { start = payPeriod.next(period).start.toString() },
                    diameter = 36.dp, iconSize = 16.dp, container = cs.surface, elevation = 0.dp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Earlier pay periods keep the salary they had.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        } else {
            Text(
                "Replaces the salary you started with, for every month before your first change.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextTab(text = "Cancel", selected = false, onClick = { open = false })
            Spacer(Modifier.weight(1f))
            val cents = parseCents(amount)?.takeIf { it > 0L }
            PillButton(
                text = "Apply",
                onClick = {
                    if (cents != null) {
                        onSetSalary(if (correction) null else period.start, cents)
                        open = false
                        amount = ""
                    }
                },
                tinted = cents != null
            )
        }
    }
}

/**
 * Which hours are overtime and what they earn. Shared by both pay modes; the
 * default (marked shifts, paid) is how the app has always worked.
 */
@Composable
private fun OvertimeSection(
    isFixed: Boolean,
    source: OvertimeSource,
    onSource: (OvertimeSource) -> Unit,
    contractHoursText: String,
    onContractHours: (String) -> Unit,
    dailyLimitText: String,
    onDailyLimit: (String) -> Unit,
    multiplier: Double,
    onMultiplier: (Double) -> Unit,
    compensation: OvertimeCompensation,
    onCompensation: (OvertimeCompensation) -> Unit,
    hasReferenceRate: Boolean
) {
    val cs = MaterialTheme.colorScheme
    val warning = AppTheme.accents.warning
    FieldLabel("Overtime")
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OvertimeSource.entries.forEach { s ->
            FilterPill(text = s.label, selected = s == source, onClick = { onSource(s) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        when (source) {
            OvertimeSource.MARKED_SHIFTS -> "Shifts with Extra hours switched on count as overtime."
            OvertimeSource.WEEKLY_LIMIT -> "Hours past your contracted hours in a week count as overtime."
            OvertimeSource.DAILY_LIMIT -> "Hours past a daily limit count as overtime."
        },
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant
    )

    when (source) {
        OvertimeSource.WEEKLY_LIMIT -> {
            // A salaried job already asks for its contract hours above.
            if (!isFixed) {
                Spacer(Modifier.height(10.dp))
                FormTextField(
                    value = contractHoursText,
                    onValueChange = onContractHours,
                    placeholder = "Contracted hours, e.g. 40",
                    singleLine = true,
                    keyboardType = KeyboardType.Decimal,
                    suffix = "h/week"
                )
            } else if ((parseDecimal(contractHoursText) ?: 0.0) <= 0.0) {
                Spacer(Modifier.height(6.dp))
                Text("Set your contracted hours per week above.", style = MaterialTheme.typography.bodySmall, color = warning)
            }
        }
        OvertimeSource.DAILY_LIMIT -> {
            Spacer(Modifier.height(10.dp))
            FormTextField(
                value = dailyLimitText,
                onValueChange = onDailyLimit,
                placeholder = "Hours a day, e.g. 8",
                singleLine = true,
                keyboardType = KeyboardType.Decimal,
                suffix = "h/day"
            )
        }
        OvertimeSource.MARKED_SHIFTS -> Unit
    }

    if (source != OvertimeSource.MARKED_SHIFTS) {
        Spacer(Modifier.height(12.dp))
        Text("Overtime rate", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1.25, 1.5, 1.75, 2.0).forEach { m ->
                FilterPill(
                    text = "%s\u00d7".format(formatDecimalInput(m)),
                    selected = m == multiplier,
                    onClick = { onMultiplier(m) }
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Compensated as", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OvertimeCompensation.entries.forEach { c ->
            FilterPill(text = c.label, selected = c == compensation, onClick = { onCompensation(c) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        when (compensation) {
            OvertimeCompensation.PAID ->
                if (isFixed) "Paid on top of your salary at the reference rate (times a shift\u2019s own multiplier, if it has one)."
                else "Paid at the shift\u2019s own rate \u2014 nothing is added automatically."
            OvertimeCompensation.TIME_OFF -> "Not paid in money: the hours are banked as time off."
            OvertimeCompensation.TRACK_ONLY ->
                if (isFixed) "Counted and shown; nothing is added to your salary."
                else "Counted and shown; paid like any other hour."
        },
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant
    )
    if (isFixed && compensation == OvertimeCompensation.PAID && !hasReferenceRate) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Add a reference hourly rate above to put a value on overtime. Until then it shows as hours.",
            style = MaterialTheme.typography.bodySmall,
            color = warning
        )
    }
}

/** Picks a palette colour at random so two new profiles rarely look alike. */
private fun suggestedProfileColor(): Long =
    ShiftColors.Palette.random().toArgb().toLong()

// ══════════════════════════════════════════════════════════════
//  Copy shifts between profiles
//
//  Two steps in one sheet: pick the job to copy from, then tick exactly which
//  of its shifts to bring over. Everything starts ticked, so "copy the lot" is
//  still one tap while copying a single shift is two.
//
//  Shifts the target already holds are listed but locked off, so the reason a
//  row can't be picked is visible up front rather than enforced silently after
//  the fact.
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyShiftsSheet(
    target: JobProfile,
    sources: List<JobProfile>,
    /** The whole saved-shift library; the sheet slices it per profile itself. */
    allTemplates: List<Shift>,
    sheetState: AppSheetState,
    onConfirm: (List<Shift>) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var pending by remember { mutableStateOf<JobProfile?>(null) }

    val source = pending
    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = if (source == null) "Copy shifts" else "Choose shifts",
        caption = if (source == null)
            "Pick the job to copy from — into ${target.name}"
        else
            "From ${source.name} into ${target.name} · nothing is moved or removed"
    ) {
        if (source == null) {
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sources.forEach { profile ->
                    val count = allTemplates.count { it.jobId == profile.id }
                    CopySourceRow(
                        profile = profile,
                        count = count,
                        enabled = count > 0,
                        onClick = { pending = profile }
                    )
                }
            }
            return@AppSheet
        }

        val sourceShifts = remember(source.id, allTemplates) {
            allTemplates.filter { it.jobId == source.id }
        }
        // Matched on visible identity — the same rule the copy itself applies.
        val alreadyThere = remember(target.id, allTemplates) {
            allTemplates.filter { it.jobId == target.id }.map { it.identity() }.toSet()
        }
        val copyable = remember(sourceShifts, alreadyThere) {
            sourceShifts.indices.filterNot { sourceShifts[it].identity() in alreadyThere }.toSet()
        }

        // Indices, not identities: one job may legitimately hold two shifts that
        // look alike, and ticking one of them must not tick the other.
        var selected by remember(source.id) { mutableStateOf(copyable) }

        Spacer(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SheetLabel(
                if (copyable.isEmpty()) "Nothing left to copy"
                else "${selected.size} of ${copyable.size} selected"
            )
            Spacer(Modifier.weight(1f))
            if (copyable.isNotEmpty()) {
                val allPicked = selected.size == copyable.size
                TextTab(
                    text = if (allPicked) "Clear" else "Select all",
                    selected = false,
                    onClick = { selected = if (allPicked) emptySet() else copyable }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(
            Modifier
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sourceShifts.forEachIndexed { index, shift ->
                val locked = index !in copyable
                CopyShiftRow(
                    shift = shift,
                    selected = index in selected,
                    lockedReason = if (locked) "Already in ${target.name}" else null,
                    onToggle = {
                        selected = if (index in selected) selected - index else selected + index
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "${source.name} keeps everything it has — these are copies.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        val canCopy = selected.isNotEmpty()
        Surface(
            onClick = { if (canCopy) onConfirm(selected.sorted().map { sourceShifts[it] }) },
            enabled = canCopy,
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = if (canCopy) cs.primary else cs.surfaceVariant,
            contentColor = if (canCopy) cs.onPrimary else cs.onSurfaceVariant,
            shadowElevation = 0.dp
        ) {
            Row(
                Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    AppIcons.Copy, null, Modifier.size(18.dp),
                    tint = if (canCopy) cs.onPrimary else cs.onSurfaceVariant
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    when {
                        canCopy -> "Copy ${selected.size} shift${if (selected.size == 1) "" else "s"} here"
                        copyable.isEmpty() -> "${target.name} already has these"
                        else -> "Select shifts to copy"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .clickable { pending = null }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Choose a different job",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CopySourceRow(
    profile: JobProfile,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.45f

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.background)
            .border(1.dp, cs.outline, MaterialTheme.shapes.large)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShiftIcon(profile.name, profile.iconName, profile.colorValue, size = 38.dp, dimmed = !enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface.copy(alpha = alpha),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (count == 0) "Nothing to copy"
                else "$count saved shift${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 1
            )
        }
        if (enabled) {
            Icon(
                AppIcons.ChevronRight, null,
                tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * One pickable shift. Deliberately the same silhouette as the rows in the "Add
 * a shift" sheet — same tint, same dot, same metrics — with a tick in place of
 * the hours, so the list reads as shifts first and as a picker second.
 */
@Composable
private fun CopyShiftRow(
    shift: Shift,
    selected: Boolean,
    /** Non-null makes the row inert and says why. */
    lockedReason: String?,
    onToggle: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accent = rememberShiftAccent(shift.colorValue)
    val locked = lockedReason != null

    val detail = remember(shift, lockedReason) {
        lockedReason ?: buildString {
            append(
                when (shift.dayType) {
                    DayType.WORKED -> shiftTimeLabel(shift)
                    DayType.PAID_LEAVE -> "Paid leave"
                    DayType.UNPAID_LEAVE -> "Unpaid leave"
                }
            )
            val hours = PayEngine.payableHours(shift)
            if (hours > 0.0) append(" · ${hoursLabel(hours)}")
        }
    }

    val tint by animateColorAsState(
        targetValue = when {
            locked -> cs.surfaceVariant.copy(alpha = 0.5f)
            selected -> accent.copy(alpha = 0.20f)
            else -> accent.copy(alpha = 0.08f)
        },
        animationSpec = tween(140),
        label = "copyRowTint"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tint)
            .then(if (locked) Modifier else Modifier.clickable(onClick = onToggle))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShiftIcon(shift.name, shift.iconName, shift.colorValue, size = 36.dp, dimmed = locked)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                shift.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (locked) cs.onSurfaceVariant else cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        SelectionTick(selected = selected, locked = locked, accent = accent)
    }
}

@Composable
private fun SelectionTick(selected: Boolean, locked: Boolean, accent: Color) {
    val cs = MaterialTheme.colorScheme
    val ticked = selected && !locked
    val fill by animateColorAsState(
        targetValue = if (ticked) accent else Color.Transparent,
        animationSpec = tween(140),
        label = "tickFill"
    )

    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(fill)
            .then(
                if (ticked) Modifier
                else Modifier.border(
                    1.5.dp,
                    if (locked) cs.onSurfaceVariant.copy(alpha = 0.35f) else cs.outline,
                    CircleShape
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (ticked) {
            Icon(AppIcons.Check, "Selected", tint = cs.surface, modifier = Modifier.size(14.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Empty state for a brand-new profile's Shifts page
// ══════════════════════════════════════════════════════════════

@Composable
fun EmptyProfileShiftsState(
    profile: JobProfile,
    canCopy: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 44.dp).padding(bottom = 60.dp)
        ) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.NavShifts, null, tint = cs.primary, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "No shifts in ${profile.name}",
                style = MaterialTheme.typography.displayMedium,
                color = cs.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (canCopy)
                    "Tap New to set one up, or copy the shifts you already have in another job."
                else
                    "Tap New to set up your first shift. Save it once, then add it to any day with a single tap.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = cs.onSurfaceVariant
            )
            if (canCopy) {
                Spacer(Modifier.height(20.dp))
                PillButton(
                    text = "Copy from another job",
                    onClick = onCopy,
                    icon = AppIcons.Copy
                )
            }
        }
    }
}
