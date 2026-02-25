package com.leonidaskiachidis.shiftvault

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Vault : Screen("vault")
    object Templates : Screen("templates")
    object Stats : Screen("stats")
    object Settings : Screen("settings")
    object CustomShift : Screen("custom_shift/{date}?templateIndex={templateIndex}") {
        fun createRoute(date: String, templateIndex: Int? = null): String {
            return if (templateIndex != null) {
                "custom_shift/$date?templateIndex=$templateIndex"
            } else {
                "custom_shift/$date"
            }
        }
    }
}
