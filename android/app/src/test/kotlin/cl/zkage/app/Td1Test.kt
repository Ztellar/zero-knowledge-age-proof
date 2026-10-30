package cl.zkage.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Td1Test {
    // ICAO 9303 part 5's TD1 specimen.
    private val line1 = "I<UTOD231458907<<<<<<<<<<<<<<<"
    private val line2 = "7408122F1204159UTO<<<<<<<<<<<6"
    private val line3 = "ERIKSSON<<ANNA<MARIA<<<<<<<<<<"

    @Test
    fun readsTheSpecimen() {
        assertEquals(AccessKey("D23145890", "740812", "120415"), Td1.parse("$line1\n$line2\n$line3"))
    }

    @Test
    fun checkDigitsMatchTheStandard() {
        assertEquals(7, Td1.check("D23145890"))
        assertEquals(2, Td1.check("740812"))
        assertEquals(9, Td1.check("120415"))
    }

    @Test
    fun toleratesOcrNoise() {
        // A header line, a filler read as '«' and lost, a space, and O for 0 in the dates.
        val noisy = "REPUBLICA DE CHILE\n${line1.replaceFirst("<<<", "« <")}\n${line2.replace('0', 'O')}\n$line3"
        assertEquals(AccessKey("D23145890", "740812", "120415"), Td1.parse(noisy))
    }

    @Test
    fun rejectsAMisreadDigit() {
        assertNull(Td1.parse("$line1\n${line2.replaceRange(1, 2, "5")}\n$line3"))
    }

    @Test
    fun rejectsAMisreadDocumentNumber() {
        assertNull(Td1.parse("${line1.replaceRange(6, 7, "8")}\n$line2\n$line3"))
    }
}
