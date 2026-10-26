package cl.zkage.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

/** What a proof makes public, decoded from its journal. */
data class ProvedClaim(val cscaSha256: ByteArray, val today: LocalDate, val minAge: Int, val deviceKeySha256: ByteArray)

/** A succinct STARK receipt (bincode) and its claim, already verified on the phone. */
class Proof(val receipt: ByteArray, val claim: ProvedClaim, val raw: ByteArray) {
    companion object {
        /** Reads the prover's output, also as stored in `age-proof.bin`. */
        fun parse(raw: ByteArray): Proof {
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val receipt = ByteArray(buf.int).also { buf.get(it) }
            val csca = ByteArray(32).also { buf.get(it) }
            val year = buf.short.toInt() and 0xffff
            val month = buf.get().toInt()
            val day = buf.get().toInt()
            val min = buf.get().toInt() and 0xff
            val deviceKey = ByteArray(32).also { buf.get(it) }
            return Proof(receipt, ProvedClaim(csca, LocalDate.of(year, month, day), min, deviceKey), raw)
        }
    }
}

/** The Rust prover in `prover/ffi`, built by `prover/scripts/build-android-lib.sh`. */
object NativeProver {
    init {
        System.loadLibrary("age_prover")
    }

    /**
     * Blocking and heavy (about a minute and 2.5 GB on a Galaxy S25): call off the main
     * thread. Throws [IllegalArgumentException] named after the failed check when the chip
     * data does not support the claim.
     */
    fun prove(cscaSpki: ByteArray, sod: ByteArray, dg1: ByteArray, today: LocalDate, minAge: Int, deviceKeySha256: ByteArray): Proof {
        return Proof.parse(prove(cscaSpki, sod, dg1, today.year, today.monthValue, today.dayOfMonth, minAge, deviceKeySha256))
    }

    @JvmStatic
    private external fun prove(
        cscaSpki: ByteArray,
        sod: ByteArray,
        dg1: ByteArray,
        year: Int,
        month: Int,
        day: Int,
        minAge: Int,
        deviceKeySha256: ByteArray,
    ): ByteArray
}
