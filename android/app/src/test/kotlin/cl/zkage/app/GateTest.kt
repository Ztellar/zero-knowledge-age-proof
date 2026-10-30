package cl.zkage.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GateTest {
    private val today = LocalDate.of(2026, 10, 29)
    private val adult = LocalDate.of(1990, 3, 12)
    private val valid = LocalDate.of(2030, 1, 1)

    private fun check(
        signatures: Boolean = true,
        issuer: Boolean = true,
        chip: ChipCheck = ChipCheck.Ok,
        photo: Boolean = true,
        birth: LocalDate = adult,
        expiry: LocalDate = valid,
    ) = Gate.check(signatures, issuer, chip, photo, birth, expiry, today)

    @Test
    fun passesAValidAdultCard() = assertNull(check())

    @Test
    fun everyChipFailureStopsBeforeProving() {
        for (chip in listOf(ChipCheck.Missing, ChipCheck.NotInSod, ChipCheck.NoAnswer, ChipCheck.Invalid)) {
            assertEquals(chip.name, Blocker.NotOriginal, check(chip = chip))
        }
        assertEquals(Blocker.Unsupported, check(chip = ChipCheck.NotProvable))
    }

    @Test
    fun signaturesComeBeforeEverythingElse() {
        assertEquals(Blocker.Altered, check(signatures = false, chip = ChipCheck.Missing, photo = false, expiry = today))
        assertEquals(Blocker.UnknownIssuer, check(issuer = false, chip = ChipCheck.Missing))
    }

    @Test
    fun aMissingPhotoFailsClosed() = assertEquals(Blocker.NoPhoto, check(photo = false))

    @Test
    fun expiresOnItsLastDay() {
        assertEquals(Blocker.Expired(today), check(expiry = today))
        assertNull(check(expiry = today.plusDays(1)))
    }

    @Test
    fun saysWhenAMinorCanProve() {
        val birth = LocalDate.of(2009, 3, 12)
        assertEquals(Blocker.Minor(LocalDate.of(2027, 3, 12)), check(birth = birth))
    }

    @Test
    fun leapDayBirthdaysMoveToTheFirstOfMarch() {
        assertEquals(LocalDate.of(2027, 3, 1), Gate.eligibleOn(LocalDate.of(2008, 2, 29), 19))
        assertEquals(LocalDate.of(2028, 2, 29), Gate.eligibleOn(LocalDate.of(2008, 2, 29), 20))
        assertEquals(LocalDate.of(2026, 2, 28), Gate.eligibleOn(LocalDate.of(2008, 2, 28), 18))
    }
}
