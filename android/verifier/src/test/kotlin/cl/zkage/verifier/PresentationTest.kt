package cl.zkage.verifier

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresentationTest {
    /** The Rust verifier's `message_vector` test checks the same bytes. */
    @Test
    fun messageMatchesRust() {
        val expected = File("../../prover/verifier/tests/message-vector.hex").readText().trim()
        val message = Presentation.message("https://192.168.1.5:8443", ByteArray(32) { 7 })
        assertEquals(expected, message.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun parsesVerifierLink() {
        val link = "zkage://present?origin=https%3A%2F%2F192.168.1.5%3A8443&session=958eb396284286f0aaef914abd656fca" +
            "&challenge=${"82".repeat(32)}&pin=${"07".repeat(32)}"
        val request = Presentation.parse(link)!!
        assertEquals("https://192.168.1.5:8443", request.origin)
        assertEquals("https://192.168.1.5:8443/sessions/958eb396284286f0aaef914abd656fca/presentation", request.presentationUrl)
        assertArrayEquals(ByteArray(32) { 0x82.toByte() }, request.challenge)
        assertArrayEquals(ByteArray(32) { 7 }, request.certSha256)
    }

    @Test
    fun rejectsOtherLinks() {
        val ok = "challenge=${"82".repeat(32)}&pin=${"07".repeat(32)}&session=ab"
        assertNull(Presentation.parse("https://example.com/?$ok&origin=https%3A%2F%2Fa.example"))
        // Plain HTTP, a path that would redirect the POST, a short challenge, a session with a slash.
        assertNull(Presentation.parse("zkage://present?$ok&origin=http%3A%2F%2Fa.example"))
        assertNull(Presentation.parse("zkage://present?$ok&origin=https%3A%2F%2Fa.example%2Fx"))
        assertNull(Presentation.parse("zkage://present?origin=https%3A%2F%2Fa.example&session=ab&challenge=82&pin=${"07".repeat(32)}"))
        assertNull(Presentation.parse("zkage://present?origin=https%3A%2F%2Fa.example&session=a%2Fb&challenge=${"82".repeat(32)}&pin=${"07".repeat(32)}"))
    }

    @Test
    fun bodyLayout() {
        val body = Presentation.body(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf())
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 1, 2, 1, 0, 0, 0, 3, 0, 0, 0, 0), body)
    }
}
