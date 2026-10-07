package dev.lantern.peer

internal enum class SetupStep { NAME, IDENTITY, PERMISSIONS, START, PAIR }

internal data class SetupReadiness(
    val named: Boolean,
    val identity: Boolean,
    val permissions: Boolean,
    val running: Boolean
) {
    fun firstMissing(): SetupStep = when {
        !named -> SetupStep.NAME
        !identity -> SetupStep.IDENTITY
        !permissions -> SetupStep.PERMISSIONS
        !running -> SetupStep.START
        else -> SetupStep.PAIR
    }

    fun accessible(step: SetupStep): SetupStep =
        if (step.ordinal > firstMissing().ordinal) firstMissing() else step
}
