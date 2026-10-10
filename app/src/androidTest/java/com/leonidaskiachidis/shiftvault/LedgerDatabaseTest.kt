package com.leonidaskiachidis.shiftvault

/*
 * The ledger tables on a real device. The migration test builds a version-1
 * database from the exported schema in app/schemas, upgrades it, and checks
 * that every shift came through; the DAO tests use an in-memory database.
 * Neither touches the app's actual data.
 */

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerDatabaseTest {

    private val dbName = "ledger-migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ShiftDatabase::class.java
    )

    @Test
    fun migration1To2_keepsEveryShift_andAddsTheLedger() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO calendar_shifts (date, position, name, startTime, endTime, colorValue, " +
                    "hourlyRate, payMultiplier, bonus, breakMinutes, paidBreak, dayType, paidLeaveHours, " +
                    "location, jobId, notes, iconName) VALUES ('2026-10-05', 0, 'Morning', '09:00', '17:00', " +
                    "0, 12.5, 1.0, 0.0, 30, 0, 'WORKED', 8.0, '', 'default', '', NULL)"
            )
            close()
        }

        // Validates the upgraded schema against the exported one as well.
        val db = helper.runMigrationsAndValidate(dbName, 3, true)

        db.query("SELECT name, hourlyRate FROM calendar_shifts").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Morning", c.getString(0))
            assertEquals(12.5, c.getDouble(1), 1e-9)
        }
        db.execSQL(
            "INSERT INTO earnings (jobId, type, description, amountCents, periodStart, periodEnd, createdAt) " +
                "VALUES ('default', 'SALARY', '', 100000, '2026-10-01', '2026-10-31', 1)"
        )
        db.query("SELECT COUNT(*) FROM earnings").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migration2To3_keepsTheLedger_andAddsHours() {
        val name = "ledger-migration-2-3.db"
        helper.createDatabase(name, 2).apply {
            execSQL(
                "INSERT INTO earnings (id, jobId, type, description, amountCents, periodStart, periodEnd, createdAt) " +
                    "VALUES (1, 'default', 'SALARY', '', 130000, '2026-10-01', '2026-10-31', 1)"
            )
            execSQL("INSERT INTO payments (earningId, amountCents, date, note, createdAt) VALUES (1, 127500, '2026-11-01', '', 2)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 3, true)

        db.query("SELECT amountCents, hours, shortfallAccepted FROM earnings").use { c ->
            c.moveToFirst()
            assertEquals(130000L, c.getLong(0))
            assertTrue(c.isNull(1))
            assertEquals(0, c.getInt(2))
        }
        db.query("SELECT amountCents, hoursPaid FROM payments").use { c ->
            c.moveToFirst()
            assertEquals(127500L, c.getLong(0))
            assertTrue(c.isNull(1))
        }
        db.close()
    }

    private fun inMemory(): ShiftDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ShiftDatabase::class.java
        ).build()

    private fun earning(jobId: String, cents: Long) = Earning(
        jobId = jobId, type = EarningType.SALARY, amountCents = cents,
        periodStart = "2026-10-01", periodEnd = "2026-10-31"
    ).toEntity()

    @Test
    fun deletingAnEarning_deletesItsPaymentsAndNothingElse() = runBlocking {
        val db = inMemory()
        val dao = db.ledger()
        val a = dao.insertEarning(earning("job", 10_000))
        val b = dao.insertEarning(earning("job", 5_000))
        dao.insertPayment(Payment(earningId = a, amountCents = 4_000, date = "2026-11-01").toEntity())
        dao.insertPayment(Payment(earningId = b, amountCents = 1_000, date = "2026-11-01").toEntity())

        dao.deleteEarning(a)

        assertNull(dao.earning(a))
        assertEquals(0L, dao.paidCents(a))
        assertEquals(1_000L, dao.paidCents(b))
        db.close()
    }

    @Test
    fun deletingAJobsLedger_leavesOtherJobsAlone() = runBlocking {
        val db = inMemory()
        val dao = db.ledger()
        val mine = dao.insertEarning(earning("mine", 10_000))
        val theirs = dao.insertEarning(earning("theirs", 10_000))
        dao.insertPayment(Payment(earningId = mine, amountCents = 2_000, date = "2026-11-01").toEntity())
        dao.insertPayment(Payment(earningId = theirs, amountCents = 3_000, date = "2026-11-01").toEntity())

        dao.deletePaymentsForJob("mine")
        dao.deleteEarningsForJob("mine")

        assertEquals(listOf(theirs), dao.earnings().map { it.id })
        assertEquals(listOf(3_000L), dao.payments().map { it.amountCents })
        db.close()
    }
}
