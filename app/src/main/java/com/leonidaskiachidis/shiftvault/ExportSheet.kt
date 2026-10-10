package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Export sheet — what to export, in which format, and where it goes.
 *
 *  One sheet serves Analytics and Settings alike. A report covers a chosen
 *  period rather than everything ever recorded: most people need "my last
 *  pay period" for a payslip check, not three years of history. Share hands
 *  the file straight to mail, chat or cloud apps; Save writes it to a folder.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

@Serializable
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    PDF("PDF", "pdf", "application/pdf"),
    EXCEL("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    /** An .ics file of the shifts themselves, for any calendar app. */
    CALENDAR("Calendar", "ics", "text/calendar")
}

@Serializable
enum class ExportAction { SAVE, SHARE }

/** A period a report can cover. A null [range] means everything recorded. */
data class ExportPeriod(val title: String, val range: DateRange?)

/**
 * An export waiting on the file picker. Plain strings so it can be saved
 * across the picker, which may outlive the activity.
 */
@Serializable
data class ExportRequest(
    val format: ExportFormat,
    val start: String? = null,
    val end: String? = null
) {
    val range: DateRange?
        get() = if (start != null && end != null) DateRange(LocalDate.parse(start), LocalDate.parse(end)) else null

    companion object {
        fun of(format: ExportFormat, range: DateRange?) =
            ExportRequest(format, range?.start?.toString(), range?.end?.toString())
    }
}

/**
 * The periods offered, most useful first. [shown] — the period open in
 * Analytics — leads the list when the sheet was opened from there. A calendar
 * export is about what's ahead, so it starts with the coming year's shifts.
 */
fun exportPeriodChoices(
    pay: PayPeriod,
    today: LocalDate,
    shown: ExportPeriod?,
    format: ExportFormat = ExportFormat.PDF
): List<ExportPeriod> {
    val thisPay = pay.periodContaining(today)
    val thisMonth = YearMonth.from(today)
    val year = Year.from(today)
    val upcoming = ExportPeriod("Upcoming shifts", DateRange(today, today.plusYears(1)))
        .takeIf { format == ExportFormat.CALENDAR }
    val choices = listOfNotNull(
        upcoming,
        shown,
        ExportPeriod("This pay period", thisPay),
        ExportPeriod("Last pay period", pay.previous(thisPay)),
        ExportPeriod("This month", DateRange(thisMonth.atDay(1), thisMonth.atEndOfMonth())),
        ExportPeriod("Last month", thisMonth.minusMonths(1).let { DateRange(it.atDay(1), it.atEndOfMonth()) }),
        ExportPeriod("This year", DateRange(year.atDay(1), year.atMonth(12).atEndOfMonth())),
        ExportPeriod("All time", null)
    )
    // A calendar-month pay period duplicates "This month"; keep the first of each.
    return choices.distinctBy { it.range }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    profile: JobProfile,
    initialFormat: ExportFormat,
    shownPeriod: ExportPeriod?,
    sheetState: AppSheetState,
    onExport: (ExportFormat, ExportPeriod, ExportAction) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var format by rememberSaveable { mutableStateOf(initialFormat) }
    val choices = remember(profile.payPeriod, shownPeriod, format) {
        exportPeriodChoices(profile.payPeriod, LocalDate.now(), shownPeriod, format)
    }
    // Picked by name, not position: the calendar format adds a period in
    // front, and switching format must not quietly move the selection.
    var selectedTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = choices.firstOrNull { it.title == selectedTitle } ?: choices.first()

    AppSheet(
        onDismiss = onDismiss,
        sheetState = sheetState,
        title = "Export",
        caption = profile.name
    ) {
        Spacer(Modifier.height(18.dp))
        SheetLabel("Format")
        Spacer(Modifier.height(10.dp))
        SegmentedControl(
            options = ExportFormat.entries.map { it to it.label },
            selected = format,
            onSelect = { format = it }
        )

        Spacer(Modifier.height(18.dp))
        SheetLabel("Period")
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            choices.forEach { period ->
                PeriodRow(period, selected = period == selected) { selectedTitle = period.title }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = { onExport(format, selected, ExportAction.SAVE) },
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                color = cs.surfaceVariant,
                contentColor = cs.onSurface
            ) {
                ActionLabel(AppIcons.Download, "Save")
            }
            Surface(
                onClick = { onExport(format, selected, ExportAction.SHARE) },
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                color = cs.primary,
                contentColor = cs.onPrimary
            ) {
                ActionLabel(AppIcons.SetShare, "Share")
            }
        }
    }
}

@Composable
private fun PeriodRow(period: ExportPeriod, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) cs.primary.copy(alpha = 0.10f) else cs.background)
            .border(1.dp, if (selected) cs.primary else cs.outline, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(period.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(
                period.range?.label() ?: "Everything recorded for this job",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) Icon(AppIcons.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ActionLabel(icon: androidx.compose.ui.graphics.painter.Painter, text: String) {
    Row(
        Modifier.padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
