package cl.zkage.app

import android.content.Context
import android.content.Intent
import cl.zkage.verifier.ChileCsca
import cl.zkage.verifier.CscaKey
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one proof the app can be generating, shared by [ProverService] (which runs it) and
 * the UI (which shows it). The chip data stays in this process: it is handed over here
 * instead of in an Intent, which would pass through the system.
 */
object ProofJob {
    sealed interface State {
        data object Idle : State
        data class Running(val startedAt: Long) : State
        data class Done(val proof: Proof, val seconds: Double, val csca: String) : State
        data class Failed(val message: String) : State
    }

    private class Input(val csca: CscaKey, val sod: ByteArray, val dg1: ByteArray)

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    @Volatile
    private var pending: Input? = null

    /** Starts proving in [ProverService]. Ignored while a proof is already running. */
    fun start(context: Context, csca: CscaKey, chip: ChipData) {
        if (state.value is State.Running) return
        pending = Input(csca, chip.sod, chip.dg1)
        mutableState.value = State.Running(System.currentTimeMillis())
        try {
            context.startForegroundService(Intent(context, ProverService::class.java))
        } catch (e: RuntimeException) {
            // e.g. ForegroundServiceStartNotAllowedException: never leave the UI stuck on Running.
            pending = null
            mutableState.value = State.Failed("Android no dejó iniciar el servicio: ${e.message}")
        }
    }

    /** Back to idle once the UI has shown the result. */
    fun reset() {
        if (state.value !is State.Running) mutableState.value = State.Idle
    }

    /** Blocking; called by [ProverService] on its worker thread. Returns the final state. */
    internal fun runPending(context: Context): State {
        val input = pending ?: return State.Failed("no hay datos de la cédula para probar").also { mutableState.value = it }
        pending = null
        val started = (state.value as? State.Running)?.startedAt ?: System.currentTimeMillis()
        val result = try {
            val proof = NativeProver.prove(input.csca.spki(), input.sod, input.dg1, LocalDate.now(ZoneOffset.UTC), 18)
            // No personal data in it: only the CSCA hash, the date and the threshold are public.
            File(context.filesDir, "age-proof.bin").writeBytes(proof.raw)
            val label = ChileCsca.keys.firstOrNull { it.spkiSha256().contentEquals(proof.claim.cscaSha256) }?.label
            State.Done(proof, (System.currentTimeMillis() - started) / 1000.0, label ?: "desconocida")
        } catch (e: Throwable) {
            State.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
        mutableState.value = result
        return result
    }
}
