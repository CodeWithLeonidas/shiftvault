package com.leonidaskiachidis.shiftvault

import android.os.Bundle
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.leonidaskiachidis.shiftvault.ui.theme.AppTheme
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Icon
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity() {

    /**
     * A launcher shortcut waiting to be acted on (see res/xml/shortcuts.xml).
     * Taken from the launching intent only on a fresh start, so rotating the
     * screen doesn't replay it; the UI clears it once handled.
     */
    private val pendingShortcut = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingShortcut.value = intent.action
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingShortcut.value = intent?.action
        enableEdgeToEdge()
        setContent {
            // 1. Create a state variable for the theme
            val context = androidx.compose.ui.platform.LocalContext.current
            val shiftManager = remember { ShiftManager(context) }
            val scope = rememberCoroutineScope()

            val systemDark = isSystemInDarkTheme()

            // Load saved theme preference
            val savedThemeMode by shiftManager.getThemeMode.collectAsState(initial = "system")
            val isDarkTheme = remember(savedThemeMode, systemDark) {
                when (savedThemeMode) {
                    "dark" -> true
                    "light" -> false
                    else -> systemDark  // "system" or any other value
                }
            }

            // This loads the currency from your persistent storage
            val selectedCurrencyCode by shiftManager.getCurrency.collectAsState(initial = "EUR")
            val startOfWeek by shiftManager.getStartOfWeek.collectAsState(initial = "Monday")
            val lightThemeVariant by shiftManager.getLightThemeVariant.collectAsState(initial = "default")
            val darkThemeVariant by shiftManager.getDarkThemeVariant.collectAsState(initial = "default")
            val reminderSettings by shiftManager.getReminderSettings.collectAsState(initial = ReminderSettings())
            val restWarnings by shiftManager.getRestWarnings.collectAsState(initial = true)
            val lastBackupAt by shiftManager.getLastBackupAt.collectAsState(initial = null)
            val ledgerData by shiftManager.getLedger.collectAsState(initial = LedgerData.Empty)
            val themeVariant = if (isDarkTheme) darkThemeVariant else lightThemeVariant

            // enableEdgeToEdge() in onCreate() reads the *system* dark-mode flag,
            // which is the wrong source whenever the user has overridden the
            // theme in Settings — dark app theme on a light system left the
            // status-bar icons dark on a dark background, i.e. invisible.
            // Re-applying it from our own flag keeps the bars in step with the
            // theme the user actually sees, navigation bar included.
            LaunchedEffect(isDarkTheme) {
                val transparent = android.graphics.Color.TRANSPARENT
                if (isDarkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(transparent),
                        navigationBarStyle = SystemBarStyle.dark(transparent)
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(transparent, transparent),
                        navigationBarStyle = SystemBarStyle.light(transparent, transparent)
                    )
                }
            }

            // 2. Pass this state to your Theme wrapper
            ShiftVaultTheme(darkTheme = isDarkTheme, themeVariant = themeVariant) {

                val navController = rememberNavController()

                    // ── Shared state & file launchers (used by Analytics and Settings) ──
                    var savedShifts by remember { mutableStateOf(mapOf<java.time.LocalDate, List<Shift>>()) }
                    // Collected once, at app start, and kept alive for the whole
                    // session: every tab then renders fully populated on its very
                    // first frame instead of flashing an empty state.
                    var savedTemplates by remember { mutableStateOf<List<Shift>>(emptyList()) }
                    // False until storage has answered once: the shortcut that
                    // opens today must see today's real shifts, not the empty
                    // map the screen starts from.
                    var shiftsLoaded by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        // Opens the database and runs the one-time import from
                        // the old DataStore JSON up front, instead of on the
                        // first screen that happens to read shifts.
                        launch {
                            shiftManager.prepareStorage()
                            // Aim any pending reminders at the data as it is now.
                            Reminders.createChannels(context)
                            Reminders.requestReschedule(context)
                        }
                        launch {
                            shiftManager.getShifts.collect { loadedShifts ->
                                savedShifts = loadedShifts
                                shiftsLoaded = true
                            }
                        }
                        launch {
                            shiftManager.getTemplates.collect { loadedTemplates ->
                                savedTemplates = loadedTemplates
                            }
                        }
                    }

                    // ── Job Profiles ──
                    //
                    // Collected once, here, and handed to every screen. This is
                    // the single source of truth the whole app reads its
                    // selection from: Home, Shifts and Analytics can never show
                    // different jobs because there is only ever one value.
                    val profileState = rememberProfileState(shiftManager)
                    val activeProfile = profileState.active

                    // The active job's slice of each store. Screens that only
                    // read (Analytics, exports) get this; screens that write
                    // (Home) get the full map and narrow it themselves.
                    val profileShifts = remember(savedShifts, activeProfile.id) {
                        ProfileScope.shifts(savedShifts, activeProfile.id)
                    }
                    // The Wallet shows one job's ledger, like every other screen.
                    val profileLedger = remember(ledgerData, activeProfile.id) {
                        ledgerData.forJob(activeProfile.id)
                    }
                    val profileTemplates = remember(savedTemplates, activeProfile.id) {
                        savedTemplates.filter { it.jobId == activeProfile.id }
                    }
                    val profileSummaries =
                        remember(profileState.profiles, savedTemplates, savedShifts) {
                            buildProfileSummaries(profileState.profiles, savedTemplates, savedShifts)
                        }

                    // The profile sheets live here rather than on each screen,
                    // so switching, creating and copying behave identically
                    // wherever they were opened from.
                    var showProfileSheet by rememberSaveable { mutableStateOf(false) }
                    var showCopySheet by rememberSaveable { mutableStateOf(false) }
                    var editingProfile by rememberSaveable(stateSaver = jsonSaver<JobProfile?>()) {
                        mutableStateOf(null)
                    }
                    var showProfileEditor by rememberSaveable { mutableStateOf(false) }
                    val profileSheetState = rememberAppSheetState()
                    val profileEditorState = rememberAppSheetState()
                    val copySheetState = rememberAppSheetState()

                    // The picked backup file, held by URI rather than by its
                    // contents: it survives rotation this way (a whole backup
                    // is far too big for saved state) and is read again on
                    // confirm.
                    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }

                    // ── In-app review ──
                    //
                    // Asked at most once (see ReviewPolicy), and only straight
                    // after something worked: a shift was logged, or a report
                    // was saved or shared.
                    val workedShiftCount = remember(savedShifts) {
                        savedShifts.values.sumOf { day -> day.count { PayEngine.isWorked(it) } }
                    }
                    fun maybeAskForReview() {
                        scope.launch {
                            ReviewPrompt.maybeAsk(this@MainActivity, shiftManager, workedShiftCount)
                        }
                    }
                    var lastWorkedCount by remember { mutableIntStateOf(-1) }
                    LaunchedEffect(workedShiftCount, shiftsLoaded) {
                        if (!shiftsLoaded) return@LaunchedEffect
                        // The first load is not something the user just did.
                        if (lastWorkedCount in 0 until workedShiftCount) maybeAskForReview()
                        lastWorkedCount = workedShiftCount
                    }

                    // ── Export sheet ──
                    var showExportSheet by rememberSaveable { mutableStateOf(false) }
                    var exportInitialFormat by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
                    // The Analytics period offered first, kept as plain values so it saves.
                    var exportShownStart by rememberSaveable { mutableStateOf<String?>(null) }
                    var exportShownEnd by rememberSaveable { mutableStateOf<String?>(null) }
                    val exportSheetState = rememberAppSheetState()
                    // What Save is writing once the picker returns; may outlive the activity.
                    var pendingSave by rememberSaveable(stateSaver = jsonSaver<ExportRequest?>()) {
                        mutableStateOf(null)
                    }

                    val showUnreadableNotice by shiftManager.hasUnreadableDataNotice
                        .collectAsState(initial = false)

                    // Which job the report is for, and in what currency — the
                    // old exports printed bare numbers with no symbol and no
                    // indication of whose shifts they were.
                    val reportMeta = remember(activeProfile, selectedCurrencyCode) {
                        ReportMeta(
                            profileName = activeProfile.name,
                            profileColorValue = activeProfile.colorValue,
                            currencyCode = selectedCurrencyCode
                        )
                    }

                    /**
                     * Both reports now lay themselves out properly, and POI in
                     * particular takes a beat to start up, so generation runs
                     * off the main thread — on the main thread it was an ANR
                     * waiting for a big enough dataset.
                     */
                    fun runExport(
                        uri: android.net.Uri?,
                        label: String,
                        write: (java.io.OutputStream) -> Unit
                    ) {
                        if (uri == null) return
                        scope.launch {
                            val failure = withContext(Dispatchers.IO) {
                                runCatching {
                                    val out = context.contentResolver.openOutputStream(uri, "wt")
                                        ?: error("Could not open the file for writing")
                                    out.use(write)
                                }.exceptionOrNull()
                            }
                            Toast.makeText(
                                context,
                                if (failure == null) "$label saved"
                                else "Couldn't save $label: ${failure.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            if (failure == null) maybeAskForReview()
                        }
                    }

                    /** Writes the report [request] describes: only its period's shifts, labelled with it. */
                    fun writeReport(request: ExportRequest, out: java.io.OutputStream) {
                        val range = request.range
                        val shifts = range?.slice(profileShifts) ?: profileShifts
                        // A salary is paid per period, not per shift: the report's
                        // totals then come from the same calculation as the app.
                        val payRange = range ?: shifts.keys.takeIf { it.isNotEmpty() }?.let { DateRange(it.min(), it.max()) }
                        val pay = if (activeProfile.pay.isFixed && payRange != null)
                            Compensation.forRange(activeProfile, profileShifts, payRange, weekStartDay(startOfWeek))
                        else null
                        val meta = reportMeta.copy(periodLabel = range?.label(), pay = pay)
                        when (request.format) {
                            ExportFormat.PDF -> ExportHelper.exportShiftsToPdf(context, shifts, out, meta)
                            ExportFormat.EXCEL -> ExportHelper.exportShiftsToExcel(shifts, out, meta)
                            ExportFormat.CALENDAR -> CalendarExport.write(shifts, activeProfile.name, out)
                        }
                    }

                    fun onSavePicked(uri: android.net.Uri?) {
                        val request = pendingSave ?: return
                        pendingSave = null
                        val label = when (request.format) {
                            ExportFormat.PDF -> "PDF"
                            ExportFormat.EXCEL -> "Excel file"
                            ExportFormat.CALENDAR -> "Calendar file"
                        }
                        runExport(uri, label) { out -> writeReport(request, out) }
                    }

                    val pdfExportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument(ExportFormat.PDF.mimeType)
                    ) { uri -> onSavePicked(uri) }

                    val excelExportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument(ExportFormat.EXCEL.mimeType)
                    ) { uri -> onSavePicked(uri) }

                    val calendarExportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument(ExportFormat.CALENDAR.mimeType)
                    ) { uri -> onSavePicked(uri) }

                    /**
                     * Share: the report is written to the app's cache and handed to
                     * the system share sheet — mail, chat, Drive — through the
                     * FileProvider, so no storage permission is involved.
                     */
                    fun shareReport(request: ExportRequest) {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                runCatching {
                                    val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                    // Yesterday's shared files have done their job.
                                    val dayAgo = System.currentTimeMillis() - 86_400_000L
                                    dir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
                                    val name = ExportHelper.suggestFileName(
                                        activeProfile.name, request.format.extension, request.range
                                    )
                                    java.io.File(dir, name).also { f -> f.outputStream().use { writeReport(request, it) } }
                                }.getOrNull()
                            }
                            if (file == null) {
                                Toast.makeText(context, "Couldn't create the report", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = request.format.mimeType
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(
                                    android.content.Intent.EXTRA_SUBJECT,
                                    "Earnings report · ${activeProfile.name}" +
                                        (request.range?.let { " · ${it.label()}" } ?: "")
                                )
                                clipData = android.content.ClipData.newRawUri(file.name, uri)
                                // A shared calendar file is opened, not attached:
                                // let calendar apps offer to import it.
                                if (request.format == ExportFormat.CALENDAR) {
                                    putExtra(android.content.Intent.EXTRA_TEXT, "Shifts for ${activeProfile.name}")
                                }
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share report"))
                            maybeAskForReview()
                        }
                    }

                    fun openExportSheet(format: ExportFormat, shown: ExportPeriod?) {
                        exportInitialFormat = format
                        exportShownStart = shown?.range?.start?.toString()
                        exportShownEnd = shown?.range?.end?.toString()
                        showExportSheet = true
                    }

                    /**
                     * The backup is gathered here, once the file exists, not
                     * before the picker opens. Holding it in memory across the
                     * picker lost it whenever Android recreated the activity or
                     * killed the process meanwhile, leaving a 0-byte "backup"
                     * and no error.
                     */
                    val exportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument("application/json")
                    ) { uri ->
                        if (uri == null) return@rememberLauncherForActivityResult
                        scope.launch {
                            val success = try {
                                val json = BackupHelper.serialize(shiftManager.getAllDataForBackup())
                                withContext(Dispatchers.IO) { BackupHelper.writeToUri(context, uri, json) }
                            } catch (_: Exception) { false }
                            if (success) shiftManager.saveLastBackupAt(System.currentTimeMillis())
                            Toast.makeText(
                                context,
                                if (success) "Backup saved successfully!" else "Failed to save backup",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    /** Reads and checks a backup file; null (with a toast) if it can't be used. */
                    suspend fun loadBackup(uri: android.net.Uri): BackupData? {
                        val jsonString = withContext(Dispatchers.IO) { BackupHelper.readFromUri(context, uri) }
                        if (jsonString == null) {
                            Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show()
                            return null
                        }
                        val backupData = withContext(Dispatchers.Default) { BackupHelper.deserialize(jsonString) }
                        if (backupData == null) {
                            Toast.makeText(context, "Invalid backup file format", Toast.LENGTH_SHORT).show()
                            return null
                        }
                        if (backupData.version > 1) {
                            Toast.makeText(context, "Incompatible backup version (v${backupData.version}). Please update the app.", Toast.LENGTH_LONG).show()
                            return null
                        }
                        return backupData
                    }

                    val importLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri == null) return@rememberLauncherForActivityResult
                        scope.launch {
                            if (loadBackup(uri) != null) pendingImportUri = uri.toString()
                        }
                    }

                    // ── Launcher shortcuts ──
                    var homeAddRequest by remember { mutableStateOf<java.time.LocalDate?>(null) }
                    fun openTab(route: String) {
                        popCustomShift(navController)
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    val shortcut = pendingShortcut.value
                    LaunchedEffect(shortcut) {
                        when (shortcut) {
                            Shortcuts.ADD_SHIFT -> {
                                openTab(Screen.Vault.route)
                                homeAddRequest = java.time.LocalDate.now()
                            }
                            Shortcuts.WALLET -> openTab(Screen.Wallet.route)
                            Shortcuts.EXPORT -> openExportSheet(ExportFormat.PDF, null)
                            else -> return@LaunchedEffect
                        }
                        pendingShortcut.value = null
                    }

                    // We need to track the current route to highlight the bottom bar icons
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val isCustomShiftVisible = currentRoute == Screen.CustomShift.route

                    // The entry's arguments are gone the instant it is popped, so
                    // they are latched here. The overlay keeps rendering the same
                    // content while it slides away instead of blanking out mid
                    // animation — and it stops recomposing the form on every pop.
                    var customShiftArgs by remember { mutableStateOf<CustomShiftArgs?>(null) }
                    if (isCustomShiftVisible) {
                        val entry = navBackStackEntry
                        val dateString = entry?.arguments?.getString("date")
                        val rawTemplate = entry?.arguments?.getInt("templateIndex") ?: -1
                        val rawEntry = entry?.arguments?.getInt("entryIndex") ?: -1
                        val next = CustomShiftArgs(
                            date = if (dateString == null || dateString == "none") null
                            else java.time.LocalDate.parse(dateString),
                            templateIndex = rawTemplate.takeIf { it >= 0 },
                            entryIndex = rawEntry.takeIf { it >= 0 }
                        )
                        if (customShiftArgs != next) customShiftArgs = next
                    }

                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        // Main content with nav bar
                        Column(modifier = Modifier.fillMaxSize()) {
                            // The NavHost replaces your Box/when logic
                            // One set of transitions for every destination: tabs slide
                            // in the direction of their place in the bar, and push
                            // and pop are the same thing for peers (back from
                            // Analytics to Home still slides in from the left).
                            NavHost(
                                navController = navController,
                                startDestination = Screen.Vault.route,
                                modifier = Modifier.weight(1f),
                                enterTransition = {
                                    NavigationTransitions.getEnterTransition(initialState.destination.route, targetState.destination.route)
                                },
                                exitTransition = {
                                    NavigationTransitions.getExitTransition(initialState.destination.route, targetState.destination.route)
                                },
                                popEnterTransition = {
                                    NavigationTransitions.getEnterTransition(initialState.destination.route, targetState.destination.route)
                                },
                                popExitTransition = {
                                    NavigationTransitions.getExitTransition(initialState.destination.route, targetState.destination.route)
                                }
                            ) {
                                composable(
                                    route = Screen.Vault.route
                                ) {
                                    MainScreen(
                                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                                        onCreateCustomClick = { date ->
                                            navController.navigate(Screen.CustomShift.createRoute(date.toString()))
                                        },
                                        onEditEntry = { date, index ->
                                            navController.navigate(
                                                Screen.CustomShift.createRoute(date.toString(), entryIndex = index)
                                            )
                                        },
                                        startOfWeek = startOfWeek,
                                        currencyCode = selectedCurrencyCode,
                                        customTemplates = profileTemplates,
                                        shifts = savedShifts,
                                        activeProfile = activeProfile,
                                        onOpenProfiles = { showProfileSheet = true },
                                        onEditProfile = {
                                            editingProfile = activeProfile
                                            showProfileEditor = true
                                        },
                                        restWarnings = restWarnings,
                                        addRequest = homeAddRequest.takeIf { shiftsLoaded },
                                        onAddRequestHandled = { homeAddRequest = null }
                                    )
                                }

                                composable(
                                    route = Screen.Templates.route
                                ) {
                                    TemplatesScreen(
                                        shiftManager = shiftManager,
                                        templates = savedTemplates,
                                        onEdit = { index ->
                                            navController.navigate(Screen.CustomShift.createRoute("none", index))
                                        },
                                        onCreateNew = {
                                            navController.navigate(Screen.CustomShift.createRoute("none"))
                                        },
                                        currencyCode = selectedCurrencyCode,
                                        activeProfile = activeProfile,
                                        onOpenProfiles = { showProfileSheet = true },
                                        // Nothing to copy from until a second
                                        // job exists, so the action isn't there.
                                        onCopyFromProfile = if (profileState.others.isEmpty()) null
                                        else ({ showCopySheet = true })
                                    )
                                }

                                composable(
                                    route = Screen.Wallet.route
                                ) {
                                    WalletScreen(
                                        savedShifts = profileShifts,
                                        ledger = profileLedger,
                                        currencyCode = selectedCurrencyCode,
                                        activeProfile = activeProfile,
                                        onOpenProfiles = { showProfileSheet = true },
                                        onExport = { shown -> openExportSheet(ExportFormat.PDF, shown) },
                                        onSaveEarning = { earning -> shiftManager.saveEarning(earning) },
                                        onDeleteEarning = { id -> scope.launch { shiftManager.deleteEarning(id) } },
                                        onAddPayment = { payment ->
                                            shiftManager.addPayment(payment).also { problem ->
                                                // Money arriving is a good moment, if any is.
                                                if (problem == null) maybeAskForReview()
                                            }
                                        },
                                        onDeletePayment = { id -> scope.launch { shiftManager.deletePayment(id) } },
                                        weekStart = weekStartDay(startOfWeek),
                                        onAddExpected = { lines -> shiftManager.addEarnings(lines) },
                                        onSetShortfallAccepted = { id, accepted ->
                                            scope.launch { shiftManager.setShortfallAccepted(id, accepted) }
                                        },
                                        onSetExtraPaid = { targets, paid ->
                                            scope.launch {
                                                shiftManager.updateShifts { map ->
                                                    ExtraHours.setPaid(map, activeProfile.id, targets, paid)
                                                }
                                            }
                                        }
                                    )
                                }


                                composable(
                                    route = Screen.Settings.route
                                ) {
                                    SettingsScreen(
                                        isDarkTheme = isDarkTheme,
                                        themeMode = savedThemeMode,
                                        onThemeModeChange = { mode ->
                                            scope.launch { shiftManager.saveThemeMode(mode) }
                                        },
                                        themeVariant = themeVariant,
                                        onThemeVariantChange = { variant ->
                                            scope.launch {
                                                if (isDarkTheme) {
                                                    shiftManager.saveDarkThemeVariant(variant)
                                                } else {
                                                    shiftManager.saveLightThemeVariant(variant)
                                                }
                                            }
                                        },
                                        selectedCurrencyCode = selectedCurrencyCode,
                                        onCurrencyChange = { newCode ->
                                            scope.launch {
                                                shiftManager.saveCurrency(newCode)
                                            }
                                        },
                                        startOfWeek = startOfWeek,
                                        onStartOfWeekChange = { newDay ->
                                            scope.launch {
                                                shiftManager.saveStartOfWeek(newDay)
                                            }
                                        },
                                        onBackClick = { navController.popBackStack() },
                                        onExportPdf = { openExportSheet(ExportFormat.PDF, null) },
                                        onExportExcel = { openExportSheet(ExportFormat.EXCEL, null) },
                                        onExportCalendar = { openExportSheet(ExportFormat.CALENDAR, null) },
                                        restWarnings = restWarnings,
                                        onRestWarningsChange = { enabled ->
                                            scope.launch { shiftManager.saveRestWarnings(enabled) }
                                        },
                                        lastBackupAt = lastBackupAt,
                                        hasData = savedShifts.isNotEmpty(),
                                        onBackupExport = {
                                            exportLauncher.launch(BackupHelper.generateBackupFileName())
                                        },
                                        onBackupImport = {
                                            // Drive, many file managers and files received
                                            // in chat apps label .json as octet-stream or
                                            // plain text; filtering on application/json
                                            // alone greyed those backups out. The content
                                            // is validated after picking either way.
                                            importLauncher.launch(
                                                arrayOf("application/json", "text/plain", "application/octet-stream")
                                            )
                                        },
                                        backupImportPending = pendingImportUri != null,
                                        reminders = reminderSettings,
                                        onRemindersChange = { next ->
                                            scope.launch { shiftManager.saveReminderSettings(next) }
                                        },
                                        onConfirmImport = {
                                            val uriString = pendingImportUri
                                            pendingImportUri = null
                                            if (uriString != null) {
                                                scope.launch {
                                                    try {
                                                        val backupData = loadBackup(android.net.Uri.parse(uriString))
                                                        if (backupData != null) {
                                                            shiftManager.restoreFromBackup(backupData)
                                                            Toast.makeText(context, "Data restored successfully!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        onDismissImport = {
                                            pendingImportUri = null
                                        }
                                    )
                                }

                                // CustomShift route registration (renders as overlay, not here)
                                composable(
                                    route = Screen.CustomShift.route,
                                    arguments = listOf(
                                        navArgument("date") { type = NavType.StringType },
                                        navArgument("templateIndex") {
                                            type = NavType.IntType; defaultValue = -1
                                        },
                                        navArgument("entryIndex") {
                                            type = NavType.IntType; defaultValue = -1
                                        }
                                    )
                                ) {
                                    // Empty - actual content renders as overlay in Box
                                }

                            }

                            BottomNavigationBar(
                                currentRoute = currentRoute,
                                onNavigate = { route ->
                                    // Already there: nothing to navigate, nothing to animate.
                                    if (route == currentRoute) return@BottomNavigationBar
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        } // end Column

                        // Scrim overlay when CustomShift is visible
                        AnimatedVisibility(
                            visible = isCustomShiftVisible,
                            enter = fadeIn(animationSpec = tween(200)),
                            exit = fadeOut(animationSpec = tween(280)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                            )
                        }

                        // CustomShift screen as overlay
                        AnimatedVisibility(
                            visible = isCustomShiftVisible,
                            enter = NavigationTransitions.customShiftEnter,
                            exit = NavigationTransitions.customShiftExit,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val args = customShiftArgs ?: CustomShiftArgs(null, null, null)

                            CustomShiftScreen(
                                selectedDate = args.date,
                                templateIndex = args.templateIndex,
                                entryIndex = args.entryIndex,
                                shiftManager = shiftManager,
                                currencyCode = selectedCurrencyCode,
                                activeProfile = activeProfile,
                                // The overlay stays composed for the whole exit
                                // animation, so a second back press while it is
                                // sliding away would otherwise pop the tab
                                // underneath it as well.
                                onBackClick = { popCustomShift(navController) },
                                onSaveFinished = { popCustomShift(navController) }
                            )
                        }

                        // ══════════ Job Profile sheets ══════════
                        //
                        // Same shell, handle and animation as every other sheet
                        // in the app — switching jobs should feel like the
                        // actions the user already knows, not a new surface.

                        if (showProfileSheet) {
                            ProfileSwitcherSheet(
                                profiles = profileState.profiles,
                                activeId = activeProfile.id,
                                summaries = profileSummaries,
                                sheetState = profileSheetState,
                                // Every one of these slides the sheet down first
                                // and only then touches app state. Writing to
                                // DataStore while the sheet is still animating
                                // recomposes the screen underneath it mid-slide,
                                // which is what made these sheets vanish instead
                                // of closing.
                                onSelect = { id ->
                                    scope.launch {
                                        profileSheetState.hide()
                                        showProfileSheet = false
                                        profileState.select(id)
                                    }
                                },
                                onEdit = { profile ->
                                    scope.launch {
                                        profileSheetState.hide()
                                        showProfileSheet = false
                                        editingProfile = profile
                                        showProfileEditor = true
                                    }
                                },
                                onCreate = {
                                    scope.launch {
                                        profileSheetState.hide()
                                        showProfileSheet = false
                                        editingProfile = null
                                        showProfileEditor = true
                                    }
                                },
                                onDismiss = { showProfileSheet = false }
                            )
                        }

                        if (showProfileEditor) {
                            ProfileEditorSheet(
                                editing = editingProfile,
                                currencySymbol = remember(selectedCurrencyCode) {
                                    try {
                                        java.util.Currency.getInstance(selectedCurrencyCode)
                                            .getSymbol(java.util.Locale.getDefault())
                                    } catch (_: Exception) { selectedCurrencyCode }
                                },
                                sheetState = profileEditorState,
                                canDelete = profileState.profiles.size > 1,
                                onSave = { profile ->
                                    // A profile you just created is the one you
                                    // meant to start using.
                                    val isNew = editingProfile == null
                                    scope.launch {
                                        profileEditorState.hide()
                                        showProfileEditor = false
                                        editingProfile = null
                                        profileState.save(profile, makeActive = isNew)
                                    }
                                },
                                onDelete = { profile ->
                                    scope.launch {
                                        profileEditorState.hide()
                                        showProfileEditor = false
                                        editingProfile = null
                                        profileState.delete(profile.id)
                                        Toast.makeText(
                                            context,
                                            "\"${profile.name}\" deleted",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onDismiss = {
                                    showProfileEditor = false
                                    editingProfile = null
                                }
                            )
                        }

                        if (showExportSheet) {
                            val shown = if (exportShownStart != null && exportShownEnd != null)
                                ExportPeriod(
                                    "Shown in Insights",
                                    DateRange(
                                        java.time.LocalDate.parse(exportShownStart),
                                        java.time.LocalDate.parse(exportShownEnd)
                                    )
                                )
                            else null
                            ExportSheet(
                                profile = activeProfile,
                                initialFormat = exportInitialFormat,
                                shownPeriod = shown,
                                sheetState = exportSheetState,
                                onExport = { format, period, action ->
                                    scope.launch {
                                        exportSheetState.hide()
                                        showExportSheet = false
                                        val request = ExportRequest.of(format, period.range)
                                        when (action) {
                                            ExportAction.SHARE -> shareReport(request)
                                            ExportAction.SAVE -> {
                                                pendingSave = request
                                                val name = ExportHelper.suggestFileName(
                                                    activeProfile.name, format.extension, period.range
                                                )
                                                when (format) {
                                                    ExportFormat.PDF -> pdfExportLauncher.launch(name)
                                                    ExportFormat.EXCEL -> excelExportLauncher.launch(name)
                                                    ExportFormat.CALENDAR -> calendarExportLauncher.launch(name)
                                                }
                                            }
                                        }
                                    }
                                },
                                onDismiss = { showExportSheet = false }
                            )
                        }

                        if (showUnreadableNotice) {
                            UnreadableDataDialog(
                                onDismiss = { scope.launch { shiftManager.acknowledgeUnreadableDataNotice() } }
                            )
                        }

                        if (showCopySheet) {
                            CopyShiftsSheet(
                                target = activeProfile,
                                sources = profileState.others,
                                allTemplates = savedTemplates,
                                sheetState = copySheetState,
                                onConfirm = { picked ->
                                    scope.launch {
                                        copySheetState.hide()
                                        showCopySheet = false
                                        val result = profileState.copySavedShifts(picked)
                                        Toast.makeText(
                                            context,
                                            copyResultMessage(result, activeProfile.name),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onDismiss = { showCopySheet = false }
                            )
                        }
                    } // end Box
            }
        }
    }
}

/**
 * Reports a copy in the user's terms. Says plainly when nothing moved, and why,
 * rather than claiming a success the Shifts list won't show.
 */
private fun copyResultMessage(result: CopyResult, to: String): String = when {
    result.copied == 0 -> "Already in $to — nothing copied"
    result.skipped == 0 ->
        "Copied ${result.copied} shift${if (result.copied == 1) "" else "s"} into $to"
    else ->
        "Copied ${result.copied} into $to · ${result.skipped} already there"
}

/**
 * Shown once when stored data couldn't be read and was set aside. Says plainly
 * what happened and that nothing was deleted, so the user isn't left staring
 * at an empty calendar wondering where their shifts went.
 */
@Composable
private fun UnreadableDataDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = cs.surface,
        icon = { Icon(AppIcons.Warning, null, tint = cs.primary) },
        title = {
            Text("Some data couldn't be read", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
        },
        text = {
            Text(
                "Part of your saved data was stored in a form this version can't read. " +
                    "It hasn't been deleted: a safety copy is kept on your device, and a " +
                    "future update can recover it. If you have a backup file, you can " +
                    "restore it from Settings > Back up & restore.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = CircleShape) {
                Text("Got it", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

/** The shift form's arguments, latched while its overlay animates away. */
private data class CustomShiftArgs(
    val date: java.time.LocalDate?,
    val templateIndex: Int?,
    val entryIndex: Int?
)

/** Pops the CustomShift overlay, and only ever that. */
private fun popCustomShift(navController: androidx.navigation.NavController) {
    if (navController.currentDestination?.route == Screen.CustomShift.route) {
        navController.popBackStack()
    }
}

/** Intent actions of the launcher shortcuts in res/xml/shortcuts.xml. */
internal object Shortcuts {
    const val ADD_SHIFT = "com.leonidaskiachidis.shiftvault.action.ADD_SHIFT"
    const val WALLET = "com.leonidaskiachidis.shiftvault.action.WALLET"
    const val EXPORT = "com.leonidaskiachidis.shiftvault.action.EXPORT"
}

private class NavDestination(val label: String, @androidx.annotation.DrawableRes val icon: Int, val route: String)

private val NavDestinations = listOf(
    NavDestination("Home", R.drawable.ic_nav_home, Screen.Vault.route),
    NavDestination("Shifts", R.drawable.ic_nav_shifts, Screen.Templates.route),
    NavDestination("Wallet", R.drawable.ic_wallet, Screen.Wallet.route),
    NavDestination("Settings", R.drawable.ic_nav_settings, Screen.Settings.route)
)

/**
 * A tab's place in the bar, left to right, or -1 for anything that isn't a tab.
 * The single ordering both the pill and the page slide take their direction from.
 */
internal fun navIndexOf(route: String?): Int = NavDestinations.indexOfFirst { it.route == route }

// The bar floats as a pill above the page; the current tab is a filled capsule
// that carries its label, the others are icons alone.
private val NavBarHeight = 64.dp
private val NavItemHeight = 46.dp
private val NavItemMinWidth = 48.dp

// One spring for the capsule growing, shrinking and recolouring. Lightly
// under-damped, it settles softly rather than stopping hard, and is done before
// the page slide is; a tap mid-flight simply re-aims it.
private val NavSpring = spring<IntSize>(dampingRatio = 0.82f, stiffness = 520f)

@Composable
fun BottomNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDark = AppTheme.accents.isDark

    // While the shift form is over the tabs no tab route is current; the
    // capsule stays where it was instead of vanishing and flying back in.
    val routeIndex = navIndexOf(currentRoute)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    if (routeIndex >= 0 && routeIndex != selectedIndex) selectedIndex = routeIndex

    Box(
        Modifier
            .fillMaxWidth()
            .background(cs.background)
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavBarHeight)
                // A long, low shadow lifts the pill off the page; on a dark
                // ground it says nothing, so only the hairline is left.
                .then(
                    if (isDark) Modifier
                    else Modifier.shadow(
                        elevation = 18.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.10f),
                        spotColor = Color.Black.copy(alpha = 0.22f)
                    )
                ),
            shape = CircleShape,
            color = cs.surface,
            contentColor = cs.onSurface,
            border = BorderStroke(1.dp, cs.outline)
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavDestinations.forEachIndexed { index, destination ->
                    BottomNavItem(
                        label = destination.label,
                        icon = destination.icon,
                        isSelected = index == selectedIndex,
                        onClick = { onNavigate(destination.route) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    @androidx.annotation.DrawableRes icon: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val motion = tween<Color>(220)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val container by animateColorAsState(if (isSelected) cs.primary else Color.Transparent, motion, label = "navContainer")
    val content by animateColorAsState(if (isSelected) cs.onPrimary else cs.onSurfaceVariant, motion, label = "navContent")
    val fill by animateColorAsState(
        if (isSelected) cs.onPrimary.copy(alpha = 0.28f) else Color.Transparent,
        motion, label = "navFill"
    )
    // A press dips whichever tab is touched, so the tap is felt even on the current one.
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "navScale"
    )

    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(NavItemHeight)
            .widthIn(min = NavItemMinWidth)
            .clip(CircleShape)
            .drawBehind { drawRect(container) }
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = label }
            .animateContentSize(NavSpring)
            .padding(horizontal = if (isSelected) 16.dp else 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToneIcon(
            id = icon,
            contentDescription = null,
            tones = IconTones(content, fill, content),
            size = if (isSelected) 20.dp else 22.dp
        )
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                // The tab already carries its name for TalkBack.
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BottomNavigationBarPreview() {
    ShiftVaultTheme {
        BottomNavigationBar(
            currentRoute = Screen.Vault.route,
            onNavigate = {}
        )
    }
}