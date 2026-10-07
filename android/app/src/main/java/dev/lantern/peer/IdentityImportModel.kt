package dev.lantern.peer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

internal sealed interface ImportState {
    data object Idle : ImportState
    data object Working : ImportState
    data class Finished(val message: Int, val imported: Boolean) : ImportState
}

internal class IdentityImportModel(application: Application) : AndroidViewModel(application) {
    private val executor = Executors.newSingleThreadExecutor()
    private val changes = MutableLiveData<ImportState>(ImportState.Idle)
    val status: LiveData<ImportState> = changes
    var pairingCode: String? = null
    var codeUntil = 0L
    var codeService = 0
    var grantsBeforeCode = emptySet<String>()

    fun acknowledge() {
        if (changes.value is ImportState.Finished) changes.value = ImportState.Idle
    }

    fun importFile(uri: Uri, password: CharArray) {
        if (changes.value == ImportState.Working) {
            password.fill('\u0000')
            return
        }
        changes.value = ImportState.Working
        executor.execute {
            var service: PeerService? = null
            var paused = false
            var imported = false
            var errorMessage = R.string.identity_read_failed
            var bytes: ByteArray? = null
            try {
                val payload = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 262_144)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } ?: error("Identity file unavailable")
                bytes = payload
                errorMessage = R.string.identity_failed
                service = PeerService.instance
                if (service != null) {
                    paused = true
                    check(service.pauseHttpsForIdentityChange())
                }
                val own = Identity.import(payload, password)
                imported = true
                val state = PeerState(getApplication())
                state.identity(true)
                val approved = (service?.approvedOrigins() ?: state.origins.list()) + own
                if (service != null) service.updateSettings(state.name(), approved)
                else state.saveOrigins(approved)
                val resumed = service?.resumeHttpsAfterIdentityChange() != false
                paused = !resumed
                changes.postValue(ImportState.Finished(
                    if (resumed) R.string.identity_ready_hint else R.string.identity_restart_failed, true))
            } catch (_: Exception) {
                val restored = !paused || service?.resumeHttpsAfterIdentityChange() == true
                Log.w("LanternSetup", "Identity setup failed; imported=$imported, restored=$restored")
                changes.postValue(ImportState.Finished(
                    if (!restored) R.string.identity_restore_failed
                    else if (imported) R.string.identity_restart_failed else errorMessage, imported))
            } finally {
                password.fill('\u0000')
                bytes?.fill(0)
            }
        }
    }

    override fun onCleared() {
        // Finish an in-flight Keystore/listener transaction rather than interrupting a paused service.
        executor.shutdown()
    }
}
