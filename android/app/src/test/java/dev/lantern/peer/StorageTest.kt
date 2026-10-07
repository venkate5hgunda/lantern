package dev.lantern.peer

import android.content.Context
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StorageTest {
    private fun context(): Context = RuntimeEnvironment.getApplication()
    private fun store(context: Context = context()) = EventStore(context, "device-1", {})
    private fun jpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        check(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, output))
        output.toByteArray()
    }

    @Test fun `grant hashes survive restart but plaintext bearer does not`() {
        val context = context()
        val first = PeerState(context)
        val (token, grant) = first.grants.issue("viewer")
        first.saveGrants()
        val second = PeerState(context)
        assertEquals(first.id, second.id)
        assertEquals(grant.id, second.grants.authenticate("Bearer $token")?.id)
        assertFalse(context.getSharedPreferences("peer", Context.MODE_PRIVATE)
            .getString("grants", "")!!.contains(token))
        second.grants.revoke(grant.id)
        second.saveGrants()
        assertNull(PeerState(context).grants.authenticate("Bearer $token"))
    }

    @Test fun `approved origins and arming persist with no wildcard`() {
        val first = PeerState(context())
        first.saveOrigins(setOf("https://100.71.2.3:8443", "https://owner.example"))
        first.armed(true)
        val second = PeerState(context())
        assertTrue(second.armed())
        assertTrue(second.origins.accepts("https://100.71.2.3:8443"))
        assertFalse(second.origins.accepts("https://unexpected.example"))
        second.armed(false)
        assertFalse(PeerState(context()).armed())
    }

    @Test fun `snapshot record appears only after durable JPEG write and survives reopening`() {
        val context = context()
        val store = store(context)
        val image = jpeg()
        val created = store.add(image)
        val file = File(context.filesDir, "snapshots/${created.id}.jpg")
        assertTrue(file.isFile)
        assertArrayEquals(image, file.readBytes())
        assertEquals(created.id, store.list().single().id)
        assertEquals("manual", store.json(created).getString("trigger"))
        assertEquals("/api/v1/events/${created.id}/snapshot", store.json(created).getString("snapshotPath"))
        assertArrayEquals(image, store(context).snapshot(created.id))
        store.close()
    }

    @Test fun `invalid JPEG never leaves a row or partial media`() {
        val context = context()
        val store = store(context)
        listOf(byteArrayOf(), byteArrayOf(1, 2, 3, 4, 5), ByteArray(8 * 1024 * 1024 + 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { store.add(it) }
        }
        assertTrue(store.list().isEmpty())
        assertTrue(File(context.filesDir, "snapshots").listFiles().orEmpty().isEmpty())
        store.close()
    }
    @Test fun `directory sync failure cleans media without inserting metadata`() {
        val context = context()
        val store = EventStore(context, "device-1") { throw IllegalStateException("injected sync failure") }
        assertThrows(IllegalStateException::class.java) { store.add(jpeg()) }
        assertTrue(store.list().isEmpty())
        assertTrue(File(context.filesDir, "snapshots").listFiles().orEmpty().isEmpty())
        store.close()
    }

    @Test fun `snapshot IDs cannot be used as filesystem paths`() {
        val store = store()
        assertNull(store.snapshot("../peer"))
        assertNull(store.snapshot("not-a-uuid"))
        assertNull(store.snapshot("00000000-0000-0000-0000-000000000000"))
        store.close()
    }

    @Test fun `oldest snapshots evict after two hundred while listing only fifty`() {
        val context = context()
        val store = store(context)
        val image = jpeg()
        val created = (0 until 205).map { store.add(image) }
        assertEquals(50, store.list().size)
        assertEquals(created.last().id, store.list().first().id)
        assertNull(store.snapshot(created.first().id))
        assertArrayEquals(image, store.snapshot(created.last().id))
        assertEquals(200, store.list(300).size)
        assertEquals(200, File(context.filesDir, "snapshots").listFiles()!!.count { it.extension == "jpg" })
        store.close()
    }

    @Test fun `interrupted and orphan media are removed on recovery`() {
        val context = context()
        val store = store(context)
        val valid = store.add(jpeg())
        val directory = File(context.filesDir, "snapshots")
        File(directory, "orphan.jpg").writeBytes(jpeg())
        File(directory, "interrupted.partial").writeBytes(byteArrayOf(1, 2))
        store.pruneOrphans()
        assertFalse(File(directory, "orphan.jpg").exists())
        assertFalse(File(directory, "interrupted.partial").exists())
        assertNotNull(store.snapshot(valid.id))
        store.close()
    }
}
