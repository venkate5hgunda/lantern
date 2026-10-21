package dev.lantern.peer

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NotificationsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun subscription(endpoint: String) = JSONObject().put("endpoint", endpoint).put("keys", JSONObject()
        .put("p256dh", WebPushCrypto.encode(WebPushCrypto.publicBytes(WebPushCrypto.generate().public))).put("auth", WebPushCrypto.encode(ByteArray(16) { 3 })))
    @Test fun `event fanout targets all active registered viewers and survives restart`() {
        val store = PushStore(context, { it }, { it })
        store.subscribe("viewer-a", subscription("https://fcm.googleapis.com/fcm/send/a"))
        store.subscribe("viewer-b", subscription("https://updates.push.services.mozilla.com/wpush/v2/b"))
        store.enqueue("event-1", JSONObject().put("type", "person"), setOf("viewer-a", "viewer-b"))
        store.enqueue("event-1", JSONObject().put("type", "person"), setOf("viewer-a", "viewer-b"))
        assertEquals(2, store.due().size)
        store.close()
        val reopened = PushStore(context, { it }, { it })
        assertEquals(2, reopened.due().size)
        reopened.prune(setOf("viewer-b"))
        assertEquals("viewer-b", reopened.due().single().grant)
        assertNull(reopened.subscription("viewer-a"))
        reopened.complete(reopened.due().single())
        assertTrue(reopened.due().isEmpty())
        reopened.close()
    }
    @Test fun `retry backoff and expiry never report queued alerts as delivered`() {
        var clock = 100_000L
        val store = PushStore(context, { it }, { it }, { clock })
        store.subscribe("viewer", subscription("https://fcm.googleapis.com/fcm/send/a"))
        store.enqueue("event", JSONObject().put("type", "doorbell"), setOf("viewer"))
        val job = store.due().single(); store.retry(job)
        assertEquals(1, store.status("viewer").getInt("pending"))
        assertTrue(store.due().isEmpty())
        clock += 15_000; assertEquals(1, store.due().size)
        clock += 3600_000; store.prune(setOf("viewer"))
        assertEquals(0, store.status("viewer").getInt("pending"))
        store.close()
    }
    @Test fun `presence debounce cooldown and missing observations do not imply identity`() {
        var clock = 100_000L
        val policy = PresencePolicy { clock }
        assertNull(policy.observe(listOf(0.8f)))
        assertNotNull(policy.observe(listOf(0.85f, 0.9f)))
        assertTrue(policy.present); assertEquals(2, policy.people)
        assertNull(policy.observe(listOf(0.8f)))
        repeat(3) { policy.observe(emptyList()) }
        assertFalse(policy.present)
        clock += 30_000
        assertNull(policy.observe(listOf(0.8f)))
        assertNotNull(policy.observe(listOf(0.8f)))
        assertEquals(2, policy.peakPeople)
        policy.reset(); assertFalse(policy.present)
    }
    @Test fun `person evidence retains model confidence after restart`() {
        val store = EventStore(context, "camera", availableBytes = { Long.MAX_VALUE }, syncDirectory = {})
        val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0, 0xff.toByte(), 0xd9.toByte())
        val event = store.add(image, "person", 0.81)
        assertEquals(0.81, store.json(event).getDouble("confidence"), 0.001)
        assertThrows(IllegalArgumentException::class.java) { store.add(image, "person", null) }
        store.close()
        EventStore(context, "camera", syncDirectory = {}).use { assertEquals(0.81, it.list().single().confidence!!, 0.001) }
    }
}
