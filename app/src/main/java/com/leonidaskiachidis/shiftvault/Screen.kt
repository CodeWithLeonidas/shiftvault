package com.leonidaskiachidis.shiftvault

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Vault : Screen("vault")
    object Templates : Screen("templates")
    /** Earnings, payments and the shift analytics (formerly "Analytics"). */
    object Wallet : Screen("wallet")
    object Settings : Screen("settings")
    /**
     * The shift form. [date] is "none" when editing a saved shift. A
     * templateIndex edits that saved shift; an entryIndex edits that one
     * calendar entry (index within the active job's shifts on [date]).
     */
    object CustomShift : Screen("custom_shift/{date}?templateIndex={templateIndex}&entryIndex={entryIndex}") {
        fun createRoute(date: String, templateIndex: Int? = null, entryIndex: Int? = null): String {
            val params = buildList {
                templateIndex?.let { add("templateIndex=$it") }
                entryIndex?.let { add("entryIndex=$it") }
            }
            return if (params.isEmpty()) "custom_shift/$date"
            else "custom_shift/$date?" + params.joinToString("&")
        }
    }
}
