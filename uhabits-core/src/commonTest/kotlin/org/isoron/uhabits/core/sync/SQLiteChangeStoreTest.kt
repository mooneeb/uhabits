package org.isoron.uhabits.core.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SQLiteChangeStoreTest {
    @Test
    fun reorderPersistsTheVisibleOrderWhenDownloadedPositionsTie() = runTest {
        val db = BaseUnitTest.buildMemoryDatabase()
        try {
            val store = SQLiteChangeStore(db)
            val factory = SQLModelFactory(db, store)
            val habits = factory.buildHabitList()
            val first = factory.buildHabit().apply { name = "First" }
            val second = factory.buildHabit().apply { name = "Second" }
            habits.add(first)
            habits.add(second)
            store.connectAccount("account")
            store.merge(store.history().edit("browser", "tie", mapOf("habit:${second.uuid}:position" to JsonPrimitive(0))))
            (habits as org.isoron.uhabits.core.models.sqlite.SQLiteHabitList).reload()
            val before = habits.toList()
            habits.reorder(before[0], before[1])
            val reopened = factory.buildHabitList().toList()
            assertEquals(before.reversed().map { it.uuid }, reopened.map { it.uuid })
            assertEquals(listOf(0, 1), reopened.map { it.position })
        } finally {
            db.close()
        }
    }

    @Test
    fun rejectedReorderDoesNotChangeDurableOrderOrPendingHistory() = runTest {
        val db = BaseUnitTest.buildMemoryDatabase()
        try {
            val store = SQLiteChangeStore(db)
            val factory = SQLModelFactory(db, store)
            val habits = factory.buildHabitList()
            val first = factory.buildHabit().apply { name = "First" }
            val second = factory.buildHabit().apply { name = "Second" }
            habits.add(first)
            habits.add(second)
            val before = store.history()
            habits.primaryOrder = HabitList.Order.BY_NAME_ASC
            assertFailsWith<IllegalStateException> { habits.reorder(first, second) }
            assertEquals(before, store.history())
            habits.primaryOrder = HabitList.Order.BY_POSITION
            assertFailsWith<IllegalArgumentException> { habits.reorder(first, factory.buildHabit()) }
            assertEquals(before, store.history())
            assertEquals(listOf(first.uuid, second.uuid), factory.buildHabitList().map { it.uuid })
        } finally {
            db.close()
        }
    }

    @Test
    fun nativeHabitAndDatedNotesReopenWithTheirPendingChanges() = runTest {
        val db = BaseUnitTest.buildMemoryDatabase()
        try {
            val store = SQLiteChangeStore(db)
            val factory = SQLModelFactory(db, store)
            val habits = factory.buildHabitList()
            val habit = factory.buildHabit().apply { name = "Walk" }
            habits.add(habit)
            habit.originalEntries.add(Entry(LocalDate(2026, 10, 1), 12345, "Native — آزمائش"))
            val reopened = SQLiteChangeStore(db)
            val reopenedHabit = SQLModelFactory(db, reopened).buildHabitList().getByUUID(habit.uuid)!!
            assertEquals("Walk", reopenedHabit.name)
            assertEquals(12345, reopenedHabit.originalEntries.get(LocalDate(2026, 10, 1)).value)
            assertEquals(JsonPrimitive("Walk"), reopened.history().value("habit:${habit.uuid}:name"))
            assertEquals("Native — آزمائش", RegisterValues.entry(reopened.history().value("entry:${habit.uuid}:2026-10-01")!!).notes)
            assertEquals(true, reopened.pendingCount() > 0)
        } finally {
            db.close()
        }
    }

    @Test
    fun rejectedConflictEditsRollBackNativeRecordsAndPendingHistory() = runTest {
        val db = BaseUnitTest.buildMemoryDatabase()
        try {
            val store = SQLiteChangeStore(db)
            val factory = SQLModelFactory(db, store)
            val habits = factory.buildHabitList()
            val habit = factory.buildHabit().apply { name = "Walk" }
            habits.add(habit)
            store.connectAccount("account")
            val base = store.history()
            val key = "entry:${habit.uuid}:2026-10-01"
            val android = base.edit("a", "a", mapOf(key to kotlinx.serialization.json.Json.parseToJsonElement("""{"value":2,"notes":"A"}""")))
            val browser = base.edit("b", "b", mapOf(key to kotlinx.serialization.json.Json.parseToJsonElement("""{"value":0,"notes":"B"}""")))
            store.merge(android.merge(browser))
            val before = store.history()
            assertFailsWith<IllegalArgumentException> { habit.originalEntries.add(Entry(LocalDate(2026, 10, 1), 2, "Overwrite")) }
            assertEquals(before, store.history())
            val reopened = SQLModelFactory(db, store).buildHabitList().getByUUID(habit.uuid)!!
            assertEquals(-1, reopened.originalEntries.get(LocalDate(2026, 10, 1)).value)
            assertEquals(1, store.pendingCount())
        } finally {
            db.close()
        }
    }

    @Test
    fun rejectedDeletionPreservesNativeEntriesAndPendingHistory() = runTest {
        val db = BaseUnitTest.buildMemoryDatabase()
        try {
            val store = SQLiteChangeStore(db)
            val factory = SQLModelFactory(db, store)
            val habits = factory.buildHabitList()
            val habit = factory.buildHabit().apply { name = "Walk" }
            habits.add(habit)
            val date = LocalDate(2026, 10, 1)
            habit.originalEntries.add(Entry(date, 12345, "Retain this dated note"))
            store.connectAccount("account")
            val base = store.history()
            val key = "habit:${habit.uuid}:name"
            store.merge(
                base.edit("a", "a", mapOf(key to JsonPrimitive("A")))
                    .merge(base.edit("b", "b", mapOf(key to JsonPrimitive("B"))))
            )
            val other = factory.buildHabit().apply { name = "Unconflicted habit" }
            habits.add(other)
            val before = store.history()
            assertFailsWith<IllegalArgumentException> { habits.remove(listOf(other, habit)) }
            assertEquals("Unconflicted habit", SQLModelFactory(db, store).buildHabitList().getByUUID(other.uuid)!!.name)
            assertEquals(before, store.history())
            val reopened = SQLModelFactory(db, store).buildHabitList().getByUUID(habit.uuid)!!
            assertEquals(Entry(date, 12345, "Retain this dated note"), reopened.originalEntries.get(date))
        } finally {
            db.close()
        }
    }

    @Test
    fun successfulDeletionRetainsRecoverableEntryRevisions() = runTest {
        val db = BaseUnitTest.buildMemoryDatabase()
        try {
            val store = SQLiteChangeStore(db)
            val factory = SQLModelFactory(db, store)
            val habits = factory.buildHabitList()
            val habit = factory.buildHabit().apply { name = "Walk" }
            habits.add(habit)
            habit.originalEntries.add(Entry(LocalDate(2026, 10, 1), 12345, "Recoverable note"))
            habits.remove(habit)
            assertEquals(12345, RegisterValues.entry(store.history().value("entry:${habit.uuid}:2026-10-01")!!).value)
            assertEquals(JsonPrimitive(true), store.history().value("habit:${habit.uuid}:deleted"))
        } finally {
            db.close()
        }
    }

    @Test
    fun downloadedBrowserEditsAppearInNativeHabitModels() = runTest {
        val db = BaseUnitTest.buildMemoryDatabase()
        try {
            val store = SQLiteChangeStore(db)
            val factory = SQLModelFactory(db, store)
            val habits = factory.buildHabitList()
            val habit = factory.buildHabit().apply { name = "Walk" }
            habits.add(habit)
            store.connectAccount("account")
            val browser = store.history().edit("browser", "rename", mapOf("habit:${habit.uuid}:name" to JsonPrimitive("Morning walk")))
            store.merge(browser)
            (habits as org.isoron.uhabits.core.models.sqlite.SQLiteHabitList).reload()
            val reopened = habits.getByUUID(habit.uuid)!!
            assertSame(habit, reopened, "Open screens and cached rows must retain their habit identity")
            assertEquals("Morning walk", reopened.name)
            assertEquals(1, store.pendingCount())
        } finally {
            db.close()
        }
    }
}
