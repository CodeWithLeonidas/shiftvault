package com.leonidaskiachidis.shiftvault

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Page slides follow the bottom bar's order: Home, Shifts, Wallet, Settings. */
class NavigationDirectionTest {

    private val home = Screen.Vault.route
    private val shifts = Screen.Templates.route
    private val analytics = Screen.Wallet.route
    private val settings = Screen.Settings.route

    @Test
    fun `tabs are ordered as they appear in the bar`() {
        assertEquals(listOf(0, 1, 2, 3), listOf(home, shifts, analytics, settings).map(::navIndexOf))
    }

    @Test
    fun `moving right slides in from the right, left from the left`() {
        assertEquals(1, NavigationTransitions.tabDirection(home, shifts))
        assertEquals(-1, NavigationTransitions.tabDirection(shifts, home))
    }

    @Test
    fun `non-adjacent tabs still take the bar's direction`() {
        assertEquals(1, NavigationTransitions.tabDirection(home, settings))
        assertEquals(-1, NavigationTransitions.tabDirection(settings, shifts))
        assertEquals(-1, NavigationTransitions.tabDirection(analytics, home))
    }

    @Test
    fun `the same tab, the shift form and unknown routes get no slide`() {
        assertEquals(0, NavigationTransitions.tabDirection(analytics, analytics))
        assertEquals(0, NavigationTransitions.tabDirection(home, Screen.CustomShift.route))
        assertEquals(0, NavigationTransitions.tabDirection(Screen.CustomShift.route, home))
        assertEquals(0, NavigationTransitions.tabDirection(null, home))

        assertEquals(EnterTransition.None, NavigationTransitions.getEnterTransition(home, Screen.CustomShift.route))
        assertEquals(ExitTransition.None, NavigationTransitions.getExitTransition(Screen.CustomShift.route, home))
    }

    @Test
    fun `tab changes do animate`() {
        assertNotEquals(EnterTransition.None, NavigationTransitions.getEnterTransition(home, analytics))
        assertNotEquals(ExitTransition.None, NavigationTransitions.getExitTransition(home, analytics))
    }
}
