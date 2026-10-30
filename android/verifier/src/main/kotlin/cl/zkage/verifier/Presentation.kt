package cl.zkage.verifier

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's side of a presentation: version 2 of `docs/protocol.md`, matching
 * `prover/verifier` and the `zkage-verifier` npm package.
 */
object Presentation {
    private val tag = "zkage-presentation-v1".toByteArray()

    /** What the verifier's QR carries. */
    data class Request(
        /** `https://host[:port]`; signed into the message, so the answer only works there. */
        val origin: String,
        /** Where to POST the presentation; always on [origin]. */
        val url: String,
        val challenge: ByteArray,
        /**
         * SHA-256 of the verifier's TLS certificate, for a demo with a self-signed one: then it
         * is the only certificate the app trusts. Null to validate it like a browser.
         */
        val certSha256: ByteArray?,
    )

    /**
     * Parses `zkage://present?v=2&origin=…&url=…&challenge=…[&pin=…]`; null if it is not one,
     * or if `url` is not on `origin`, so a page cannot get a signature for one site and send it
     * to another.
     */
    fun parse(link: String): Request? = runCatching {
        val uri = URI(link)
        if (uri.scheme != "zkage" || uri.host != "present") return null
        val pairs = uri.rawQuery.split("&").map {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
        val query = pairs.toMap()
        require(query.size == pairs.size && query["v"] == "2")
        val origin = query.getValue("origin")
        val parsed = URI(origin)
        require(
            parsed.scheme == "https" && !parsed.host.isNullOrEmpty() && parsed.rawUserInfo == null &&
                parsed.rawPath.isNullOrEmpty() && parsed.rawQuery == null && parsed.rawFragment == null,
        )
        val url = query.getValue("url")
        require(url.startsWith("$origin/") && URI(url).run { scheme == "https" && rawFragment == null })
        Request(origin, url, hex(query.getValue("challenge"), 32), query["pin"]?.let { hex(it, 32) })
    }.getOrNull()

    /** `"zkage-presentation-v1" || 0x00 || u16 BE origin length || origin || challenge`. */
    fun message(origin: String, challenge: ByteArray): ByteArray {
        val o = origin.toByteArray()
        require(o.size <= 0xffff && challenge.size == 32)
        return ByteBuffer.allocate(tag.size + 3 + o.size + 32)
            .put(tag).put(0).putShort(o.size.toShort()).put(o).put(challenge)
            .array()
    }

    /** `u32 LE length | receipt | u32 LE length | device key SPKI | u32 LE length | signature`. */
    fun body(receipt: ByteArray, deviceKeySpki: ByteArray, signature: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in listOf(receipt, deviceKeySpki, signature)) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(field.size).array())
            out.write(field)
        }
        return out.toByteArray()
    }

    private fun hex(s: String, bytes: Int): ByteArray {
        require(s.length == 2 * bytes)
        return ByteArray(bytes) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
