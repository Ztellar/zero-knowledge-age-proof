package cl.zkage.verifier

import java.math.BigInteger
import java.security.KeyFactory
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

    override fun toString() = label
}
