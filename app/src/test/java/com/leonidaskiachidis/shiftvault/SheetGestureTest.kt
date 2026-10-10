package com.leonidaskiachidis.shiftvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom sheet's gesture rules, driven the way Compose drives them: for
 * each finger movement the sheet sees preScroll, the content scrolls, then
 * postScroll with what the content used; a lifted finger ends in endScroll.
 */
class SheetGestureTest {

    private var offset = 0f
    private val sheet = SheetDragCoordinator(readOffset = { offset }, writeOffset = { offset = it })

    /**
     * One movement of [delta] px (positive = finger down) over content that has
     * [room] px it can still scroll towards its top. Returns the room left.
     */
    private fun move(delta: Float, room: Float): Float {
        val pre = sheet.preScroll(delta, fromUser = true)
        val left = delta - pre
        // Finger down scrolls the content back towards its top, up to [room].
        val consumed = if (left > 0f) minOf(left, room) else left
        sheet.postScroll(consumed, left - consumed, fromUser = true)
        return if (left > 0f) room - consumed else room - left
    }

    @Test
    fun `scrolled content keeps the gesture and stops at its top`() {
        sheet.onGestureStart()
        var room = 100f
        repeat(10) { room = move(30f, room) }
        assertEquals(0f, room, 0f)
        assertEquals(SheetGestureOwner.Content, sheet.owner)
        assertEquals("reaching the top mid-gesture must not pull the sheet", 0f, offset, 0f)
        assertFalse(sheet.endScroll())
    }

    @Test
    fun `repeated downward swipes in the middle of the content never move the sheet`() {
        var room = 1_000f
        repeat(5) {
            sheet.onGestureStart()
            repeat(4) { room = move(40f, room) }
            assertFalse(sheet.endScroll())
        }
        assertEquals(0f, offset, 0f)
    }

    @Test
    fun `content already at its top hands a downward drag to the sheet`() {
        sheet.onGestureStart()
        move(30f, room = 0f)
        move(30f, room = 0f)
        assertEquals(SheetGestureOwner.Sheet, sheet.owner)
        assertEquals(60f, offset, 0f)
        assertTrue("a pulled sheet settles instead of the content flinging", sheet.endScroll())
    }

    @Test
    fun `a gesture starting just short of the top only scrolls, the next one drags`() {
        sheet.onGestureStart()
        var room = move(20f, room = 5f)
        room = move(30f, room)
        assertEquals(0f, offset, 0f)
        sheet.endScroll()

        sheet.onGestureStart()
        move(30f, room)
        assertEquals(30f, offset, 0f)
    }

    @Test
    fun `pushing a pulled sheet back up hands the rest of the swipe to the content`() {
        sheet.onGestureStart()
        move(50f, room = 0f)
        assertEquals(50f, offset, 0f)

        val used = sheet.preScroll(-80f, fromUser = true)
        assertEquals(-50f, used, 0f)
        assertEquals(0f, offset, 0f)
    }

    @Test
    fun `the sheet never rises above its resting place`() {
        sheet.drag(-40f)
        assertEquals(0f, offset, 0f)
        sheet.drag(25f)
        sheet.drag(-100f)
        assertEquals(0f, offset, 0f)
    }

    @Test
    fun `momentum never moves the sheet`() {
        sheet.onGestureStart()
        val leftover = sheet.postScroll(consumed = 0f, available = 400f, fromUser = false)
        assertEquals("fling leftovers stop at the sheet", 400f, leftover, 0f)
        assertEquals(0f, sheet.preScroll(-30f, fromUser = false), 0f)
        assertEquals(0f, offset, 0f)
    }

    @Test
    fun `each new gesture decides afresh`() {
        sheet.onGestureStart()
        move(10f, room = 0f)
        assertEquals(SheetGestureOwner.Sheet, sheet.owner)
        sheet.endScroll()
        offset = 0f

        sheet.onGestureStart()
        assertEquals(SheetGestureOwner.Undecided, sheet.owner)
        move(10f, room = 100f)
        assertEquals(SheetGestureOwner.Content, sheet.owner)
    }

    // ── Release thresholds (density 1: px == dp) ──

    private val height = 600f

    @Test
    fun `a short flick does not dismiss`() {
        assertFalse(shouldDismissSheet(offset = 20f, velocity = 3_000f, sheetHeight = height, density = 1f))
    }

    @Test
    fun `a real flick downwards dismisses`() {
        assertTrue(shouldDismissSheet(offset = 60f, velocity = 1_000f, sheetHeight = height, density = 1f))
    }

    @Test
    fun `a slow release decides by distance`() {
        assertFalse(shouldDismissSheet(offset = 150f, velocity = 0f, sheetHeight = height, density = 1f))
        assertTrue(shouldDismissSheet(offset = 230f, velocity = 0f, sheetHeight = height, density = 1f))
    }

    @Test
    fun `flicking back up keeps the sheet even past the threshold`() {
        assertFalse(shouldDismissSheet(offset = 400f, velocity = -500f, sheetHeight = height, density = 1f))
    }

    @Test
    fun `a short sheet needs a proportionally short pull`() {
        // 40% of 200 = 80
        assertFalse(shouldDismissSheet(offset = 70f, velocity = 0f, sheetHeight = 200f, density = 1f))
        assertTrue(shouldDismissSheet(offset = 85f, velocity = 0f, sheetHeight = 200f, density = 1f))
    }

    @Test
    fun `thresholds scale with density`() {
        // 60dp flick at 1000dp/s on a 3x screen
        assertTrue(shouldDismissSheet(offset = 180f, velocity = 3_000f, sheetHeight = 1_800f, density = 3f))
        // 20dp at the same speed is still a twitch
        assertFalse(shouldDismissSheet(offset = 60f, velocity = 3_000f, sheetHeight = 1_800f, density = 3f))
    }

    @Test
    fun `a sheet at rest never dismisses`() {
        assertFalse(shouldDismissSheet(offset = 0f, velocity = 5_000f, sheetHeight = height, density = 1f))
    }
}
