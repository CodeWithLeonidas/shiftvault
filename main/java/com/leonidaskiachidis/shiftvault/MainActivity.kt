package com.leonidaskiachidis.shiftvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
import com.leonidaskiachidis.shiftvault.ui.theme.ElectricViolet
import com.leonidaskiachidis.shiftvault.ui.theme.MidnightBlue
import com.leonidaskiachidis.shiftvault.ui.theme.MutedText
import com.leonidaskiachidis.shiftvault.ui.theme.ShiftVaultTheme
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import com.leonidaskiachidis.shiftvault.ui.theme.SlateGrey
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.leonidaskiachidis.shiftvault.ui.theme.ChampagneGold
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 1. Create a state variable for the theme
            val context = androidx.compose.ui.platform.LocalContext.current
            val shiftManager = remember { ShiftManager(context) }
            val scope = rememberCoroutineScope()

            val systemDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(systemDark) }

// This loads the currency from your persistent storage
            val selectedCurrencyCode by shiftManager.getCurrency.collectAsState(initial = "EUR")

            // 2. Pass this state to your Theme wrapper
            ShiftVaultTheme(darkTheme = isDarkTheme) {
                val navController = rememberNavController()

                // We need to track the current route to highlight the bottom bar icons
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

                // The NavHost replaces your Box/when logic
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Vault.route,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable(
                            route = Screen.Vault.route,
                            // Replace all your enter/exit/pop transitions with this unified style:
                            enterTransition = {
                                scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec = tween(350, easing = EaseOutBack) // "Playful" bounce
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                scaleOut(
                                    targetScale = 1.05f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            },
                            popEnterTransition = {
                                scaleIn(
                                    initialScale = 1.05f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            popExitTransition = {
                                scaleOut(
                                    targetScale = 0.92f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            }
                        )
                        {
                            MainScreen(
                                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                                onCreateCustomClick = { date ->
                                    navController.navigate(Screen.CustomShift.createRoute(date.toString()))
                                },
                                onStatsClick = { navController.navigate(Screen.Stats.route) }  // NEW
                            )
                        }

                        composable(
                            route = Screen.Templates.route,
                            enterTransition = {
                                scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                scaleOut(
                                    targetScale = 1.05f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            },
                            popEnterTransition = {
                                scaleIn(
                                    initialScale = 1.05f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            popExitTransition = {
                                scaleOut(
                                    targetScale = 0.92f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            }
                        ) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            TemplatesScreen(
                                shiftManager = shiftManager,
                                onEdit = { index ->
                                    navController.navigate(Screen.CustomShift.createRoute("none", index))
                                },
                                onCreateNew = {
                                    navController.navigate(Screen.CustomShift.createRoute("none"))
                                }
                            )
                        }

                        composable(
                            route = Screen.Stats.route,
                            enterTransition = {
                                scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                scaleOut(
                                    targetScale = 1.05f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            },
                            popEnterTransition = {
                                scaleIn(
                                    initialScale = 1.05f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            popExitTransition = {
                                scaleOut(
                                    targetScale = 0.92f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            }
                        ) {
                            var savedShifts by remember { mutableStateOf(mapOf<java.time.LocalDate, List<Shift>>()) }

                            LaunchedEffect(Unit) {
                                shiftManager.getShifts.collect { loadedShifts ->
                                    savedShifts = loadedShifts
                                }
                            }

                            StatsScreen(
                                savedShifts = savedShifts, // Passing your shifts map
                                onBackClick = { navController.popBackStack() },
                                currencyCode = selectedCurrencyCode // PASSING THE DYNAMIC CURRENCY
                            )
                        }



                        composable(
                            route = Screen.Settings.route,
                            enterTransition = {
                                scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                scaleOut(
                                    targetScale = 1.05f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            },
                            popEnterTransition = {
                                scaleIn(
                                    initialScale = 1.05f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            popExitTransition = {
                                scaleOut(
                                    targetScale = 0.92f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            }
                        ) {
                            // 1. Create a state to hold shifts for the export
                            var savedShifts by remember { mutableStateOf(mapOf<java.time.LocalDate, List<Shift>>()) }

                            // 2. Load them from the manager
                            LaunchedEffect(Unit) {
                                shiftManager.getShifts.collect { loadedShifts ->
                                    savedShifts = loadedShifts
                                }
                            }
                            SettingsScreen(
                                isDarkTheme = isDarkTheme,
                                onThemeChange = { isDark -> isDarkTheme = isDark },
                                // ADD THESE TWO LINES:
                                selectedCurrencyCode = selectedCurrencyCode,
                                onCurrencyChange = { newCode ->
                                    scope.launch {
                                        shiftManager.saveCurrency(newCode)
                                    }
                                },
                                onBackClick = { navController.popBackStack() },
                                onExportPdf = { ExportHelper.exportShiftsToPdf(context, savedShifts) },
                            )
                        }

                        composable(
                            route = Screen.CustomShift.route,
                            arguments = listOf(
                                navArgument("date") { type = NavType.StringType },
                                navArgument("templateIndex") {
                                    type = NavType.IntType; defaultValue = -1
                                }
                            ),
                            enterTransition = {
                                scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                scaleOut(
                                    targetScale = 1.05f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            },
                            popEnterTransition = {
                                scaleIn(
                                    initialScale = 1.05f,
                                    animationSpec = tween(350, easing = EaseOutBack)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            popExitTransition = {
                                scaleOut(
                                    targetScale = 0.92f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            }
                        ) { backStackEntry ->
                            val dateString = backStackEntry.arguments?.getString("date")
                            val date = if (dateString == "none") null else java.time.LocalDate.parse(dateString)
                            val templateIndex = backStackEntry.arguments?.getInt("templateIndex") ?: -1
                            val context = androidx.compose.ui.platform.LocalContext.current

                            CustomShiftScreen(
                                selectedDate = date,
                                templateIndex = if (templateIndex == -1) null else templateIndex,
                                shiftManager = shiftManager,
                                currencyCode = selectedCurrencyCode,
                                onBackClick = { navController.popBackStack() },
                                onSaveFinished = { navController.popBackStack() }
                            )
                        }
                    }



                    BottomNavigationBar(
                        currentRoute = currentRoute,
                        isVisible = currentRoute != Screen.CustomShift.route,
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
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Composable
fun BottomNavigationBar(
    currentRoute: String?,
    isVisible: Boolean = true,
    onNavigate: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val barBackground = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
    val barElevation = if (isDark) 0.dp else 8.dp
    val barBorderColor = if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f) else Color.Transparent

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(400, easing = EaseOutBack)
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(250))
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = barElevation, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
            color = barBackground,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = if (isDark) BorderStroke(1.dp, barBorderColor) else null
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(0.dp)
                    .height(92.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val items = listOf(
                    Triple("Vault", Icons.Filled.CalendarMonth to Icons.Outlined.CalendarMonth, Screen.Vault.route),
                    Triple("Templates", Icons.Filled.PendingActions to Icons.Outlined.PendingActions, Screen.Templates.route),
                    Triple("Stats", Icons.Filled.BarChart to Icons.Outlined.BarChart, Screen.Stats.route),
                    Triple("Settings", Icons.Filled.Settings to Icons.Outlined.Settings, Screen.Settings.route)
                )

                items.forEach { (label, icons, route) ->
                    val isSelected = currentRoute == route

                    CreativeNavItem(
                        label = label,
                        selectedIcon = icons.first,
                        unselectedIcon = icons.second,
                        isSelected = isSelected,
                        isDark = isDark,
                        onClick = { onNavigate(route) }
                    )
                }
            }
        }
    }
}


@Composable
fun CreativeNavItem(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    // Animation States
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Bounce effect on click
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        label = "scale"
    )

    // Rotation effect when selected
    val rotation by animateFloatAsState(
        targetValue = if (isSelected) 0f else 0f, // Use simpler rotation logic: 0->360 on select trigger
        animationSpec = tween(300)
    )

    // Dynamic Colors based on Theme
    val selectedColor = if (isDark) ElectricViolet else MaterialTheme.colorScheme.primary
    val unselectedColor = if (isDark) SlateGrey else MutedText
    val indicatorColor = if (isDark) ElectricViolet.copy(alpha = 0.15f) else ChampagneGold.copy(alpha = 0.18f)

    // Animated container size
    val animatedWeight by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.8f,
        label = "weight"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .height(50.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (isSelected) indicatorColor else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isSelected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = if (isSelected) selectedColor else unselectedColor,
                modifier = Modifier
                    .size(24.dp)
                    // Add a slight rotate shake when selecting
                    .graphicsLayer {
                        rotationZ = if(isSelected) 0f else 0f
                    }
            )

            // Animated Text Reveal
            AnimatedVisibility(
                visible = isSelected,
                enter = expandIn(expandFrom = Alignment.CenterStart, animationSpec = tween(200)) + fadeIn(tween(200)),
                exit = shrinkOut(shrinkTowards = Alignment.CenterStart, animationSpec = tween(200)) + fadeOut(tween(200))
            ) {
                Row {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        color = selectedColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
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
