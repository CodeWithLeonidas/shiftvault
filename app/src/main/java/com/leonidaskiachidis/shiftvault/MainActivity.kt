package com.leonidaskiachidis.shiftvault

import android.os.Bundle
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
import androidx.compose.material3.rememberModalBottomSheetState
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
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    LaunchedEffect(Unit) {
                        // Anything that no longer decodes is moved to a safety
                        // copy before any screen gets a chance to write.
                        launch { shiftManager.setAsideUnreadableData() }
                        launch {
                            shiftManager.getShifts.collect { loadedShifts ->
                                savedShifts = loadedShifts
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
                    val profileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val profileEditorState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val copySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    // The picked backup file, held by URI rather than by its
                    // contents: it survives rotation this way (a whole backup
                    // is far too big for saved state) and is read again on
                    // confirm.
                    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }

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
                        }
                    }

                    val pdfExportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument("application/pdf")
                    ) { uri ->
                        runExport(uri, "PDF") { out ->
                            ExportHelper.exportShiftsToPdf(context, profileShifts, out, reportMeta)
                        }
                    }

                    val excelExportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    ) { uri ->
                        runExport(uri, "Excel file") { out ->
                            ExportHelper.exportShiftsToExcel(profileShifts, out, reportMeta)
                        }
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

                    // We need to track the current route to highlight the bottom bar icons
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val isCustomShiftVisible = currentRoute == Screen.CustomShift.route

                    // The entry's arguments are gone the instant it is popped, so
                    // they are latched here. The overlay keeps rendering the same
                    // content while it slides away instead of blanking out mid
                    // animation — and it stops recomposing the form on every pop.
                    var customShiftArgs by remember {
                        mutableStateOf<Pair<java.time.LocalDate?, Int?>?>(null)
                    }
                    if (isCustomShiftVisible) {
                        val entry = navBackStackEntry
                        val dateString = entry?.arguments?.getString("date")
                        val rawIndex = entry?.arguments?.getInt("templateIndex") ?: -1
                        val parsed =
                            if (dateString == null || dateString == "none") null
                            else java.time.LocalDate.parse(dateString)
                        val next = parsed to (if (rawIndex == -1) null else rawIndex)
                        if (customShiftArgs != next) customShiftArgs = next
                    }

                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        // Main content with nav bar
                        Column(modifier = Modifier.fillMaxSize()) {
                            // The NavHost replaces your Box/when logic
                            NavHost(
                                navController = navController,
                                startDestination = Screen.Vault.route,
                                modifier = Modifier.weight(1f)
                            ) {
                                composable(
                                    route = Screen.Vault.route,
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
                                    MainScreen(
                                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                                        onCreateCustomClick = { date ->
                                            navController.navigate(Screen.CustomShift.createRoute(date.toString()))
                                        },
                                        startOfWeek = startOfWeek,
                                        currencyCode = selectedCurrencyCode,
                                        customTemplates = profileTemplates,
                                        shifts = savedShifts,
                                        activeProfile = activeProfile,
                                        onOpenProfiles = { showProfileSheet = true }
                                    )
                                }

                                composable(
                                    route = Screen.Templates.route,
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
                                    route = Screen.Stats.route,
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
                                    StatsScreen(
                                        savedShifts = profileShifts,
                                        onBackClick = { navController.popBackStack() },
                                        currencyCode = selectedCurrencyCode,
                                        onExportPdf = {
                                            pdfExportLauncher.launch(ExportHelper.suggestFileName(activeProfile.name, "pdf"))
                                        },
                                        onExportExcel = {
                                            excelExportLauncher.launch(ExportHelper.suggestFileName(activeProfile.name, "xlsx"))
                                        },
                                        activeProfile = activeProfile,
                                        onOpenProfiles = { showProfileSheet = true }
                                    )
                                }


                                composable(
                                    route = Screen.Settings.route,
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
                                        onExportPdf = {
                                            pdfExportLauncher.launch(ExportHelper.suggestFileName(activeProfile.name, "pdf"))
                                        },
                                        onExportExcel = {
                                            excelExportLauncher.launch(ExportHelper.suggestFileName(activeProfile.name, "xlsx"))
                                        },
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
                                        }
                                    )
                                ) {
                                    // Empty - actual content renders as overlay in Box
                                }

                            }

                            BottomNavigationBar(
                                currentRoute = currentRoute,
                                onNavigate = { route ->
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
                            val (date, templateIndex) = customShiftArgs ?: (null to null)

                            CustomShiftScreen(
                                selectedDate = date,
                                templateIndex = templateIndex,
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

/** Pops the CustomShift overlay, and only ever that. */
private fun popCustomShift(navController: androidx.navigation.NavController) {
    if (navController.currentDestination?.route == Screen.CustomShift.route) {
        navController.popBackStack()
    }
}

@Composable
fun BottomNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val isDark = AppTheme.accents.isDark

    // Curved shoulders on the left and right, sitting on its own surface so the
    // curve actually reads against the page behind it. The old flat hairline
    // separator is gone — the shape and the lift do that job now.
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = cs.surface,
        contentColor = cs.onSurface,
        // A shadow says nothing on a dark ground, so the edge is drawn instead.
        shadowElevation = if (isDark) 0.dp else 10.dp,
        border = if (isDark) BorderStroke(1.dp, cs.outline) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val items = listOf(
                Triple("Home", AppIcons.Home, Screen.Vault.route),
                Triple("Shifts", AppIcons.CalendarMonth, Screen.Templates.route),
                Triple("Analytics", AppIcons.BarChart, Screen.Stats.route),
                Triple("Settings", AppIcons.Settings, Screen.Settings.route)
            )

            items.forEach { (label, icon, route) ->
                BottomNavItem(
                    label = label,
                    icon = icon,
                    isSelected = currentRoute == route,
                    onClick = { onNavigate(route) }
                )
            }
        }
    }
}

@Composable
fun BottomNavItem(
    label: String,
    icon: Painter,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val tint = if (isSelected) cs.primary else cs.onSurfaceVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
        )
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