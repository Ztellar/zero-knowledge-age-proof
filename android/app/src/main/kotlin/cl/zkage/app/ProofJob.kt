package cl.zkage.app

import android.content.Context
import android.content.Intent
import cl.zkage.verifier.ChileCsca
import cl.zkage.verifier.CscaKey
import cl.zkage.verifier.MlDsa
import java.io.File
import java.security.SecureRandom
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
        data class Done(
            val proof: Proof,
            val seconds: Double,
            val csca: String,
            val presentation: Presentation,
            val segment: SegmentSize,
        ) : State
        data class Failed(val message: String) : State
    }

    /**
     * A sample presentation made right after proving, the way a verifier will check one in
     * stage 5: sign a random challenge with the device key and verify it against the key
     * whose hash the proof carries.
     */
    data class Presentation(val keyStorage: String, val signatureBytes: Int, val signMillis: Long, val valid: Boolean)

    /** [dg15] and [aaSignature] are empty when the chip's answer cannot go in the proof. */
    private class Input(
        val csca: CscaKey,
        val sod: ByteArray,
        val dg1: ByteArray,
        val dg15: ByteArray,
        val aaSignature: ByteArray,
        val key: DeviceKey,
    )

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    @Volatile
    private var pending: Input? = null

    /**
     * Starts proving in [ProverService]. Ignored while a proof is already running. [key] is the
     * device key whose hash the chip signed when the card was read; [chipAnswer] is that
     * answer when the proof can check it (a valid P-256 signature), else null.
     */
    fun start(context: Context, csca: CscaKey, chip: ChipData, key: DeviceKey, chipAnswer: ActiveAuthAnswer?) {
        if (state.value is State.Running) return
        pending = Input(csca, chip.sod, chip.dg1, chipAnswer?.dg15 ?: ByteArray(0), chipAnswer?.response ?: ByteArray(0), key)
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
            val key = input.key
            val segment = SegmentSize.forDevice(context)
            val proof = NativeProver.prove(
                input.csca.spki(), input.sod, input.dg1, LocalDate.now(ZoneOffset.UTC), 18, key.spkiSha256,
                input.dg15, input.aaSignature, segment.po2,
            )
            // No personal data in it: only the CSCA hash, the date and the threshold are public.
            File(context.filesDir, Presenter.PROOF_FILE).writeBytes(proof.raw)
            // Only now: if proving had failed, the previous proof and its key stay usable.
            DeviceKey.retireOthers(context, key)
            val label = ChileCsca.keys.firstOrNull { it.spkiSha256().contentEquals(proof.claim.cscaSha256) }?.label
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            State.Done(proof, seconds, label ?: "desconocida", samplePresentation(key, proof), segment)
        } catch (e: Throwable) {
            State.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
        mutableState.value = result
        return result
    }

    private fun samplePresentation(key: DeviceKey, proof: Proof): Presentation {
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val start = System.nanoTime()
        val signature = key.sign(challenge)
        val millis = (System.nanoTime() - start) / 1_000_000
        val valid = MlDsa.spkiSha256(key.spki).contentEquals(proof.claim.deviceKeySha256) &&
            MlDsa.verify(key.spki, challenge, signature)
        return Presentation(key.storage, signature.size, millis, valid)
    }
}
