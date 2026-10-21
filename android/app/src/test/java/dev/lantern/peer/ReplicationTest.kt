package dev.lantern.peer

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ReplicationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val source = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val receiver = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private fun image(size: Int = 100): ByteArray = ByteArray(size) { 5 }.apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte(); this[2] = 0xff.toByte(); this[size-2] = 0xff.toByte(); this[size-1] = 0xd9.toByte() }
    private fun store(namespace: String = "", clock: () -> Long = System::currentTimeMillis, quota: Long = 256L * 1024 * 1024): BackupStore = BackupStore(context, { it }, { it }, clock, { Long.MAX_VALUE }, quota, {}, namespace)
    private fun manifest(bytes: ByteArray, clock: Long = System.currentTimeMillis()) = ReplicaManifest(source, "cccccccc-cccc-4ccc-8ccc-cccccccccccc", clock, "manual", null, bytes.size, mediaHash(bytes))
    @Test fun `encrypted evidence is resumable idempotent and recoverable with source offline`() {
        val store = store(); val plain = image(70_000); val m = manifest(plain)
        val credentials = store.issue(source); val grant = store.authenticate("Bearer ${credentials.first}")!!
        val encrypted = ReplicaCrypto.encrypt(plain, m, grant.key)
        assertFalse(encrypted.contentEquals(plain))
        assertEquals(0, store.begin(m, source))
        assertEquals(32_768, store.chunk(m.id, source, 0, encrypted.copyOfRange(0, 32_768)))
        assertEquals(32_768, store.chunk(m.id, source, 0, encrypted.copyOfRange(0, 32_768)))
        assertThrows(IllegalArgumentException::class.java) { store.chunk(m.id, source, 0, ByteArray(100)) }
        store.close()
        val reopened = store()
        assertEquals(32_768, reopened.begin(m, source))
        var offset = 32_768
        while (offset < encrypted.size) { val end = minOf(encrypted.size, offset + 32_768); offset = reopened.chunk(m.id, source, offset, encrypted.copyOfRange(offset, end)) }
        assertEquals(m.sha256, reopened.commit(m.id, grant))
        assertEquals(m.sha256, reopened.commit(m.id, grant))
        assertArrayEquals(plain, reopened.restore(m.id))
        reopened.unlink(source)
        assertNull(reopened.authenticate("Bearer ${credentials.first}"))
        assertArrayEquals(plain, reopened.restore(m.id)) // Revocation preserves existing restore keys.
        reopened.close()
    }
    @Test fun `metadata tampering wrong keys quota and cross-camera uploads are rejected`() {
        val plain = image(); val m = manifest(plain); val key = ByteArray(32) { 7 }
        val encrypted = ReplicaCrypto.encrypt(plain, m, key)
        assertThrows(Exception::class.java) { ReplicaCrypto.decrypt(encrypted, m.copy(created = m.created + 1), key) }
        assertThrows(Exception::class.java) { ReplicaCrypto.decrypt(encrypted, m, ByteArray(32) { 8 }) }
        val store = store(quota = 100)
        assertThrows(IllegalArgumentException::class.java) { store.begin(m, receiver) }
        assertThrows(IllegalStateException::class.java) { store.begin(m, source) }
        assertTrue(store.history().length() == 0)
        store.close()
    }
    @Test fun `full replica quota keeps newer evidence against older retries`() {
        val store = store(quota = 128); val plain = image(); val now = System.currentTimeMillis()
        val current = manifest(plain, now - 1000); val issued = store.issue(source)
        val grant = store.authenticate("Bearer ${issued.first}")!!
        fun save(m: ReplicaManifest) {
            val bytes = ReplicaCrypto.encrypt(plain, m, grant.key)
            store.begin(m, source); store.chunk(m.id, source, 0, bytes); store.commit(m.id, grant)
        }
        save(current)
        val older = current.copy(eventId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd", created = now - 2000)
        assertThrows(IllegalStateException::class.java) { store.begin(older, source) }
        assertArrayEquals(plain, store.restore(current.id))
        val newer = older.copy(created = now - 500)
        save(newer)
        assertNull(store.restore(current.id))
        assertArrayEquals(plain, store.restore(newer.id))
        store.close()
    }
    @Test fun `corrupted replica is removed from inventory so reconciliation can repair it`() {
        val store = store(); val plain = image(); val m = manifest(plain); val issued = store.issue(source)
        val grant = store.authenticate("Bearer ${issued.first}")!!; val bytes = ReplicaCrypto.encrypt(plain, m, grant.key)
        store.begin(m, source); store.chunk(m.id, source, 0, bytes); store.commit(m.id, grant)
        File(context.filesDir, "replicas/${m.id}.bin").writeBytes(ByteArray(bytes.size))
        assertEquals(0, store.inventory(source).length())
        assertNull(store.restore(m.id))
        assertEquals(0, store.begin(m, source))
        store.chunk(m.id, source, 0, bytes); store.commit(m.id, grant)
        assertArrayEquals(plain, store.restore(m.id))
        store.close()
    }
    @Test fun `sync retries the same durable ciphertext after an interrupted commit and verifies receipt`() {
        var clock = System.currentTimeMillis()
        val sourceStore = store("-sender", { clock }); val receiverStore = store("-receiver", { clock }, quota = 70_028)
        val state = PeerState(context)
        val events = EventStore(context, state.id, now = { clock }, availableBytes = { Long.MAX_VALUE }, syncDirectory = {})
        val plain = image(70_000); val event = events.add(plain)
        val issued = receiverStore.issue(state.id)
        sourceStore.target(receiver, "https://other.example:8443", "a".repeat(64), issued.first, issued.second)
        var fail = true
        val calls: (BackupTarget) -> (String, JSONObject?) -> JSONObject = { target ->
            val grant = receiverStore.authenticate("Bearer ${target.token}")!!
            val call: (String, JSONObject?) -> JSONObject = { path, body ->
                when {
                    path.endsWith("/inventory") -> JSONObject().put("replicas", receiverStore.inventory(state.id))
                    path.endsWith("/begin") -> JSONObject().put("received", receiverStore.begin(ReplicaManifest.parse(body!!), state.id))
                    path.endsWith("/chunk") -> JSONObject().put("received", receiverStore.chunk(path.split('/')[4], state.id, body!!.getInt("offset"), WebPushCrypto.decode(body.getString("data"))))
                    else -> { if (fail) { fail = false; error("injected lost connection") }; JSONObject().put("sha256", receiverStore.commit(path.split('/')[4], grant)) }
                }
            }
            call
        }
        val sync = PeerBackup(sourceStore, events, state, { true }, calls, { clock })
        sync.sync()
        assertNotNull(sourceStore.pendingUpload(receiver))
        clock += 7000; sync.sync()
        assertNull(sourceStore.pendingUpload(receiver))
        val id = ReplicaManifest(state.id, event.id, event.created, event.trigger, null, plain.size, mediaHash(plain)).id
        assertArrayEquals(plain, receiverStore.restore(id))
        assertEquals(1, sourceStore.status(1).getJSONArray("targets").getJSONObject(0).getInt("verified"))
        clock += 20_000
        val newer = events.add(plain)
        sync.sync()
        // Only the newest image fits: receipts for evicted copies must not
        // inflate the verified counter after this transfer batch.
        val status = sourceStore.status(2).getJSONArray("targets").getJSONObject(0)
        assertEquals(1, status.getInt("verified"))
        assertEquals(1, status.getInt("pending"))
        assertNull(receiverStore.restore(id))
        assertEquals(newer.id, receiverStore.history().getJSONObject(0).getJSONObject("evidence").getString("eventId"))
        sourceStore.close(); receiverStore.close(); events.close()
    }
}
