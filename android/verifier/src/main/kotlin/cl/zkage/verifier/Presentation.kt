package cl.zkage.verifier

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's side of a presentation (decision 0005), matching `prover/verifier` and
 * `prover/verifier-web`.
 */
object Presentation {
    private val tag = "zkage-presentation-v1".toByteArray()

    /** What the verifier's QR carries. */
    data class Request(
        /** `https://host:port`; signed into the message, so the answer only works there. */
        val origin: String,
        val session: String,
        val challenge: ByteArray,
        /** SHA-256 of the verifier's TLS certificate: the only one the app will trust. */
        val certSha256: ByteArray,
    ) {
        val presentationUrl: String get() = "$origin/sessions/$session/presentation"
    }

    /** Parses `zkage://present?origin=…&session=…&challenge=…&pin=…`; null if it is not one. */
    fun parse(link: String): Request? = runCatching {
        val uri = URI(link)
        if (uri.scheme != "zkage" || uri.host != "present") return null
        val query = uri.rawQuery.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
        val origin = query.getValue("origin")
        val parsed = URI(origin)
        require(parsed.scheme == "https" && parsed.path.isNullOrEmpty() && parsed.rawQuery == null)
        val session = query.getValue("session")
        require(session.all { it in '0'..'9' || it in 'a'..'f' })
        Request(origin, session, hex(query.getValue("challenge"), 32), hex(query.getValue("pin"), 32))
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
