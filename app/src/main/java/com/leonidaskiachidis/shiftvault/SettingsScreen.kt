package com.leonidaskiachidis.shiftvault

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  SettingsScreen — grouped rows in the warm-cream language.
 *
 *  Same features and same public signature. Presentation: big header with a
 *  soft fade into the scroll area, section labels, white cards holding
 *  divider-separated rows (tinted badge · title · subtitle · value · chevron).
 *  Week start and Theme are pickers laid out as grids inside their sheets.
 * ─────────────────────────────────────────────────────────────────────────────
 */

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import com.leonidaskiachidis.shiftvault.ui.theme.DarkThemeVariants
import com.leonidaskiachidis.shiftvault.ui.theme.LightThemeVariants
import com.leonidaskiachidis.shiftvault.ui.theme.ThemeVariantInfo
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** Which palette set is showing right now — resolved from [themeMode]. */
    isDarkTheme: Boolean,
    /** "system", "light" or "dark", as stored. */
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    themeVariant: String,
    onThemeVariantChange: (String) -> Unit,
    selectedCurrencyCode: String,
    onCurrencyChange: (String) -> Unit,
    startOfWeek: String,
    onStartOfWeekChange: (String) -> Unit,
    onExportPdf: () -> Unit,
    onExportExcel: () -> Unit,
    onBackClick: () -> Unit,
    onBackupExport: () -> Unit,
    onBackupImport: () -> Unit,
    /** True while a picked backup file is waiting for the user to confirm. */
    backupImportPending: Boolean,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val accents = AppTheme.accents
    val accent = cs.primary

    var showCurrency by rememberSaveable { mutableStateOf(false) }
    var showWeekStart by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }

    // Read from the installed package, so the About row can never drift from
    // the versionName in build.gradle again.
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    /**
     * Picking an option used to flip the `show…` flag straight to false, which
     * yanks the sheet out of the composition with no animation at all. Running
     * `sheetState.hide()` first lets it slide down the same way a swipe or a
     * scrim tap does, and only then is the sheet torn down.
     */
    fun dismissSheet(setVisible: (Boolean) -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) setVisible(false)
        }
    }

    val currencySymbol = remember(selectedCurrencyCode) {
        try { java.util.Currency.getInstance(selectedCurrencyCode).getSymbol(java.util.Locale.getDefault()) }
        catch (_: Exception) { selectedCurrencyCode }
    }

    val variants = if (isDarkTheme) DarkThemeVariants else LightThemeVariants
    val variantName = remember(variants, themeVariant) {
        variants.firstOrNull { it.key == themeVariant }?.displayName ?: "Coral"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding()
    ) {
        ScreenHeader(
            title = "Settings",
            subtitle = "Preferences, data, and about"
        )

        Spacer(Modifier.height(6.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp)
            ) {
                // ── General ──
                SectionLabel("General")
                Group {
                    SettingRow(
                        icon = AppIcons.Payments,
                        badgeColor = accents.morning,
                        title = "Currency",
                        subtitle = "Set your default currency",
                        value = "$currencySymbol $selectedCurrencyCode"
                    ) { showCurrency = true }
                    RowDivider()
                    SettingRow(
                        icon = AppIcons.CalendarMonth,
                        badgeColor = accent,
                        title = "Week start day",
                        subtitle = "First day of the week in your calendar",
                        value = startOfWeek
                    ) { showWeekStart = true }
                }

                // ── Appearance ──
                SectionLabel("Appearance")
                Group {
                    ThemeModeRow(
                        icon = if (isDarkTheme) AppIcons.DarkMode else AppIcons.LightMode,
                        badgeColor = accents.night,
                        mode = themeMode,
                        onChange = onThemeModeChange
                    )
                    RowDivider()
                    SettingRow(
                        icon = AppIcons.Palette,
                        badgeColor = accents.evening,
                        title = "Theme",
                        subtitle = "Choose your accent palette",
                        value = variantName
                    ) { showTheme = true }
                }

                // ── Data ──
                SectionLabel("Data")
                Group {
                    SettingRow(
                        icon = AppIcons.PictureAsPdf,
                        badgeColor = accents.negative,
                        title = "Export to PDF",
                        subtitle = "Pick a period, then save or share",
                        value = null,
                        onClick = onExportPdf
                    )
                    RowDivider()
                    SettingRow(
                        icon = AppIcons.TableChart,
                        badgeColor = cs.secondary,
                        title = "Export to Excel",
                        subtitle = "A formatted spreadsheet, for any period",
                        value = null,
                        onClick = onExportExcel
                    )
                    RowDivider()
                    SettingRow(
                        icon = AppIcons.Save,
                        badgeColor = accents.positive,
                        title = "Back up & restore",
                        subtitle = "Export your data or restore it",
                        value = null
                    ) { showBackup = true }
                }

                // ── Other ──
                SectionLabel("Other")
                Group {
                    SettingRow(
                        icon = AppIcons.Star,
                        badgeColor = accents.night,
                        title = "Rate the app",
                        subtitle = "Share your feedback",
                        value = null
                    ) {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.leonidaskiachidis.shiftvault")
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }
                    RowDivider()
                    SettingRow(
                        icon = AppIcons.Share,
                        badgeColor = cs.tertiary,
                        title = "Share with friends",
                        subtitle = "Tell others about ShiftVault",
                        value = null
                    ) {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Check out ShiftVault!")
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "I've been using ShiftVault to manage my work shifts — check it out:\n\nhttps://play.google.com/store/apps/details?id=com.leonidaskiachidis.shiftvault"
                            )
                        }
                        context.startActivity(Intent.createChooser(i, "Share ShiftVault"))
                    }
                }

                // ── About ──
                SectionLabel("About")
                Group {
                    SettingRow(
                        icon = AppIcons.NewReleases,
                        badgeColor = accents.evening,
                        title = "What's new",
                        subtitle = "Latest updates and features",
                        value = null
                    ) { showWhatsNew = true }
                    RowDivider()
                    SettingRow(
                        icon = AppIcons.PrivacyTip,
                        badgeColor = accents.morning,
                        title = "Privacy policy",
                        subtitle = "How we handle your data",
                        value = null
                    ) { showPrivacy = true }
                    RowDivider()
                    SettingRow(
                        icon = AppIcons.Email,
                        badgeColor = accent,
                        title = "Contact developer",
                        subtitle = "Send feedback or report an issue",
                        value = null
                    ) {
                        val email = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:kiachidis.leonidas@gmail.com")
                            putExtra(Intent.EXTRA_SUBJECT, "ShiftVault Feedback")
                        }
                        try { context.startActivity(Intent.createChooser(email, "Send Email")) } catch (_: Exception) {}
                    }
                    RowDivider()
                    SettingRow(
                        icon = AppIcons.EditCalendar,
                        badgeColor = cs.onSurfaceVariant,
                        title = "About Shift Vault",
                        subtitle = "Version $versionName",
                        value = null,
                        showChevron = false
                    ) { }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Developed by Leonidas Kiachidis",
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Soft fade where the header meets the scroll area
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(cs.background, cs.background.copy(alpha = 0f))
                        )
                    )
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Currency sheet
    // ══════════════════════════════════════════════════════════
    if (showCurrency) {
        val all = remember { CurrencyHelper.getAllCurrencies() }
        var query by remember { mutableStateOf("") }
        val filtered = remember(query, all) {
            if (query.isBlank()) all
            else all.filter { it.name.startsWith(query, true) || it.code.startsWith(query, true) }
        }
        ModalBottomSheet(
            onDismissRequest = { showCurrency = false },
            sheetState = sheetState,
            containerColor = cs.surface,
            contentColor = cs.onSurface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            dragHandle = { SettingsHandle() }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 16.dp)
            ) {
                SheetHeading("Currency", "Used for rates and earnings across the app.")
                Spacer(Modifier.height(16.dp))
                SheetSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search currency"
                )
                Spacer(Modifier.height(14.dp))
                LazyColumn(
                    Modifier.weight(1f).nestedScroll(SheetScrollLock),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.code }) { c ->
                        val selected = c.code == selectedCurrencyCode
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(
                                    if (selected) accent.copy(alpha = 0.12f) else cs.background
                                )
                                .border(
                                    1.dp,
                                    if (selected) accent else cs.outline,
                                    MaterialTheme.shapes.large
                                )
                                .clickable {
                                    onCurrencyChange(c.code)
                                    dismissSheet { showCurrency = it }
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                c.symbol,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selected) accent else cs.onSurface,
                                modifier = Modifier.widthIn(min = 36.dp), maxLines = 1
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = cs.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    c.code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            if (selected) {
                                Icon(AppIcons.Check, null, tint = accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Week start sheet — grid
    // ══════════════════════════════════════════════════════════
    if (showWeekStart) {
        ModalBottomSheet(
            onDismissRequest = { showWeekStart = false },
            sheetState = sheetState,
            containerColor = cs.surface,
            contentColor = cs.onSurface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            dragHandle = { SettingsHandle() }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 36.dp)
            ) {
                SheetHeading("Week start day", "Choose the first day of the week in your calendar.")
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(
                        "Monday" to listOf("M", "T", "W", "T", "F", "S", "S"),
                        "Sunday" to listOf("S", "M", "T", "W", "T", "F", "S")
                    ).forEach { (day, strip) ->
                        WeekStartCard(
                            day = day,
                            strip = strip,
                            selected = startOfWeek == day,
                            modifier = Modifier.weight(1f)
                        ) {
                            onStartOfWeekChange(day)
                            dismissSheet { showWeekStart = it }
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Theme sheet — grid
    // ══════════════════════════════════════════════════════════
    if (showTheme) {
        ModalBottomSheet(
            onDismissRequest = { showTheme = false },
            sheetState = sheetState,
            containerColor = cs.surface,
            contentColor = cs.onSurface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            dragHandle = { SettingsHandle() }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 36.dp)
            ) {
                SheetHeading(
                    "Theme",
                    "Accent palette for the ${if (isDarkTheme) "dark" else "light"} theme."
                )
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .nestedScroll(SheetScrollLock)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    variants.chunked(2).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { v ->
                                ThemeCard(
                                    variant = v,
                                    selected = v.key == themeVariant,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    onThemeVariantChange(v.key)
                                    dismissSheet { showTheme = it }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Backup sheet
    // ══════════════════════════════════════════════════════════
    if (showBackup) {
        ModalBottomSheet(
            onDismissRequest = { showBackup = false },
            sheetState = sheetState,
            containerColor = cs.surface,
            contentColor = cs.onSurface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            dragHandle = { SettingsHandle() }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 36.dp)
            ) {
                SheetHeading(
                    "Back up & restore",
                    "Save all your shifts and saved shifts to a file, or restore from a previous backup."
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { dismissSheet { showBackup = it }; onBackupExport() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
                ) {
                    Icon(AppIcons.Upload, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Export backup", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = { dismissSheet { showBackup = it }; onBackupImport() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                    color = cs.surfaceVariant,
                    contentColor = cs.onSurface
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(AppIcons.Download, null, Modifier.size(19.dp), tint = cs.onSurface)
                        Spacer(Modifier.width(9.dp))
                        Text("Import backup", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Dialogs
    // ══════════════════════════════════════════════════════════
    if (backupImportPending) {
        AlertDialog(
            onDismissRequest = onDismissImport,
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            icon = { Icon(AppIcons.Warning, null, tint = accents.night) },
            title = {
                Text(
                    "Restore from backup?",
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface
                )
            },
            text = {
                Text(
                    "This replaces all your current shifts and saved shifts with the backup data. It can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmImport,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Restore", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = onDismissImport) { Text("Cancel") } }
        )
    }

    if (showWhatsNew) {
        AlertDialog(
            onDismissRequest = { showWhatsNew = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            title = {
                Text("What's new", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    WhatsNew("Refreshed design", "A warmer, softer look across every screen.", accent)
                    WhatsNew("New analytics", "Period deltas, hour distribution and insights.", accent)
                    WhatsNew("Eight themes", "Pick a palette for light and dark separately.", accent)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showWhatsNew = false },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Got it", style = MaterialTheme.typography.labelLarge) }
            }
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = cs.surface,
            title = {
                Text("Privacy policy", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "ShiftVault stores all of your data locally on your device. Nothing is uploaded to any server, and no personal information is collected or shared. Backups you create are saved only where you choose. You stay in full control of your data at all times.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPrivacy = false },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Close", style = MaterialTheme.typography.labelLarge) }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
//  Building blocks
// ══════════════════════════════════════════════════════════════

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(
            start = AppSpace.screenH + 4.dp,
            end = AppSpace.screenH,
            top = 4.dp,
            bottom = 10.dp
        )
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpace.screenH),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(vertical = 4.dp),
        content = content
    )
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun RowDivider() =
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 76.dp, end = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )

@Composable
private fun Badge(icon: Painter, color: Color) =
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    }

@Composable
private fun ValuePill(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(CircleShape)
            .background(cs.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun SettingRow(
    icon: Painter,
    badgeColor: Color,
    title: String,
    subtitle: String?,
    value: String?,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Badge(icon, badgeColor)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            ValuePill(value)
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                AppIcons.ChevronRight, null,
                tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)
            )
        }
    }
}

private val ThemeModes = listOf(
    "system" to "System",
    "light" to "Light",
    "dark" to "Dark"
)

/**
 * Light / dark / follow the phone. The old on-off switch could only ever store
 * "light" or "dark", so once it was touched there was no way back to following
 * the system setting.
 */
@Composable
private fun ThemeModeRow(
    icon: Painter,
    badgeColor: Color,
    mode: String,
    onChange: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(icon, badgeColor)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Theme mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when (mode) {
                        "light" -> "Always light"
                        "dark" -> "Always dark"
                        else -> "Follows your phone's setting"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // Same segmented control as the day-type picker in the shift form.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 58.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ThemeModes.forEach { (key, label) ->
                // Anything unrecognised is treated as "system", matching MainActivity.
                val selected = key == mode || (key == "system" && mode !in listOf("light", "dark"))
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (selected) cs.primary else Color.Transparent)
                        .clickable { if (!selected) onChange(key) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Picker cards
// ══════════════════════════════════════════════════════════════

@Composable
private fun WeekStartCard(
    day: String,
    strip: List<String>,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else cs.background)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) cs.primary else cs.outline,
                MaterialTheme.shapes.large
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                day,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) cs.primary else cs.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (selected) {
                Icon(AppIcons.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            strip.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == 0 && selected) cs.primary else cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    variant: ThemeVariantInfo,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else cs.background)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) cs.primary else cs.outline,
                MaterialTheme.shapes.large
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(14.dp)
    ) {
        // Palette preview: background block with two swatches on it
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(MaterialTheme.shapes.small)
                .background(variant.previewBackground)
        ) {
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(variant.previewPrimary))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(34.dp, 14.dp)
                        .clip(CircleShape)
                        .background(variant.previewSurface)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                variant.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) cs.primary else cs.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (selected) {
                Icon(AppIcons.Check, null, tint = cs.primary, modifier = Modifier.size(17.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Sheet pieces
// ══════════════════════════════════════════════════════════════

@Composable
private fun SettingsHandle() {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(42.dp, 5.dp).clip(CircleShape).background(cs.outline))
    }
}

@Composable
private fun SheetHeading(title: String, caption: String?) {
    val cs = MaterialTheme.colorScheme
    Text(title, style = MaterialTheme.typography.displayMedium, color = cs.onSurface)
    if (caption != null) {
        Spacer(Modifier.height(5.dp))
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun SheetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(cs.background)
            .border(1.dp, cs.outline, CircleShape)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(AppIcons.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(13.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Icon(
                AppIcons.Close, "Clear",
                tint = cs.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onValueChange("") }
            )
        }
    }
}

@Composable
private fun WhatsNew(title: String, body: String, accent: Color) {
    val cs = MaterialTheme.colorScheme
    Row {
        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}