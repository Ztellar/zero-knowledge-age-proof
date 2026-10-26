package cl.zkage.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentSizeTest {
    /** MemTotal as phones report it: below the advertised size, which counts reserved memory. */
    private fun po2(totalBytes: Long, lowRam: Boolean = false) = SegmentSize.forTotalMemory(totalBytes, lowRam).po2

    @Test
    fun largerPhonesGetLargerSegments() {
        assertEquals(19, po2(11_380_220L * 1024)) // the Galaxy S25 ("12 GB")
        assertEquals(18, po2(7_500_000_000)) // an "8 GB" phone
        assertEquals(17, po2(5_600_000_000)) // a "6 GB" phone
        assertEquals(17, po2(3_700_000_000)) // a "4 GB" phone: below the budget, smallest size
    }

    @Test
    fun lowRamDevicesGetTheSmallest() {
        assertEquals(17, po2(11_380_220L * 1024, lowRam = true))
    }
}
