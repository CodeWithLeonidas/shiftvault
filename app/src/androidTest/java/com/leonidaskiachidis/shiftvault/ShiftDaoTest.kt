package com.leonidaskiachidis.shiftvault

/*
 * The database on a real device, in memory only — these tests never touch
 * the app's actual data, so they are safe to run on a personal phone.
 */

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ShiftDaoTest {

    private lateinit var db: ShiftDatabase
    private lateinit var dao: ShiftDao

    private val day = LocalDate.of(2026, 9, 1)
    private fun shift(name: String, jobId: String = JobProfile.DEFAULT_ID) = Shift(
        name = name, startTime = "09:00", endTime = "17:00",
        colorValue = 0xFF3F7DDE, hourlyRate = 10.0, jobId = jobId
    )

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ShiftDatabase::class.java).build()
        dao = db.dao()
    }

    @After
    fun close() = db.close()

    @Test
    fun calendar_readsBackInDateThenPositionOrder() = runBlocking {
        val later = day.plusDays(1)
        dao.insertCalendar(calendarRows(later, listOf(shift("C"))))
        dao.insertCalendar(calendarRows(day, listOf(shift("A"), shift("B"))))

        val map = dao.calendar().toShiftMap()
        assertEquals(listOf(day, later), map.keys.toList())
        assertEquals(listOf("A", "B"), map.getValue(day).map { it.name })
    }

    @Test
    fun deleteDay_removesOnlyThatDay() = runBlocking {
        dao.insertCalendar(calendarRows(day, listOf(shift("A"))))
        dao.insertCalendar(calendarRows(day.plusDays(1), listOf(shift("B"))))

        dao.deleteDay(day.toString())

        assertEquals(setOf(day.plusDays(1)), dao.calendar().toShiftMap().keys)
    }

    @Test
    fun deleteForJob_leavesOtherProfilesAlone() = runBlocking {
        dao.insertCalendar(calendarRows(day, listOf(shift("Mine", "a"), shift("Theirs", "b"))))
        dao.insertSaved(savedRows(listOf(shift("Mine", "a"), shift("Theirs", "b"))))

        dao.deleteCalendarForJob("a")
        dao.deleteSavedForJob("a")

        assertEquals(listOf("Theirs"), dao.calendar().map { it.shift.name })
        assertEquals(listOf("Theirs"), dao.saved().map { it.shift.name })
    }

    @Test
    fun failedTransaction_leavesNothingBehind() = runBlocking {
        dao.insertCalendar(calendarRows(day, listOf(shift("Kept"))))

        runCatching {
            db.withTransaction {
                dao.clearCalendar()
                dao.putMeta(MetaEntity(ShiftDatabase.META_IMPORTED_FROM_DATASTORE, "1"))
                error("interrupted")
            }
        }

        assertEquals(listOf("Kept"), dao.calendar().map { it.shift.name })
        assertNull(dao.meta(ShiftDatabase.META_IMPORTED_FROM_DATASTORE))
    }

    @Test
    fun observeCalendar_emitsAfterWrites() = runBlocking {
        dao.insertCalendar(calendarRows(day, listOf(shift("A"))))
        assertEquals(listOf("A"), dao.observeCalendar().first().map { it.shift.name })
    }
}
