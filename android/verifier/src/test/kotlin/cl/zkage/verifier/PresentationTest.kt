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

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun link(
        origin: String = "https://shop.example",
        url: String = "https://shop.example/age/proof?s=8f2a",
        challenge: String = "82".repeat(32),
        extra: String = "",
        v: String = "2",
    ) = "zkage://present?v=$v&origin=${enc(origin)}&url=${enc(url)}&challenge=$challenge$extra"

    /** The link of `docs/protocol.md`, section 1. */
    @Test
    fun parsesVerifierLink() {
        val request = Presentation.parse(link())!!
        assertEquals("https://shop.example", request.origin)
        assertEquals("https://shop.example/age/proof?s=8f2a", request.url)
        assertArrayEquals(ByteArray(32) { 0x82.toByte() }, request.challenge)
        assertNull(request.certSha256)

        val pinned = Presentation.parse(link("https://127.0.0.1:8443", "https://127.0.0.1:8443/sessions/ab/presentation", extra = "&pin=${"07".repeat(32)}"))!!
        assertArrayEquals(ByteArray(32) { 7 }, pinned.certSha256)
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(Presentation.parse(link().replace("zkage://present", "https://example.com/")))
        assertNull(Presentation.parse(link(v = "1")))
        assertNull(Presentation.parse(link().replace("v=2&", "")))
        // Plain HTTP, an origin with a path, user info or query.
        assertNull(Presentation.parse(link("http://shop.example", "http://shop.example/p")))
        assertNull(Presentation.parse(link("https://shop.example/x", "https://shop.example/x/p")))
        assertNull(Presentation.parse(link("https://u@shop.example", "https://u@shop.example/p")))
        // A url on another origin, including one that only starts like it.
        assertNull(Presentation.parse(link(url = "https://evil.example/p")))
        assertNull(Presentation.parse(link(url = "https://shop.example.evil.example/p")))
        assertNull(Presentation.parse(link(url = "https://shop.example")))
        // A short challenge, a bad pin, a parameter twice.
        assertNull(Presentation.parse(link(challenge = "82")))
        assertNull(Presentation.parse(link(extra = "&pin=07")))
        assertNull(Presentation.parse(link(extra = "&challenge=${"00".repeat(32)}")))
    }

    @Test
    fun bodyLayout() {
        val body = Presentation.body(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf())
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 1, 2, 1, 0, 0, 0, 3, 0, 0, 0, 0), body)
    }
}
