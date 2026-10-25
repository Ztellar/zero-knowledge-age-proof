package cl.zkage.verifier

import java.time.LocalDate

/**
 * The dates in a TD1 MRZ, read with the same rules as `age_core` in the prover, so that
 * this check and the proof agree on every card.
 */
data class MrzDates(val birth: LocalDate, val expiry: LocalDate)

object Mrz {
    /** Extracts the 90-character TD1 MRZ from DG1 (`61 { 5F1F mrz }`). */
    fun td1(dg1: ByteArray): String {
        val start = (0 until dg1.size - 1).firstOrNull { dg1[it] == 0x5f.toByte() && dg1[it + 1] == 0x1f.toByte() }
            ?: throw VerificationException("DG1 has no MRZ (tag 5F1F)")
        var pos = start + 2
        val first = dg1.getOrNull(pos)?.toInt()?.and(0xff) ?: throw VerificationException("DG1 is truncated")
        val len = when {
            first < 0x80 -> first.also { pos += 1 }
            first == 0x81 -> (dg1.getOrNull(pos + 1)?.toInt()?.and(0xff) ?: throw VerificationException("DG1 is truncated")).also { pos += 2 }
            else -> throw VerificationException("DG1 MRZ length is not supported")
        }
        if (len != 90 || pos + len > dg1.size) throw VerificationException("MRZ is not TD1 (90 characters)")
        return String(dg1, pos, len, Charsets.US_ASCII)
    }

    /** Birth at columns 31–36, expiry at 39–44 (1-based, both YYMMDD). */
    fun dates(mrz: String, today: LocalDate): MrzDates =
        MrzDates(birth(mrz.substring(30, 36), today), expiry(mrz.substring(38, 44)))

    /** A birth year that would land after `today` belongs to the previous century. */
    fun birth(field: String, today: LocalDate): LocalDate {
        val (yy, mm, dd) = yymmdd(field)
        val date = LocalDate.of(2000 + yy, mm, dd)
        return if (date.isAfter(today)) date.minusYears(100) else date
    }

    /** Expiry dates of cards in circulation are always in the 2000s. */
    fun expiry(field: String): LocalDate {
        val (yy, mm, dd) = yymmdd(field)
        return LocalDate.of(2000 + yy, mm, dd)
    }

    /**
     * True when the holder turned `years` on or before `today`. Compares (year, month, day)
     * like the prover, so someone born on 29 February turns a year older on 1 March.
     */
    fun isAtLeast(birth: LocalDate, today: LocalDate, years: Int): Boolean {
        val birthday = Triple(birth.year + years, birth.monthValue, birth.dayOfMonth)
        val now = Triple(today.year, today.monthValue, today.dayOfMonth)
        return compareValuesBy(birthday, now, { it.first }, { it.second }, { it.third }) <= 0
    }

    private fun yymmdd(field: String): Triple<Int, Int, Int> {
        if (field.length != 6 || !field.all { it in '0'..'9' }) throw VerificationException("bad MRZ date '$field'")
        val parts = Triple(field.substring(0, 2).toInt(), field.substring(2, 4).toInt(), field.substring(4, 6).toInt())
        // Stricter than the prover, which accepts any day 1–31: an impossible date fails closed here.
        try {
            LocalDate.of(2000 + parts.first, parts.second, parts.third)
        } catch (e: java.time.DateTimeException) {
            throw VerificationException("bad MRZ date '$field'")
        }
        return parts
    }
}
