package dev.lantern.peer

import org.junit.Assert.*
import org.junit.Test

class DisplayPoliciesTest {
    @Test fun `static scenes sensor noise and global exposure changes do not wake`() {
        val motion = SignificantMotion()
        assertFalse(motion.observe(ByteArray(768) { 60 }, 1000))
        assertFalse(motion.observe(ByteArray(768) { 64 }, 2000))
        assertFalse(motion.observe(ByteArray(768) { 120 }, 3000))
        assertFalse(motion.observe(ByteArray(768) { if (it % 2 == 0) 125 else 115 }, 4000))
    }
    @Test fun `substantial local movement requires consecutive observations`() {
        val motion = SignificantMotion()
        val first = ByteArray(768) { 60 }
        val moved = ByteArray(768) { if (it < 200) 120 else 60 }
        assertFalse(motion.observe(first, 1000))
        assertFalse(motion.observe(moved, 2000))
        assertTrue(motion.observe(first, 3000))
        assertFalse(motion.observe(first, 4000))
    }
    @Test fun `gaps and reset require a fresh baseline`() {
        val motion = SignificantMotion()
        val first = ByteArray(768) { 40 }
        val moved = ByteArray(768) { if (it < 200) 120 else 40 }
        motion.observe(first, 1000)
        motion.observe(moved, 2000)
        assertFalse(motion.observe(first, 10_000))
        motion.reset()
        assertFalse(motion.observe(moved, 11_000))
    }
    @Test fun `ambient timeout follows user and scene activity and re-dims`() {
        val policy = AmbientDisplayPolicy()
        policy.interact(1000)
        assertFalse(policy.ambient(30_999, 0))
        assertTrue(policy.ambient(31_000, 0))
        assertFalse(policy.ambient(32_000, 31_500))
        assertTrue(policy.ambient(61_500, 31_500))
        policy.interact(62_000)
        assertFalse(policy.ambient(62_000, 31_500))
    }
}
