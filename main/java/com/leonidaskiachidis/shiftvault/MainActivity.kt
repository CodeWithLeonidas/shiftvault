package com.leonidaskiachidis.shiftvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 1. Create a state variable for the theme
            val systemDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(systemDark) }

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
                            enterTransition = {
                                when (initialState.destination.route) {
                                    Screen.Templates.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.Stats.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.Settings.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.CustomShift.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    else -> slideInHorizontally(
                                        initialOffsetX = { -it / 3 },
                                        animationSpec = tween(250)
                                    ) + fadeIn(tween(250))
                                }
                            },

                            exitTransition = {
                                when (targetState.destination.route) {
                                    Screen.Templates.route -> slideOutHorizontally(
                                        targetOffsetX = { -it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.Stats.route -> slideOutHorizontally(
                                        targetOffsetX = { -it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.Settings.route -> slideOutHorizontally(
                                        targetOffsetX = { -it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.CustomShift.route -> slideOutHorizontally(
                                        targetOffsetX = { -it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    else -> slideOutHorizontally(
                                        targetOffsetX = { -it / 3 },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                }
                            },

                            popEnterTransition = {
                                when (initialState.destination.route) {
                                    Screen.Templates.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.Stats.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.Settings.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.CustomShift.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    else -> fadeIn(tween(200))
                                }
                            },

                            popExitTransition = {
                                when (targetState.destination.route) {
                                    Screen.Templates.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.Stats.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.Settings.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.CustomShift.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    else -> fadeOut(tween(150))
                                }
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
                                when (initialState.destination.route) {
                                    Screen.Vault.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.Settings.route -> slideInHorizontally(
                                        initialOffsetX = { -it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    else -> fadeIn(tween(200))
                                }
                            },
                            exitTransition = {
                                when (targetState.destination.route) {
                                    Screen.Vault.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.Settings.route -> slideOutHorizontally(
                                        targetOffsetX = { -it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    else -> fadeOut(tween(150))
                                }
                            },
                            popEnterTransition = { fadeIn(tween(200)) },
                            popExitTransition = { fadeOut(tween(150)) }
                        ) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            TemplatesScreen(
                                shiftManager = remember { ShiftManager(context) },
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
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                ) + fadeIn(tween(300))
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(250)
                                ) + fadeOut(tween(250))
                            },
                            popEnterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(250)
                                ) + fadeIn(tween(250))
                            },
                            popExitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                ) + fadeOut(tween(250))
                            }
                        ) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val shiftManager = remember { ShiftManager(context) }
                            var savedShifts by remember { mutableStateOf(mapOf<java.time.LocalDate, List<Shift>>()) }

                            LaunchedEffect(Unit) {
                                shiftManager.getShifts.collect { loadedShifts ->
                                    savedShifts = loadedShifts
                                }
                            }

                            StatsScreen(
                                savedShifts = savedShifts,
                                onBackClick = { navController.popBackStack() }  // UPDATED
                            )
                        }



                        composable(
                            route = Screen.Settings.route,
                            enterTransition = {
                                when (initialState.destination.route) {
                                    Screen.Templates.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.Vault.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    else -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeIn(tween(250))
                                }
                            },
                            exitTransition = {
                                when (targetState.destination.route) {
                                    Screen.Templates.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.Vault.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    else -> fadeOut(tween(150))
                                }
                            },
                            popEnterTransition = {
                                when (initialState.destination.route) {
                                    Screen.Templates.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    Screen.Vault.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(250))
                                    else -> fadeIn(tween(200))
                                }
                            },
                            popExitTransition = {
                                when (targetState.destination.route) {
                                    Screen.Templates.route -> slideOutHorizontally(
                                        targetOffsetX = { -it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    Screen.Vault.route -> slideOutHorizontally(
                                        targetOffsetX = { -it },
                                        animationSpec = tween(250)
                                    ) + fadeOut(tween(250))
                                    else -> fadeOut(tween(150))
                                }
                            }
                        ) {
                            SettingsScreen(
                                isDarkTheme = isDarkTheme,
                                onThemeChange = { isDark -> isDarkTheme = isDark },
                                onBackClick = { navController.popBackStack() }
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
                                when (initialState.destination.route) {
                                    Screen.Vault.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(300))
                                    Screen.Templates.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(300))
                                    else -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeIn(tween(300))
                                }
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(250)
                                ) + fadeOut(tween(250))
                            },
                            popEnterTransition = {
                                when (initialState.destination.route) {
                                    Screen.Vault.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeIn(tween(250))
                                    Screen.Templates.route -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeIn(tween(250))
                                    else -> slideInHorizontally(
                                        initialOffsetX = { it },
                                        animationSpec = tween(250)
                                    ) + fadeIn(tween(250))
                                }
                            },
                            popExitTransition = {
                                when (targetState.destination.route) {
                                    Screen.Vault.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeOut(tween(250))
                                    Screen.Templates.route -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeOut(tween(250))
                                    else -> slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    ) + fadeOut(tween(250))
                                }
                            }
                        ) { backStackEntry ->
                            val dateString = backStackEntry.arguments?.getString("date")
                            val date = if (dateString == "none") null else java.time.LocalDate.parse(dateString)
                            val templateIndex = backStackEntry.arguments?.getInt("templateIndex") ?: -1
                            val context = androidx.compose.ui.platform.LocalContext.current

                            CustomShiftScreen(
                                selectedDate = date,
                                templateIndex = if (templateIndex == -1) null else templateIndex,
                                shiftManager = remember { ShiftManager(context) },
                                onBackClick = { navController.popBackStack() },
                                onSaveFinished = { navController.popBackStack() }
                            )
                        }
                    }



                    BottomNavigationBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            navController.navigate(route) {
                                // Pop up to the start destination of the graph to
                                // avoid building up a large stack of destinations
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination when
                                // reselecting the same item
                                launchSingleTop = true
                                // Restore state when reselecting a previously selected item
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
    onNavigate: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()

    // Aesthetic configurations based on theme
    val barBackground = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
    val barElevation = if (isDark) 0.dp else 8.dp
    val barBorderColor = if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f) else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = barElevation, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        color = barBackground,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        // Fix: Use BorderStroke instead of Modifier.border
        border = if (isDark) BorderStroke(1.dp, barBorderColor) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .height(84.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val items = listOf(
                Triple("Vault", Icons.Filled.CalendarMonth to Icons.Outlined.CalendarMonth, Screen.Vault.route),
                Triple("Templates", Icons.Filled.Description to Icons.Outlined.Description, Screen.Templates.route),
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
    val indicatorColor = if (isDark) ElectricViolet.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer

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
