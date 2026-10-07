package dev.lantern.peer

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnerSetupTest {
    @Test fun `setup always returns the earliest missing prerequisite`() {
        assertEquals(SetupStep.NAME, SetupReadiness(false, false, false, false).firstMissing())
        assertEquals(SetupStep.IDENTITY, SetupReadiness(true, false, true, true).firstMissing())
        assertEquals(SetupStep.PERMISSIONS, SetupReadiness(true, true, false, true).firstMissing())
        assertEquals(SetupStep.START, SetupReadiness(true, true, true, false).firstMissing())
        assertEquals(SetupStep.PAIR, SetupReadiness(true, true, true, true).firstMissing())
    }

    @Test fun `permissions or identity loss cannot skip back into pairing`() {
        assertEquals(SetupStep.PERMISSIONS, SetupReadiness(true, true, false, true).accessible(SetupStep.PAIR))
        assertEquals(SetupStep.IDENTITY, SetupReadiness(true, false, true, true).accessible(SetupStep.START))
    }

    @Test fun `completed prerequisites may be revisited without changing later readiness`() {
        val ready = SetupReadiness(true, true, true, true)
        SetupStep.entries.forEach { assertEquals(it, ready.accessible(it)) }
    }
}
