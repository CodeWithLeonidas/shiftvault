package com.leonidaskiachidis.shiftvault

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/**
 * Every icon the app draws, resolved from res/drawable.
 *
 * Replaces androidx.compose.material:material-icons-extended (~1k vectors) with
 * the 35 we actually use. Each property is a @Composable getter, so call sites
 * read exactly like the old Icons.Default.X did:
 *
 *     Icon(AppIcons.Add, null, Modifier.size(18.dp), tint = cs.primary)
 */
object AppIcons {

    // ── Actions ──
    val Add: Painter          @Composable get() = painterResource(R.drawable.ic_add)
    val Close: Painter        @Composable get() = painterResource(R.drawable.ic_close)
    val Delete: Painter       @Composable get() = painterResource(R.drawable.ic_delete)
    val Edit: Painter         @Composable get() = painterResource(R.drawable.ic_edit)
    val ContentCopy: Painter  @Composable get() = painterResource(R.drawable.ic_content_copy)
    val MoreHoriz: Painter    @Composable get() = painterResource(R.drawable.ic_more_horiz)
    val Check: Painter        @Composable get() = painterResource(R.drawable.ic_check)
    val Search: Painter       @Composable get() = painterResource(R.drawable.ic_search)
    val Filters: Painter      @Composable get() = painterResource(R.drawable.ic_filters)

    // ── Navigation ──
    val ChevronLeft: Painter  @Composable get() = painterResource(R.drawable.ic_chevron_left)
    val ChevronRight: Painter @Composable get() = painterResource(R.drawable.ic_chevron_right)

    // ── Bottom bar ──
    val Home: Painter          @Composable get() = painterResource(R.drawable.ic_home)
    val CalendarMonth: Painter @Composable get() = painterResource(R.drawable.ic_calendar_month)
    val Shift: Painter         @Composable get() = painterResource(R.drawable.ic_shift)
    val BarChart: Painter      @Composable get() = painterResource(R.drawable.ic_bar_chart)
    val Settings: Painter      @Composable get() = painterResource(R.drawable.ic_settings)

    // ── Calendar / time ──
    val Today: Painter        @Composable get() = painterResource(R.drawable.ic_today)
    val Schedule: Painter     @Composable get() = painterResource(R.drawable.ic_schedule)
    val EventNote: Painter    @Composable get() = painterResource(R.drawable.ic_event_note)
    val DateRange: Painter    @Composable get() = painterResource(R.drawable.ic_date_range)
    val EditCalendar: Painter @Composable get() = painterResource(R.drawable.ic_edit_calendar)
    val Place: Painter        @Composable get() = painterResource(R.drawable.ic_place)

    // ── Settings ──
    val DarkMode: Painter     @Composable get() = painterResource(R.drawable.ic_dark_mode)
    val LightMode: Painter    @Composable get() = painterResource(R.drawable.ic_light_mode)
    val Palette: Painter      @Composable get() = painterResource(R.drawable.ic_palette)
    val Payments: Painter     @Composable get() = painterResource(R.drawable.ic_payments)
    val Save: Painter         @Composable get() = painterResource(R.drawable.ic_save)
    val Download: Painter     @Composable get() = painterResource(R.drawable.ic_download)
    val Upload: Painter       @Composable get() = painterResource(R.drawable.ic_upload)
    val PictureAsPdf: Painter @Composable get() = painterResource(R.drawable.ic_picture_as_pdf)
    val TableChart: Painter   @Composable get() = painterResource(R.drawable.ic_table_chart)
    val Share: Painter        @Composable get() = painterResource(R.drawable.ic_share)
    val Star: Painter         @Composable get() = painterResource(R.drawable.ic_star)
    val Email: Painter        @Composable get() = painterResource(R.drawable.ic_email)
    val PrivacyTip: Painter   @Composable get() = painterResource(R.drawable.ic_privacy_tip)
    val NewReleases: Painter  @Composable get() = painterResource(R.drawable.ic_new_releases)
    val Warning: Painter      @Composable get() = painterResource(R.drawable.ic_warning)

    // ── Stats ──
    val BeachAccess: Painter  @Composable get() = painterResource(R.drawable.ic_beach_access)
    val Weekend: Painter      @Composable get() = painterResource(R.drawable.ic_weekend)
}