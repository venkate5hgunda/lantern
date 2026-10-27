package dev.lantern.peer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EvidenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0, 0xff.toByte(), 0xd9.toByte())

    @Test fun `age maintenance removes expired evidence without another capture`() {
        var clock = 100_000L
        val store = EventStore(context, "camera", SnapshotRetention(maxAgeMs = 1000), { clock }, { Long.MAX_VALUE }, {})
        val old = store.add(image)
        clock += 999
        store.maintain()
        assertNotNull(store.snapshot(old.id))
        clock++
        store.maintain()
        assertNull(store.snapshot(old.id))
        assertEquals(0, store.status().getInt("items"))
        store.close()
    }
    @Test fun `byte quota evicts oldest and scheduled trigger survives reopening`() {
        var clock = 100_000L
        val policy = SnapshotRetention(maxBytes = 12)
        val store = EventStore(context, "camera", policy, { clock++ }, { Long.MAX_VALUE }, {})
        val old = store.add(image)
        val retained = store.add(image)
        val newest = store.add(image, "scheduled")
        assertNull(store.snapshot(old.id))
        assertNotNull(store.snapshot(retained.id))
        assertEquals(12, store.status().getLong("bytes"))
        store.close()
        EventStore(context, "camera", syncDirectory = {}).use {
            assertEquals("scheduled", it.list().first().trigger)
            assertEquals("scheduled", it.json(newest).getString("trigger"))
        }
    }
    @Test fun `disk reserve rejects admission without publishing partial evidence`() {
        val store = EventStore(context, "camera", availableBytes = { 0 }, syncDirectory = {})
        assertThrows(IllegalStateException::class.java) { store.add(image) }
        assertTrue(store.list().isEmpty())
        assertTrue(File(context.filesDir, "snapshots").listFiles().orEmpty().isEmpty())
        store.close()
    }
    @Test fun `populated version one database migrates without losing images`() {
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val directory = File(context.filesDir, "snapshots").also { it.mkdirs() }
        File(directory, "$id.jpg").writeBytes(image)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("events.db").also { it.parentFile!!.mkdirs() }, null).use {
            it.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, created INTEGER NOT NULL)")
            it.execSQL("CREATE INDEX events_recent ON events(created DESC)")
            it.execSQL("INSERT INTO events VALUES (?, ?)", arrayOf(id, System.currentTimeMillis()))
            it.version = 1
        }
        EventStore(context, "camera", syncDirectory = {}).use {
            assertArrayEquals(image, it.snapshot(id))
            assertEquals("manual", it.list().single().trigger)
            assertEquals(image.size.toLong(), it.list().single().bytes)
        }
    }
    @Test fun `missing media is reconciled and automatic capture defaults off`() {
        val store = EventStore(context, "camera", syncDirectory = {})
        val event = store.add(image)
        File(context.filesDir, "snapshots/${event.id}.jpg").delete()
        store.pruneOrphans()
        assertTrue(store.list().isEmpty())
        assertFalse(PeerState(context).automaticSnapshots())
        PeerState(context).automaticSnapshots(true)
        assertTrue(PeerState(context).automaticSnapshots())
        store.close()
    }
    @Test fun `doorbell persists bounds acknowledges idempotently and rate limits restarts`() {
        var clock = 100_000L
        var monotonic = 0L
        val first = DoorbellStore(context, { clock }, { monotonic })
        val initial = first.ring()!!
        assertNull(first.ring())
        first.close()
        val restarted = DoorbellStore(context, { clock }, { monotonic })
        assertNull(restarted.ring())
        clock += 5000; monotonic += 5000
        assertNotNull(restarted.ring())
        assertTrue(restarted.acknowledge(initial.id))
        assertTrue(restarted.acknowledge(initial.id))
        assertFalse(restarted.acknowledge("missing"))
        assertTrue(restarted.list().last().acknowledged)
        repeat(55) { clock += 5000; monotonic += 5000; assertNotNull(restarted.ring()) }
        assertEquals(50, restarted.list().size)
        assertFalse(restarted.list().any { it.id == initial.id })
        restarted.close()
    }
}
