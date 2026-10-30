package cl.zkage.app

import cl.zkage.verifier.Mrz
import java.time.LocalDate

/** What Active Authentication said about the chip, from the most to the least broken. */
enum class ChipCheck {
    /** No DG15: the chip cannot prove it is original. */
    Missing,
    /** DG15 is not the one the SOD signs. */
    NotInSod,
    /** The chip did not answer, or its answer could not be checked. */
    NoAnswer,
    /** It answered, and the signature is wrong. */
    Invalid,
    /** A valid signature, but not ECDSA P-256 with SHA-256: the proof cannot check it. */
    NotProvable,
    Ok,
}

/** Why the proof cannot go on after reading the card; null in [Gate.check] means it can. */
sealed interface Blocker {
    /** The signatures do not hold: the data was altered or is not from the Registro Civil. */
    data object Altered : Blocker
    /** No known Chilean country key signed it (a new key the app lacks, or not Chilean). */
    data object UnknownIssuer : Blocker
    /** Decision 0009: a chip that cannot prove it is original stops here. */
    data object NotOriginal : Blocker
    data object Unsupported : Blocker
    /** The chip photo is missing, unreadable or not the one the SOD signs. */
    data object NoPhoto : Blocker
    data class Expired(val on: LocalDate) : Blocker
    data class Minor(val eligibleOn: LocalDate) : Blocker
}

object Gate {
    /**
     * The order is the order of trust: nothing about age or expiry means anything until the
     * signatures and the chip hold.
     */
    fun check(
        signaturesValid: Boolean,
        issuerKnown: Boolean,
        chip: ChipCheck,
        photoValid: Boolean,
        birth: LocalDate,
        expiry: LocalDate,
        today: LocalDate,
        minAge: Int = 18,
    ): Blocker? = when {
        !signaturesValid -> Blocker.Altered
        !issuerKnown -> Blocker.UnknownIssuer
        chip == ChipCheck.NotProvable -> Blocker.Unsupported
        chip != ChipCheck.Ok -> Blocker.NotOriginal
        !photoValid -> Blocker.NoPhoto
        !expiry.isAfter(today) -> Blocker.Expired(expiry)
        !Mrz.isAtLeast(birth, today, minAge) -> Blocker.Minor(eligibleOn(birth, minAge))
        else -> null
    }

    /**
     * The first day [Mrz.isAtLeast] says yes. Someone born on 29 February turns a year older
     * on 1 March in a common year, as in the prover.
     */
    fun eligibleOn(birth: LocalDate, years: Int): LocalDate {
        val candidate = birth.plusYears(years.toLong())
        return if (Mrz.isAtLeast(birth, candidate, years)) candidate else candidate.plusDays(1)
    }
}
