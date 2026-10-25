package cl.zkage.verifier

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the `age_boundaries` and `century_resolution` tests of `age_core`. */
class MrzTest {
    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun ageBoundaries() {
        assertTrue(Mrz.isAtLeast(LocalDate.of(2008, 10, 22), today, 18))
        assertFalse(Mrz.isAtLeast(LocalDate.of(2008, 10, 23), today, 18))
        // Born on 29 February: 18 on 1 March of a non-leap year.
        assertFalse(Mrz.isAtLeast(LocalDate.of(2008, 2, 29), LocalDate.of(2026, 2, 28), 18))
        assertTrue(Mrz.isAtLeast(LocalDate.of(2008, 2, 29), LocalDate.of(2026, 3, 1), 18))
    }

    @Test
    fun centuryResolution() {
        assertEquals(LocalDate.of(1995, 6, 15), Mrz.birth("950615", today))
        assertEquals(LocalDate.of(2026, 10, 22), Mrz.birth("261022", today))
        assertEquals(LocalDate.of(1926, 10, 23), Mrz.birth("261023", today))
    }

    @Test(expected = VerificationException::class)
    fun impossibleDateFailsClosed() {
        Mrz.expiry("300231")
    }
}
