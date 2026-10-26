package cl.zkage.verifier

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec

/** One country signing CA key. Dates are ISO `yyyy-mm-dd`; `signsUntil` is the end of its private key usage period, when known. */
data class CscaKey(
    val label: String,
    val validFrom: String,
    val validUntil: String,
    val signsUntil: String?,
    val modulus: BigInteger,
    val exponent: BigInteger,
) {
    fun publicKey(): PublicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))

    /** DER SubjectPublicKeyInfo: what the prover takes as the CSCA input. */
    fun spki(): ByteArray = publicKey().encoded

    /** SHA-256 of [spki]: the value a proof's claim carries for this key. */
    fun spkiSha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(spki())

    override fun toString() = label
}
