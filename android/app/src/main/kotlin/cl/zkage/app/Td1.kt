package cl.zkage.app

/**
 * Reads the access key from the three MRZ lines on the back of the card (ICAO 9303 TD1),
 * as OCR returns them. Every field it takes has a check digit, and the composite one covers
 * them all, so a misread character fails here instead of failing at the chip.
 */
object Td1 {
    private const val LINE = 30

    /** The access key, or null unless all three check digits and the composite one hold. */
    fun parse(ocr: String): AccessKey? {
        val lines = ocr.lines()
            .map { clean(it) }
            .filter { it.length in LINE - 2..LINE + 2 }
        // OCR may add text above or below; any two consecutive candidate lines may be 1 and 2.
        for (i in 0 until lines.size - 1) {
            parseLines(fit(lines[i]), fit(lines[i + 1]))?.let { return it }
        }
        return null
    }

    fun parseLines(line1: String, line2: String): AccessKey? {
        if (line1.length != LINE || line2.length != LINE || !line1.startsWith("I")) return null
        val number = digitsLike(line1.substring(5, 14), letters = true)
        val numberCheck = digit(line1[14])
        val birth = digitsLike(line2.substring(0, 6))
        val birthCheck = digit(line2[6])
        val expiry = digitsLike(line2.substring(8, 14))
        val expiryCheck = digit(line2[14])
        val composite = digit(line2[29])
        if (numberCheck == null || birthCheck == null || expiryCheck == null || composite == null) return null
        if (check(number) != numberCheck || check(birth) != birthCheck || check(expiry) != expiryCheck) return null
        val compositeData = line1.substring(5, 30) + line2.substring(0, 7) + line2.substring(8, 15) + line2.substring(18, 29)
        if (check(compositeData.replaceRange(0, 9, number).replaceRange(25, 31, birth).replaceRange(32, 38, expiry)) != composite) return null
        if (!birth.all(Char::isDigit) || !expiry.all(Char::isDigit)) return null
        return AccessKey(number.trimEnd('<'), birth, expiry)
    }

    /** ICAO 9303 check digit: weights 7, 3, 1; digits as is, A–Z as 10–35, '<' as 0. */
    fun check(field: String): Int = field.mapIndexed { i, ch ->
        val v = when (ch) {
            in '0'..'9' -> ch - '0'
            in 'A'..'Z' -> ch - 'A' + 10
            else -> 0
        }
        v * intArrayOf(7, 3, 1)[i % 3]
    }.sum() % 10

    /** Upper case, no spaces, and the usual OCR stand-ins for the filler '<'. */
    private fun clean(line: String) = line.uppercase()
        .replace(" ", "")
        .replace('«', '<').replace('‹', '<').replace('〈', '<')
        .filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }

    /** A line one or two characters off is usually trailing fillers lost or doubled. */
    private fun fit(line: String) = if (line.length >= LINE) line.take(LINE) else line.padEnd(LINE, '<')

    /** Digit fields: OCR reads O for 0 and the like. Letters stay in the document number. */
    private fun digitsLike(field: String, letters: Boolean = false) = if (letters) field else field.map {
        when (it) {
            'O', 'Q', 'D' -> '0'
            'I', 'L' -> '1'
            'Z' -> '2'
            'S' -> '5'
            'G' -> '6'
            'B' -> '8'
            else -> it
        }
    }.joinToString("")

    private fun digit(ch: Char): Int? = digitsLike(ch.toString()).single().takeIf(Char::isDigit)?.minus('0')
}
