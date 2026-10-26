package cl.zkage.verifier

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveSpec

/**
 * Active Authentication (ICAO 9303 part 11, section 6.1): the chip signs an 8-byte challenge
 * with a private key it never reveals, whose public half is DG15, which the SOD signs. A valid
 * answer shows the chip itself answered, not a copy of its files.
 */
object ActiveAuth {
    private val bc = BouncyCastleProvider()

    data class Result(
        /** "RSA" or "EC". */
        val algorithm: String,
        /** RSA modulus or EC group order size. */
        val keyBits: Int,
        /** Hash the signature used: from the ISO 9796-2 trailer for RSA, from DG14 for EC. */
        val digest: String,
        val valid: Boolean,
        /** EC curve name, or null for RSA or an unknown curve. Decides the cost in the zkVM. */
        val curve: String? = null,
    )

    /** EF.DG15 is a SubjectPublicKeyInfo in application tag 0x6F. */
    fun publicKey(dg15: ByteArray): PublicKey {
        val spki = SubjectPublicKeyInfo.getInstance(SodVerifier.unwrap(dg15, 0x6f, "EF.DG15"))
        val type = when (spki.algorithm.algorithm) {
            PKCSObjectIdentifiers.rsaEncryption -> "RSA"
            X9ObjectIdentifiers.id_ecPublicKey -> "EC"
            else -> throw VerificationException("DG15 key is ${spki.algorithm.algorithm}, not RSA or EC")
        }
        return KeyFactory.getInstance(type, bc).generatePublic(X509EncodedKeySpec(spki.encoded))
    }

    /**
     * Checks the chip's [response] to [challenge]. For an EC key, [ecSignatureOid] is the
     * algorithm DG14's ActiveAuthenticationInfo names; ICAO requires it there for EC.
     */
    fun verify(dg15: ByteArray, challenge: ByteArray, response: ByteArray, ecSignatureOid: String? = null): Result =
        when (val key = publicKey(dg15)) {
            is RSAPublicKey -> verifyRsa(key, challenge, response)
            is ECPublicKey -> {
                val digest = ecDigests[ecSignatureOid]
                    ?: throw VerificationException("EC Active Authentication needs DG14's algorithm, got $ecSignatureOid")
                val valid = runCatching {
                    Signature.getInstance("${digest.replace("-", "")}withPLAIN-ECDSA", bc).run {
                        initVerify(key)
                        update(challenge)
                        verify(response)
                    }
                }.getOrDefault(false)
                Result("EC", key.params.order.bitLength(), digest, valid, curveName(key))
            }
            else -> throw VerificationException("DG15 key is ${key.algorithm}")
        }

    /**
     * ISO/IEC 9796-2 signature scheme 1 with partial message recovery: the RSA-opened block is
     * `header | M1 | H(M1 || challenge) | trailer`, where M1 is chosen by the chip.
     */
    private fun verifyRsa(key: RSAPublicKey, challenge: ByteArray, response: ByteArray): Result {
        val bits = key.modulus.bitLength()
        val s = BigInteger(1, response)
        if (s >= key.modulus) return Result("RSA", bits, "?", false)
        val opened = s.modPow(key.publicExponent, key.modulus)
        // Some chips send min(s, n - s); the right one starts with header bits 01.
        val block = listOf(opened, key.modulus - opened)
            .map { fixed(it, (bits + 7) / 8) }
            .firstOrNull { it[0].toInt() and 0xc0 == 0x40 }
            ?: return Result("RSA", bits, "?", false)

        val (digest, trailer) = when {
            block.last() == 0xbc.toByte() -> "SHA-1" to 1
            block.last() == 0xcc.toByte() -> (trailers[block[block.size - 2].toInt() and 0xff] ?: return Result("RSA", bits, "?", false)) to 2
            else -> return Result("RSA", bits, "?", false)
        }
        val md = MessageDigest.getInstance(digest)
        val hashStart = block.size - trailer - md.digestLength
        // M1 starts after the padding, which ends in the first byte whose low nibble is 0xA.
        val m1Start = block.indexOfFirst { it.toInt() and 0x0f == 0x0a } + 1
        if (m1Start == 0 || m1Start > hashStart) return Result("RSA", bits, digest, false)
        md.update(block, m1Start, hashStart - m1Start)
        md.update(challenge)
        val valid = MessageDigest.isEqual(md.digest(), block.copyOfRange(hashStart, block.size - trailer))
        return Result("RSA", bits, digest, valid)
    }

    /** DG15 may name its curve or spell out its parameters; match the latter against known curves. */
    private fun curveName(key: ECPublicKey): String? {
        val params = key.params
        if (params is ECNamedCurveSpec) return params.name
        val names = ECNamedCurveTable.getNames().toList().map { it as String }
        return names.firstOrNull { name ->
            val spec = ECNamedCurveTable.getParameterSpec(name)
            spec.n == params.order &&
                spec.g.normalize().affineXCoord.toBigInteger() == params.generator.affineX &&
                spec.curve.a.toBigInteger() == params.curve.a
        }
    }

    private fun fixed(n: BigInteger, size: Int): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == size -> raw
            raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
            else -> ByteArray(size - raw.size) + raw
        }
    }

    /** ISO/IEC 10118-3 hash identifiers in an explicit trailer `id 0xCC`. */
    private val trailers = mapOf(0x33 to "SHA-1", 0x38 to "SHA-224", 0x34 to "SHA-256", 0x36 to "SHA-384", 0x35 to "SHA-512")

    /** BSI TR-03111 ecdsa-plain-signatures OIDs, as DG14 names them. */
    private val ecDigests = mapOf(
        "0.4.0.127.0.7.1.1.4.1.1" to "SHA-1",
        "0.4.0.127.0.7.1.1.4.1.2" to "SHA-224",
        "0.4.0.127.0.7.1.1.4.1.3" to "SHA-256",
        "0.4.0.127.0.7.1.1.4.1.4" to "SHA-384",
        "0.4.0.127.0.7.1.1.4.1.5" to "SHA-512",
    )
}
