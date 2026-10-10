package com.leonidaskiachidis.shiftvault

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/**
 * Every icon the app draws, resolved from res/drawable. Each property is a
 * @Composable getter, so call sites read like the old Icons.Default.X did:
 *
 *     Icon(AppIcons.Add, null, Modifier.size(18.dp), tint = cs.primary)
 *
 * Two families:
 *  - UI glyphs (ic_ui_*) — single-colour line marks: chevrons, close, check.
 *    Tinted the ordinary way.
 *  - Tone icons — the three-tone illustrations. Drawn through [TonePainter],
 *    which repaints their fixed colours from the tint (see ToneIcons.kt).
 */
object AppIcons {

    // ── UI glyphs ──
    val Add: Painter          @Composable get() = painterResource(R.drawable.ic_ui_add)
    val Close: Painter        @Composable get() = painterResource(R.drawable.ic_ui_close)
    val Check: Painter        @Composable get() = painterResource(R.drawable.ic_ui_check)
    val Search: Painter       @Composable get() = painterResource(R.drawable.ic_ui_search)
    val Filters: Painter      @Composable get() = painterResource(R.drawable.ic_ui_filter)
    val ChevronLeft: Painter  @Composable get() = painterResource(R.drawable.ic_ui_chevron_left)
    val ChevronRight: Painter @Composable get() = painterResource(R.drawable.ic_ui_chevron_right)
    val ChevronDown: Painter  @Composable get() = painterResource(R.drawable.ic_ui_chevron_down)
    val Download: Painter     @Composable get() = painterResource(R.drawable.ic_ui_download)
    val Upload: Painter       @Composable get() = painterResource(R.drawable.ic_ui_upload)
    val Warning: Painter      @Composable get() = painterResource(R.drawable.ic_ui_warning)

    // ── Bottom bar ──
    val NavHome: Painter      @Composable get() = rememberTonePainter(R.drawable.ic_nav_home)
    val NavShifts: Painter    @Composable get() = rememberTonePainter(R.drawable.ic_nav_shifts)
    val NavAnalytics: Painter @Composable get() = rememberTonePainter(R.drawable.ic_nav_analytics)
    val NavSettings: Painter  @Composable get() = rememberTonePainter(R.drawable.ic_nav_settings)

    // ── Calendar actions ──
    val Copy: Painter         @Composable get() = rememberTonePainter(R.drawable.ic_action_copy)
    val Delete: Painter       @Composable get() = rememberTonePainter(R.drawable.ic_action_delete)
    val EditCalendar: Painter @Composable get() = rememberTonePainter(R.drawable.ic_action_edit_calendar)
    val Rotation: Painter     @Composable get() = rememberTonePainter(R.drawable.ic_action_rotation)
    val Today: Painter        @Composable get() = rememberTonePainter(R.drawable.ic_action_today)
    val Edit: Painter         @Composable get() = rememberTonePainter(R.drawable.ic_edit)

    // ── Calendar, time, money ──
    val Calendar: Painter     @Composable get() = rememberTonePainter(R.drawable.ic_calendar)
    val Clock: Painter        @Composable get() = rememberTonePainter(R.drawable.ic_clock)
    val Break: Painter        @Composable get() = rememberTonePainter(R.drawable.ic_break)
    val Location: Painter     @Composable get() = rememberTonePainter(R.drawable.ic_location)
    val Wallet: Painter       @Composable get() = rememberTonePainter(R.drawable.ic_wallet)
    val Statistics: Painter   @Composable get() = rememberTonePainter(R.drawable.ic_statistics)
    val Vacation: Painter     @Composable get() = rememberTonePainter(R.drawable.ic_vacation)
    val TimeOff: Painter      @Composable get() = rememberTonePainter(R.drawable.ic_time_off)

    // ── Settings ──
    val SetAbout: Painter          @Composable get() = rememberTonePainter(R.drawable.ic_set_about)
    val SetBackup: Painter         @Composable get() = rememberTonePainter(R.drawable.ic_set_backup)
    val SetContact: Painter        @Composable get() = rememberTonePainter(R.drawable.ic_set_contact)
    val SetCurrency: Painter       @Composable get() = rememberTonePainter(R.drawable.ic_set_currency)
    val SetDailyLog: Painter       @Composable get() = rememberTonePainter(R.drawable.ic_set_daily_log)
    val SetExportExcel: Painter    @Composable get() = rememberTonePainter(R.drawable.ic_set_export_excel)
    val SetExportPdf: Painter      @Composable get() = rememberTonePainter(R.drawable.ic_set_export_pdf)
    val SetPrivacy: Painter        @Composable get() = rememberTonePainter(R.drawable.ic_set_privacy)
    val SetRate: Painter           @Composable get() = rememberTonePainter(R.drawable.ic_set_rate)
    val SetReminderShift: Painter  @Composable get() = rememberTonePainter(R.drawable.ic_set_reminder_shift)
    val SetReminderTime: Painter   @Composable get() = rememberTonePainter(R.drawable.ic_set_reminder_time)
    val SetShare: Painter          @Composable get() = rememberTonePainter(R.drawable.ic_set_share)
    val SetTheme: Painter          @Composable get() = rememberTonePainter(R.drawable.ic_set_theme)
    val SetThemeMode: Painter      @Composable get() = rememberTonePainter(R.drawable.ic_set_theme_mode)
    val SetWeekStart: Painter      @Composable get() = rememberTonePainter(R.drawable.ic_set_week_start)
    val SetWhatsNew: Painter       @Composable get() = rememberTonePainter(R.drawable.ic_set_whats_new)
}
