package dev.lantern.peer

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PoliciesTest {
    @Test fun `pairing expires and consumes exactly once`() {
        var now = 1_000L
        val codes = PairingPolicy(SecureRandom(), { now })
        val code = codes.generate()
        assertEquals(PairResult.INVALID, codes.redeem("999999".takeUnless { it == code } ?: "000000"))
        assertEquals(PairResult.OK, codes.redeem(code))
        assertEquals(PairResult.UNAVAILABLE, codes.redeem(code))
        codes.generate()
        now += 120_000
        assertEquals(PairResult.EXPIRED, codes.redeem(code))
    }
    @Test fun `pairing five failures are global and lock code`() {
        val codes = PairingPolicy()
        val code = codes.generate()
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(4) { assertEquals(PairResult.INVALID, codes.redeem(wrong)) }
        assertEquals(PairResult.LIMITED, codes.redeem(wrong))
        assertEquals(PairResult.LIMITED, codes.redeem(code))
        val replacement = codes.generate()
        assertEquals(PairResult.OK, codes.redeem(replacement))
    }
    @Test fun `bearer is hashed expires and revokes`() {
        var now = 1_000L
        val grants = Grants { now }
        val (token, grant) = grants.issue("My phone")
        assertEquals(43, token.length)
        assertFalse(grant.hash.contains(token))
        assertEquals(grant, grants.authenticate("Bearer $token"))
        assertNull(grants.authenticate(token))
        assertNull(grants.authenticate("Bearer ${token.dropLast(1)}x".takeUnless { it == "Bearer $token" } ?: "Bearer bad"))
        grants.revoke(grant.id)
        assertNull(grants.authenticate("Bearer $token"))
        val (second, added) = grants.issue("Second")
        assertEquals(now + 30L * 24 * 3600 * 1000, added.expires)
        now = added.expires
        assertNull(grants.authenticate("Bearer $second"))
    }
    @Test fun `origins require exact approved secure authority`() {
        val policy = Origins()
        policy.set(setOf("https://camera.mesh:8443", "https://pages.example"))
        assertTrue(policy.accepts(null))
        assertTrue(policy.accepts("https://camera.mesh:8443"))
        listOf("http://camera.mesh:8443", "https://camera.mesh", "https://camera.mesh:8443/",
            "https://camera.mesh:8443/path", "https://camera.mesh:8443?x=1",
            "https://attacker@camera.mesh:8443", "https://evil.example",
            "https://camera.mesh:8443.evil.example", "https://CAMERA.mesh:8443").forEach {
            assertFalse(it, policy.accepts(it))
        }
        assertNull(Origins.canonical("https://*.mesh:8443"))
    }
    @Test fun `sessions enforce owner capacity audio and deadline`() {
        var now = 100L
        val closed = mutableListOf<String>()
        val slots = Slots(now = { now }, monotonic = { now }, onExpire = { closed.add(it) })
        val first = slots.reserve("a", true)!!
        assertNull(slots.owned(first.id, "b"))
        assertNull(slots.heartbeat(first.id, "b"))
        assertFalse(slots.remove(first.id, "b"))
        assertNull(slots.reserve("b", true))
        val second = slots.reserve("b", false)!!
        assertNotNull(slots.reserve("c", false))
        assertNull(slots.reserve("d", false))
        now += 10_000
        assertEquals(now + 30_000, slots.heartbeat(first.id, "a")!!.expires)
        now += 20_000
        assertNull(slots.owned(second.id, "b"))
        assertTrue(closed.contains(second.id))
        assertNotNull(slots.owned(first.id, "a"))
        assertEquals(listOf(first.id), slots.removeOwner("a"))
        assertNotNull(slots.reserve("d", true))
    }
    @Test fun `concurrent session reservations never exceed global and audio caps`() {
        val slots = Slots()
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        try {
            val results = (0 until 32).map { index ->
                pool.submit<Slot?> {
                    start.await()
                    slots.reserve("viewer-$index", index % 2 == 0)
                }
            }
            start.countDown()
            val admitted = results.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(3, admitted.size)
            assertTrue(admitted.count { it.audio } <= 1)
            assertEquals(3, admitted.map { it.id }.toSet().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun `monotonic expiry survives backwards wall-clock changes`() {
        var wall = 1_000_000L
        var elapsed = 100L
        val closed = mutableListOf<String>()
        val slots = Slots({ wall }, { elapsed }, { closed.add(it) })
        val session = slots.reserve("viewer", false)!!
        wall -= 100_000
        elapsed += 30_000
        assertNull(slots.heartbeat(session.id, "viewer"))
        assertEquals(listOf(session.id), closed)
    }
    @Test fun `all owned sessions close on revocation and on disarm`() {
        val slots = Slots()
        val a = slots.reserve("owner", true)!!
        val b = slots.reserve("other", false)!!
        assertEquals(listOf(a.id), slots.removeOwner("owner"))
        assertNull(slots.owned(a.id, "owner"))
        assertEquals(listOf(b.id), slots.clear())
        assertNull(slots.owned(b.id, "other"))
    }
    @Test fun `snapshot writes are exclusive and at least two seconds apart`() {
        var elapsed = 0L
        val gate = SnapshotGate { elapsed }
        assertEquals(SnapshotAdmission.READY, gate.enter())
        assertEquals(SnapshotAdmission.BUSY, gate.enter())
        gate.exit()
        elapsed = 1_999
        assertEquals(SnapshotAdmission.RATE_LIMITED, gate.enter())
        elapsed = 2_000
        assertEquals(SnapshotAdmission.READY, gate.enter())
        gate.exit()
    }
    @Test fun `failed 201 delivery releases owned slot and peer exactly once`() {
        val slots = Slots()
        val audio = slots.reserve("viewer", true)!!
        val closed = mutableListOf<String>()
        val reply = Response(201, onDeliveryFailure = SessionDelivery(slots, audio, closed::add)::failed)
        reply.onDeliveryFailure!!.invoke()
        reply.onDeliveryFailure.invoke()
        assertNull(slots.owned(audio.id, "viewer"))
        assertEquals(listOf(audio.id), closed)
        assertNotNull(slots.reserve("next", true))
    }
    @Test fun `bracketed IPv6 origin canonicalizes without losing brackets`() {
        val origins = Origins()
        origins.set(setOf("https://[::1]:8443", "https://[2001:db8::3]:8443"))
        assertEquals("https://[::1]:8443", Origins.canonical("https://[::1]:8443"))
        assertTrue(origins.accepts("https://[2001:db8::3]:8443"))
        assertFalse(origins.accepts("https://[2001:db8::4]:8443"))
    }
    @Test fun `ICE candidate input has strict size type and control bounds`() {
        val valid = IceInput.validate("candidate:1 1 udp 42 100.75.4.3 40000 typ host", "0", 0)
        assertNotNull(valid)
        assertEquals(null, IceInput.validate("", "0", 0))
        assertNotNull(IceInput.validate("a".repeat(4096), null, 1))
        assertNull(IceInput.validate("a".repeat(4097), null, 1))
        assertNotNull(IceInput.validate("candidate:x", "x".repeat(64), 1))
        assertNull(IceInput.validate("candidate:x", "x".repeat(65), 1))
        listOf(-1, 2, 0L, 0.0, "0").forEach { assertNull(IceInput.validate("candidate:x", null, it)) }
        assertNull(IceInput.validate(123, null, 0))
        assertNull(IceInput.validate("candidate:x", 0, 0))
        assertNull(IceInput.validate("candidate:x\r\nother", null, 0))
    }
}
